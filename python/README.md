# Acme Fiber voice agent: Python

A customer calls a Sinch number and an AI agent answers through Voice Relay: Sinch does speech-to-text and text-to-speech, and this server only exchanges text with the agent. When the agent decides the call needs a human, or the customer presses 1, the server sends one `PATCH` and Sinch does the rest. It plays a transfer notice and hold music, dials the technicians in order, plays a summary (the "whisper") to the first one who answers, bridges them with the customer and drops the AI leg. If nobody answers, the customer is told to call again later.

```
caller ──► Sinch number ──► service (STATIC call behavior)
                              answer → bridgeCall main-bridge → dial VOICE_RELAY "ai-agent"
                                                                   │
                                          wss://<public-host>/relay ▼
                                                            this server (app.py) ── Claude (Strands Agents)
                                                                   │
  transfer_to_human or DTMF 1 → PATCH /sessions/{sessionId}/calls/inbound   (customer's leg)
                          "I'm connecting you with a technician." + hold music
                          dial tech-1 → whisper → stop music, bridgeCall main-bridge, hangup ai-agent
                          (no answer) dial tech-2 → … → dial tech-3
                          (nobody) stop music, "Sorry, no technician…" → hangup ai-agent, hangup inbound
```

The use case, call flow and escalation rules are in [`docs/use-case.md`](../docs/use-case.md).

## Files

| File | Purpose |
| --- | --- |
| `config.py` | Loads and validates environment variables; creates the Sinch client. |
| `setup_service.py` | Sets the service STATIC call behavior with the SDK SVAML helpers. Run it again whenever the public URL changes. |
| `app.py` | Starlette server: `WS /relay` (Voice Relay, call state, the keypad option) and `GET /health`. |
| `agent.py` | Strands agent (Anthropic) with the `transfer_to_human` tool. |
| `handoff.py` | Builds the handoff SVAML with the SDK helpers, sends the `PATCH` and writes the handoff log. |
| `relay_protocol.py` | `RelayMessages`: parses and builds Voice Relay messages. |
| `call_sinch_phone_number.py` | Test helper: rings your phone from a Sinch number and bridges you with a call to `SINCH_NUMBER`. |

## Prerequisites

- Python 3.10 or later.
- A Sinch project with an access key (Dashboard → Settings → Access Keys).
- A Voice API v2 service with a phone number assigned to it. `setup_service.py` **overwrites that service's call behavior**.
- The Sinch client uses the **North America** voice region (`create_sinch_client` in `config.py`). If your calls live in another region, change `voice_region`.
- An Anthropic API key.
- One to three technician mobiles.
- [ngrok](https://ngrok.com/) (or any tunnel that supports WebSockets).

## 1. Install

All commands in this README run from the `python/` directory, with the virtual environment created there (`python/.venv`).

```bash
cd python
python3 -m venv .venv   # python3 --version must be 3.10 or later
.venv/bin/pip install -r requirements.txt
```

## 2. Configure

```bash
cp .env.example .env
```

Fill in the Sinch credentials, `SINCH_SERVICE_ID`, `SINCH_NUMBER`, `TECHNICIAN_NUMBERS`, `HOLD_MUSIC_URL` and `LLM_API_KEY`. Leave `RELAY_PUBLIC_URL` for the next step.

| Variable | Purpose |
| --- | --- |
| `SINCH_PROJECT_ID`, `SINCH_KEY_ID`, `SINCH_KEY_SECRET` | SDK credentials |
| `SINCH_SERVICE_ID` | Service that answers the number |
| `SINCH_NUMBER` | Number assigned to `SINCH_SERVICE_ID`; caller ID shown to technicians |
| `TECHNICIAN_NUMBERS` | Exactly three technician mobiles in dialing order, E.164, comma-separated. They are dialed as `tech-1`, `tech-2`, `tech-3`. Repeat a number if you have fewer. |
| `RELAY_PUBLIC_URL` | Public `wss://…/relay` URL of this server |
| `HOLD_MUSIC_URL` | Public URL of the hold music (e.g. an MP3). It does not loop, so make it last about 70 s (3 × `DIAL_TIMEOUT_SECONDS` plus the whisper). The sample in `.env.example` lasts only 12 s. |
| `LLM_API_KEY` | Anthropic API key |
| `TEST_FROM_NUMBER`, `TEST_PHONE_NUMBER` | Optional. Only for `call_sinch_phone_number.py` (step 6) |

## 3. Expose the server

In a separate terminal:

```bash
ngrok http 8000
```

Copy the `https://<host>` forwarding URL and set it in `.env` as a WebSocket URL:

```
RELAY_PUBLIC_URL=wss://<host>/relay
```

The free ngrok URL changes every time ngrok restarts. When it changes, update `.env` and repeat steps 4 and 5.

## 4. Start the server

```bash
.venv/bin/uvicorn --factory app:create_app --port 8000
```

## 5. Update the service settings

This sets the service's call behavior: answer the call, bridge it and connect it to this server through Voice Relay.

```bash
.venv/bin/python setup_service.py
```

Expected: `Service <id> updated: STATIC call behavior dialing wss://<host>/relay`.

## 6. Call the demo

Call `SINCH_NUMBER` from your phone. Use a phone that is not in `TECHNICIAN_NUMBERS`, so that you can answer as a technician on another one.

You hear the greeting. Describe a problem to talk to the agent, or press 1 to go straight to a technician. Then answer on a technician mobile to hear the whisper and talk to the customer, or don't answer any of them to hear the "no technician" message.

### If you can't call the number directly

If `SINCH_NUMBER` is in another country or your phone can't reach it, let Sinch call you instead. Add `TEST_FROM_NUMBER` (another Sinch number in the project) and `TEST_PHONE_NUMBER` (your phone) to `.env`, then run:

```bash
.venv/bin/python call_sinch_phone_number.py
```

Your phone rings from `TEST_FROM_NUMBER`. When you answer, Sinch calls `SINCH_NUMBER` and connects both calls, so you talk to the demo as if you had dialed it. Each call is limited to 60 seconds.
