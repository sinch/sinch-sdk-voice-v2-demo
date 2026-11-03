# Blog post snippets

Each code block in [`blog-post.md`](blog-post.md) has a `<!-- snippet: <id> -->` marker. To publish the post in another language, replace each block with the equivalent code. The text around the snippets doesn't mention any language-specific names.

| Snippet id | What it shows | Python source |
| --- | --- | --- |
| `service-call-behavior` | Updating the service with a STATIC call behavior: `answer`, `bridgeCall` into `main-bridge`, `dial` the Voice Relay leg (`ai-agent`) with `onAnswer` → `bridgeCall`, `name` `inbound`, and `onHangup` that hangs up `ai-agent`, `tech-1`, `tech-2`, `tech-3`. | `setup_service.py` |
| `relay-loop` | The WebSocket message loop (simplified): `connect` → create the session, `answer` and greet; ignore everything once the handoff has started; `text` (not a correction) → agent; `dtmf` with 1 → keypad handoff. | `app.py` (`relay`) |
| `transfer-tool` | The `transfer_to_human(reason, summary)` tool, which only records the request. | `agent.py` |
| `handle-transcript` | After the agent's turn: start the handoff if there is a transfer request, otherwise send the reply as text. | `app.py` (`handle_transcript`) |
| `keypad-handoff` | Pressing 1: summary from the agent if there was a conversation, fixed text otherwise or if the LLM fails, then hand off with `customer_request`. | `app.py` (`request_technician`) |
| `handoff-commands` | The handoff SVAML for the first technician: named messages (notice + hold music), `dial` with caller ID, name and timeout; `onAnswer` whisper with `onFinish` → stop messages, `bridgeCall`, hang up `ai-agent`; `onTimeout` elided; `onHangup` → hang up `inbound`. | `handoff.py` (`build_handoff_commands`) |
| `no-technician` | The last `onTimeout`: stop messages, "no technician" text, then hang up `ai-agent` and `inbound`. | `handoff.py` (`build_handoff_commands`) |
| `send-handoff` | Get the session from the `ai-agent` call ID and send the commands to the `inbound` leg by name. | `handoff.py` (`send_handoff`) |
| `start-handoff` | Mark the handoff as started, send `interrupt` over the WebSocket, then send the handoff (off the event loop if the SDK client is synchronous). | `app.py` (`start_handoff`) |

Snippets are simplified from the source (logging and type hints removed, some lines elided). Keep the same simplifications in every language so the text matches.
