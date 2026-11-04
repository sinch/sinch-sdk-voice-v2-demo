# Acme Fiber: AI voice agent with mid-call handoff to a human

Sinch Voice API v2 · Voice Relay · Sinch SDK Python · Google ADK

## Summary

A customer calls **Acme Fiber**, a fictional internet provider. An AI agent answers through Voice Relay: Sinch does speech-to-text and text-to-speech, and our server only exchanges text with the agent.

The customer gets to a human in one of two ways:

- **The agent decides.** It calls the `transfer_to_human` tool, for example when the problem needs a technician.
- **The customer presses 1.** The greeting offers it, and it works even if the LLM is down.

The server then sends **one `PATCH`** to the customer's call leg, and Sinch runs the rest of the call on its own:

1. It tells the customer "I'm connecting you with a technician." and plays hold music.
2. It dials three technician mobiles, one after another.
3. It plays an LLM-written summary (the "whisper") to the first technician who answers, then bridges them with the customer and drops the AI.
4. If nobody answers, it tells the customer that no technician is available and hangs up.

Once the handoff starts, the AI stays silent and the call never goes back to it.

## What the demo shows

- **The Sinch Python SDK and its SVAML v2 helpers.** Every Voice API call and every SVAML sequence is built with the SDK: the service's call behavior, the handoff with its nested dial chain, and the fallback that hangs up.
- **Voice Relay with an LLM agent.** The server never handles audio, only text messages over a WebSocket.

## Call legs

The whole call is one Voice API v2 session with one bridge, `main-bridge`. Every participant is a call leg with a name:

| Leg | What it is |
| --- | --- |
| `inbound` | The customer's PSTN call to the Sinch number. |
| `ai-agent` | The Voice Relay leg, connected to our server over a WebSocket. |
| `tech-1`, `tech-2`, `tech-3` | The technician mobiles, dialed in this order during a handoff. |

The service's STATIC call behavior, set once by `setup_service.py`, builds the starting point:

1. `answer` the `inbound` leg.
2. `bridgeCall` it into `main-bridge`.
3. `dial` the `ai-agent` leg to a `VOICE_RELAY` destination (our WebSocket URL, TTS voice and STT language). When it answers, it joins `main-bridge` too.
4. An `onHangup` on the `inbound` leg: when the customer hangs up, hang up `ai-agent`, `tech-1`, `tech-2` and `tech-3`.

## Call flow

```
Customer calls the Sinch number
   │
   ▼
Sinch answers and connects the call to the AI agent
   │
   ▼
Customer talks with the AI agent
   │
   ▼
Handoff starts when either:
  • the agent decides the call needs a human (transfer_to_human)
  • the customer presses 1
   │
   ▼
The server updates the live call with the transfer instructions
   │
   ▼
Customer hears a transfer notice and hold music
Sinch dials technicians 1, 2 and 3, one after another
   │
   ├── someone answers ──► they hear a short summary of the problem,
   │                       then are connected to the customer
   │
   └── nobody answers ───► "Sorry, no technician is available…"
                           and the call ends
```

1. **The call starts.** The customer dials the Sinch number. The STATIC call
   behavior answers `inbound`, puts it in `main-bridge`, and dials `ai-agent`
   into the same bridge. From here on Sinch does the speech work and our server
   only exchanges text.

2. **The AI handles the call.** Transcripts arrive over the WebSocket, the
   agent replies with text, Sinch speaks it. The greeting offers pressing 1.

3. **Something triggers a handoff.** Either the agent calls
   `transfer_to_human(reason, summary)`, or the customer presses 1 and the
   server asks the agent for a summary. Both paths end in the same place: one
   `PATCH` to the `inbound` leg.

4. **Sinch runs the handoff on its own.** It tells the customer it is
   connecting them and plays hold music, dials `tech-1`, `tech-2` and `tech-3`
   in order, whispers the summary to the first one who answers and bridges them
   with the customer, dropping `ai-agent`. If nobody answers, it tells the
   customer and hangs up.

## Escalation

The agent decides the cases that need judgment. Pressing 1 is the path that does not depend on the LLM.

| Trigger | Decided by | `reason` |
| --- | --- | --- |
| The customer asks for a person | Agent | `customer_request` |
| The customer presses 1 | Server (Voice Relay `dtmf`) | `customer_request` |
| Cancellation, billing dispute, technician visit or ownership change | Agent | `out_of_scope` |
| The problem needs a technician, such as no signal at the fiber terminal | Agent | `needs_technician` |
| The conversation makes no progress | Agent | `stuck` |

The agent's only tool is `transfer_to_human(reason, summary)`. The tool just records the request; the server runs the handoff after the agent's turn. The summary is two or three sentences that can be spoken in under 15 seconds: the account number if known, the problem and what was already tried. That is why the agent asks for the account number early in the call.

When the customer presses 1, the server asks the agent for a summary of the conversation so far. If there was no conversation yet, or the LLM fails, the whisper is a fixed text: "The customer pressed 1 to talk to a technician." The handoff goes ahead either way.

There is no keyword rule (such as matching "human" or "agent" in the transcript). It gave false positives ("your agent told me…"), and pressing 1 already guarantees a way out that does not depend on the LLM.


## Scope

**In scope:** inbound PSTN call to a Sinch number; AI agent over Voice Relay with Google ADK and Gemini; escalation through an LLM tool and the keypad; handoff with sequential dialing to three technicians and a whisper; what happens when nobody answers or the LLM fails; English (`en-US`).

**Out of scope:** audio streaming (STREAM/PCM); SIP or app destinations; queues and real agent availability; recording and analytics; persistent history; a real CRM or ticketing system (`agent.py` shows, commented out, where such tools would go); production deployment (the demo uses an ngrok-style tunnel).
