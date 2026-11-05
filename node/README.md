# Acme Fiber voice agent: Node.js

A customer calls a Sinch number and an AI agent answers through Voice Relay: Sinch does speech-to-text and text-to-speech, and this server only exchanges text with the agent. When the agent decides the call needs a human, or the customer presses 1, the server sends one `PATCH` and Sinch does the rest. It plays a transfer notice and hold music, dials the technicians in order, plays a summary (the "whisper") to the first one who answers, bridges them with the customer and drops the AI leg. If nobody answers, the customer is told to call again later.

```
Customer calls the Sinch number
   │
   ▼
Sinch answers and connects the call to the AI agent.
   │
   ▼
Customer talks with the AI agent
   │
   ├── the agent decides the call needs a human (transfer_to_human)
   └── or the customer presses 1
   │
   ▼
The server updates the live call with the transfer instructions.
   │
   ▼
Customer hears a transfer notice and hold music.
Sinch dials the first technician.
   │                            │
 answers                    no answer
   │                            │
   ▼                            ▼
Technician hears a        Sinch dials the next one
short summary of the      (up to 3 technicians).
problem, then is                │
connected to the          If nobody answers:
customer.                 "Sorry, no technician is
                          available…" and hang up.
```

The use case, call flow and escalation rules are in [`docs/use-case.md`](../docs/use-case.md). This is the same demo as the [Python](../python/README.md) and [Java](../java/README.md) versions, written in TypeScript.

## Files

| File | Purpose |
| --- | --- |
| `src/config.ts` | Loads and validates environment variables; creates the Sinch client. |
| `src/setup-service.ts` | Sets the service STATIC call behavior with the SDK SVAML helpers. Run it again whenever the public URL changes. |
| `src/app.ts` | HTTP server with `ws`: `WS /relay` (Voice Relay, call state, the keypad option) and `GET /health`. |
| `src/agent.ts` | Google ADK agent (Gemini) with the `transfer_to_human` tool. |
| `src/handoff.ts` | Builds the handoff SVAML with the SDK helpers, sends the `PATCH` and writes the handoff log. |
| `src/relay-protocol.ts` | `RelayMessages`: parses and builds Voice Relay messages. |
| `src/call-sinch-phone-number.ts` | Test helper: rings your phone from a Sinch number and bridges you with a call to `SINCH_NUMBER`. |

The scripts run the TypeScript sources directly with [`tsx`](https://tsx.is/), so there is no build step.

## Prerequisites

- Node.js 22 or later.
- A Sinch project with an access key (Dashboard → Settings → Access Keys).
- A Voice API v2 service with a phone number assigned to it. `npm run setup-service` **overwrites that service's call behavior**.
- The Sinch client uses the **North America** voice region (`VOICE_V2_HOSTNAME` in `src/config.ts`). If your calls live in another region, change the hostname (for example `https://eu1.voice.api.sinch.com`).
- A Gemini API key from [Google AI Studio](https://aistudio.google.com/apikey).
- One to three technician mobiles.
- [ngrok](https://ngrok.com/) (or any tunnel that supports WebSockets).

## 1. Install

All commands in this README run from the `node/` directory.

```bash
cd node
npm install
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
| `LLM_API_KEY` | Gemini API key. The model is set by `LLM_MODEL` in `src/config.ts` (`gemini-3.5-flash-lite`). |
| `TEST_FROM_NUMBER`, `TEST_PHONE_NUMBER` | Optional. Only for `npm run call-sinch-phone-number` (step 6) |

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
npm start
```

## 5. Update the service settings

This sets the service's call behavior: answer the call, bridge it and connect it to this server through Voice Relay.

```bash
npm run setup-service
```

Expected: `Service <id> updated: STATIC call behavior dialing wss://<host>/relay`.

## 6. Call the demo

Call `SINCH_NUMBER` from your phone. Use a phone that is not in `TECHNICIAN_NUMBERS`, so that you can answer as a technician on another one.

You hear the greeting. Describe a problem to talk to the agent, or press 1 to go straight to a technician. Then answer on a technician mobile to hear the whisper and talk to the customer, or don't answer any of them to hear the "no technician" message.

Each handoff is appended to `handoffs.jsonl` in the `node/` directory.

### If you can't call the number directly

If `SINCH_NUMBER` is in another country or your phone can't reach it, let Sinch call you instead. Add `TEST_FROM_NUMBER` (another Sinch number in the project) and `TEST_PHONE_NUMBER` (your phone) to `.env`, then run:

```bash
npm run call-sinch-phone-number
```

Your phone rings from `TEST_FROM_NUMBER`. When you answer, Sinch calls `SINCH_NUMBER` and connects both calls, so you talk to the demo as if you had dialed it. Each call is limited to 60 seconds.

## Type check

```bash
npm run typecheck
```
