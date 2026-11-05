"""AI support agent (Google ADK + Gemini)."""

import uuid
from typing import Any, Literal

from config import Config
from google.adk.agents import Agent
from google.adk.models.google_llm import Gemini
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.genai import types

APP_NAME: str = "acme_fiber"
USER_ID: str = "caller"

# ADK declares these values to the model as an enum.
TransferReason = Literal["customer_request", "out_of_scope", "stuck", "needs_technician"]

SYSTEM_PROMPT: str = """You are the phone support agent of Acme Fiber, an internet provider.
Everything you write is spoken to the customer by text-to-speech:
- Answer in one or two short sentences. No lists, no markdown, no emojis.
- Spell out numbers the way you would say them.

How to help:
- Ask for the account number, so the technician gets it if the call is transferred.
- Help with simple connection problems, such as restarting the router.

When to call transfer_to_human:
- reason "out_of_scope": the customer asks about cancellation, a billing dispute, a technician visit or an ownership change.
- reason "needs_technician": the problem needs a technician, such as no signal at the fiber terminal.
- reason "stuck": the conversation makes no progress, because you cannot understand the problem or the customer keeps repeating it.
- reason "customer_request": the customer asks for a person.
The summary must be two or three sentences that can be spoken in under 15 seconds:
the account number if known, the problem, and what was already tried.
After calling transfer_to_human, do not say anything else: the system tells the customer."""

SUMMARY_PROMPT: str = """The call is being transferred to a technician. Do not call any tool.
Write only the handoff summary: two or three sentences that can be spoken in under 15 seconds,
with the account number if known, the problem, and what was already tried."""


class TransferRequest:
    def __init__(self, reason: TransferReason, summary: str) -> None:
        self.reason: TransferReason = reason
        self.summary: str = summary


class SupportAgent:
    def __init__(self, config: Config) -> None:
        self.transfer_request: TransferRequest | None = None
        model: Gemini = Gemini(model=config.llm_model, client_kwargs={"api_key": config.llm_api_key})
        agent: Agent = Agent(
            name="acme_fiber_support",
            model=model,
            instruction=SYSTEM_PROMPT,
            tools=self._create_tools(),
        )
        # One in-memory session per call holds the conversation history.
        self._session_id: str = str(uuid.uuid4())
        self._runner: Runner = Runner(
            app_name=APP_NAME,
            agent=agent,
            session_service=InMemorySessionService(),
            auto_create_session=True,
        )
        self._has_conversation: bool = False

    async def reply(self, text: str) -> str:
        self.transfer_request = None
        self._has_conversation = True
        return await self._run(text)

    def has_conversation(self) -> bool:
        return self._has_conversation

    async def summarize(self) -> str:
        return await self._run(SUMMARY_PROMPT)

    async def _run(self, text: str) -> str:
        message: types.Content = types.UserContent(text)
        reply: str = ""
        async for event in self._runner.run_async(user_id=USER_ID, session_id=self._session_id, new_message=message):
            if event.is_final_response() and event.content and event.content.parts:
                reply = "".join(part.text or "" for part in event.content.parts)
        return reply.strip()

    def _create_tools(self) -> list[Any]:
        # Add your own tools here as plain functions (ADK reads their signature and docstring),
        # and list them in the return value and in SYSTEM_PROMPT. For example:
        #
        # def lookup_account(account_number: str) -> dict[str, Any]:
        #     """Look up a customer account by account number.
        #
        #     Args:
        #         account_number: The account number given by the customer, digits only.
        #     """
        #     return your_crm.get_account(account_number)
        #
        # def get_ticket_status(account_number: str) -> dict[str, Any]:
        #     """Get the open support ticket of an account, if any.
        #
        #     Args:
        #         account_number: A verified account number.
        #     """
        #     return your_ticketing.get_open_ticket(account_number)

        def transfer_to_human(reason: TransferReason, summary: str) -> str:
            """Transfer the call to a human technician.

            Args:
                reason: Why the call is transferred.
                summary: Two or three sentences for the technician: account number if known,
                    the problem, and what was already tried.
            """
            # Only records the request; the server runs the handoff.
            self.transfer_request = TransferRequest(reason, summary)
            return "Transfer requested."

        return [transfer_to_human]
