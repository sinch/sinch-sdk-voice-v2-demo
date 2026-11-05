"""Voice Relay WebSocket server (/relay) with the AI agent and the handoff, plus GET /health.

  1. Sinch sends connect: the server answers and greets the customer.
  2. Each transcript goes to the agent, and its reply goes back as text.
  3. On transfer_to_human or DTMF 1, the server PATCHes the customer's leg and stays silent.

Run: uvicorn --factory app:create_app --port 8000   (or: python app.py)
"""

import asyncio
import json
import logging
from typing import Any

import handoff
import uvicorn
from agent import SupportAgent
from config import Config, create_sinch_client, load_config
from relay_protocol import RelayMessages
from sinch import SinchClient
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route, WebSocketRoute
from starlette.websockets import WebSocket, WebSocketDisconnect

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s", datefmt="%H:%M:%S")
log: logging.Logger = logging.getLogger("relay")
# Raw Voice Relay messages are cut to this length in the log.
MAX_LOGGED_MESSAGE_LENGTH: int = 200

APOLOGY: str = "Sorry, I'm having trouble right now. Could you say that again?"

# Whisper for DTMF 1 when there is no conversation to summarize.
KEYPAD_SUMMARY: str = "The customer pressed 1 to talk to a technician."
TECHNICIAN_KEY: str = "1"


class CallSession:
    """State of one call."""

    def __init__(self, call_id: str, agent: SupportAgent) -> None:
        self.call_id: str = call_id
        self.agent: SupportAgent = agent
        self.handoff_started: bool = False


def preview(raw: str) -> str:
    return raw if len(raw) <= MAX_LOGGED_MESSAGE_LENGTH else raw[:MAX_LOGGED_MESSAGE_LENGTH] + "…"


async def send(websocket: WebSocket, message: dict[str, Any]) -> None:
    raw: str = json.dumps(message)
    log.info("  WS >> %s", preview(raw))
    await websocket.send_text(raw)


async def start_handoff(
    websocket: WebSocket, session: CallSession, config: Config, client: SinchClient, reason: str, summary: str
) -> None:
    """From here on Sinch runs the call (see handoff.run_handoff)."""
    session.handoff_started = True
    log.info("    handoff  reason=%s  summary=%r", reason, summary)
    # Stop any AI speech.
    await send(websocket, RelayMessages.interrupt())
    try:
        await asyncio.to_thread(handoff.run_handoff, client, config, session.call_id, reason, summary)
        log.info("    PATCH handoff → sent")
    except Exception as exc:
        log.warning("    [!] PATCH handoff failed: %s", exc)


async def request_technician(websocket: WebSocket, session: CallSession, config: Config, client: SinchClient) -> None:
    """DTMF 1: hand off without asking the LLM. The LLM only writes the whisper, if there was a conversation."""
    summary: str = KEYPAD_SUMMARY
    if session.agent.has_conversation():
        try:
            summary = await session.agent.summarize() or KEYPAD_SUMMARY
        except Exception as exc:
            log.warning("    [!] summary error, using the fixed one: %s", exc)
    await start_handoff(websocket, session, config, client, "customer_request", summary)


async def handle_transcript(
    websocket: WebSocket, session: CallSession, config: Config, client: SinchClient, text: str
) -> None:
    agent: SupportAgent = session.agent
    try:
        reply: str = await agent.reply(text)
        log.info("    LLM ← %r", reply)
        if agent.transfer_request is not None:
            request = agent.transfer_request
            await start_handoff(websocket, session, config, client, request.reason, request.summary)
            return
    except Exception as exc:
        log.warning("    [!] LLM error: %s", exc)
        if not session.handoff_started:
            await send(websocket, RelayMessages.text(APOLOGY))
        return

    await send(websocket, RelayMessages.text(reply or APOLOGY))


def create_app() -> Starlette:
    config: Config = load_config()
    client: SinchClient = create_sinch_client(config)

    async def health(request: Request) -> JSONResponse:
        return JSONResponse({"status": "ok"})

    async def relay(websocket: WebSocket) -> None:
        await websocket.accept()
        session: CallSession | None = None
        try:
            while True:
                raw: str = await websocket.receive_text()
                log.info("WS << %s", preview(raw))
                message: dict[str, Any] = RelayMessages.parse(raw)
                command: str = message["command"]
                if command == RelayMessages.CONNECT:
                    session = CallSession(message["callId"], SupportAgent(config))
                    log.info("    connect  callId=%s  serviceId=%s", session.call_id, message.get("serviceId"))
                    await send(websocket, RelayMessages.answer())
                    await send(websocket, RelayMessages.text(config.greeting))
                elif command == RelayMessages.TEXT and not message.get("isCorrection") and not session.handoff_started:
                    log.info("    STT → %r", message.get("text"))
                    await handle_transcript(websocket, session, config, client, message.get("text", ""))
                elif (
                    command == RelayMessages.DTMF
                    and TECHNICIAN_KEY in message.get("sequence", "")
                    and not session.handoff_started
                ):
                    log.info("    DTMF %s → technician requested", message.get("sequence"))
                    await request_technician(websocket, session, config, client)
                elif session.handoff_started:
                    log.info("    ignored: handoff started")
        except WebSocketDisconnect as exc:
            log.info("    websocket closed  callId=%s  code=%s", session.call_id if session else "-", exc.code)

    return Starlette(routes=[Route("/health", health), WebSocketRoute("/relay", relay)])


if __name__ == "__main__":
    uvicorn.run(create_app(), host="0.0.0.0", port=load_config().port)
