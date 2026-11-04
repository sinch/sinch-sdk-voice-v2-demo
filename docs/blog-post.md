---
title: Hand Off an AI Voice Agent to a Human Mid-Call with Sinch Voice API v2
published: false
tags: ai, voice, python, tutorial
---

An AI agent on the phone can be very helpful and answer a lot of questions, but some requests have to be handled by a human. When that happens, the caller shouldn't have to hang up, dial another number and explain the problem all over again.

What you want is a warm transfer: the customer stays on the line and hears hold music, and the technician who picks up has access to a summary of the customer's problem.

Sinch Voice API v2 lets you set up an AI agent that answers your customers' calls, and gives you an easy way to act on a call while it's in progress, for example to transfer it to a human technician. In this post, you set up the whole transfer with a single request that configures the hold music, which technicians to dial and in what order, and what to tell the customer if nobody answers.

Voice API v2 is in public preview, so features, limits, documentation, and behavior can still change. I built and ran the demo in this post against real phone calls.

For a deeper look at Voice Relay, services and call behaviors, see Gunnar Grosch's [Connect AI Agents to Phone Calls with Sinch Voice API v2](https://dev.to/gunnargrosch/connect-ai-agents-to-phone-calls-with-sinch-voice-api-v2-4o1p). This post focuses on moving a live call from the AI agent to a human.

## What you'll build

**Acme Fiber** is a fictional internet provider. A customer calls its Sinch number and an AI support agent answers. The agent can walk the customer through simple fixes, like restarting the router. The customer gets to a human in one of two ways:

- **The agent transfers the call.** It calls a `transfer_to_human` tool when the customer asks for a person or when the call needs something it can't do, like a technician visit or a billing dispute.
- **The customer presses 1.** The greeting offers it as a shortcut to a technician, and it works even if the LLM is down.

From the customer's perspective, the handoff works like this:

1. The customer hears "I'm connecting you with a technician." and hold music while Sinch dials the technicians in order.
2. The first technician who answers hears a short summary of the problem written by the AI, and is then connected to the customer.
3. If nobody answers, the customer hears "Sorry, no technician is available right now. Please call us again later." and the call ends.

The full code is in the [sinch-sdk-voice-v2-demo](https://github.com/sinch/sinch-sdk-voice-v2-demo) repository. The snippets in this post are in Python and use the [Sinch Python SDK](https://github.com/sinch/sinch-sdk-python), which has helpers to build every Voice API v2 command. The agent uses Google's [Agent Development Kit (ADK)](https://google.github.io/adk-docs/) with Gemini, but both can be substituted with any framework and model that support tool calling.

## How it works

### One session, named call legs

The whole call is one Voice API v2 session. Every participant in it is a **call leg** with a name. The legs talk to each other through a **bridge**, everyone in `main-bridge` hears everyone else.

| Leg | What it is |
| --- | --- |
| `inbound` | The customer's call to the Sinch number. |
| `ai-agent` | The Voice Relay leg, connected to your server over a WebSocket. |
| `tech-1`, `tech-2`, `tech-3` | The technician mobiles, dialed in this order during a handoff. |

At the start of the call, `main-bridge` holds `inbound` and `ai-agent`. After a successful handoff, it holds `inbound` and one technician.

### Voice Relay: your server only handles text

The `ai-agent` leg is a **Voice Relay** destination. Sinch does the speech-to-text and text-to-speech, and your server exchanges JSON messages with it over a WebSocket: Sinch sends `connect` when the call starts, `text` with each transcript and `dtmf` when the caller presses a key; your server answers with `text` messages that Sinch speaks to the caller. You don't have to deal with audio streams.

### The handoff is one PATCH request

Voice API v2 controls a call with SVAML commands. You send them as a sequence, and Sinch runs them in order: play a message, play music, dial a number, bridge two legs, hang up. Some commands, like `dial`, also have **event handlers** (`onAnswer`, `onTimeout`, `onHangup`). Each handler holds its own sequence of commands, which Sinch runs only if that event happens. Nest handlers inside handlers and you get a decision tree.

For the transfer, your server sends that whole decision tree to the inbound leg in one PATCH request.


## You'll need

- Python 3.11 or later.
- A [Sinch](https://dashboard.sinch.com/) project with an access key (Dashboard → Settings → Access Keys).
- A Voice API v2 service with a phone number assigned to it. The setup script **overwrites that service's call behavior**.
- A [Gemini API key](https://aistudio.google.com/apikey) from Google AI Studio.
- One to three mobile phones for the technicians, plus a phone to call from that isn't one of them.
- [ngrok](https://ngrok.com/), or any tunnel that supports WebSockets.

## Set up the project

Clone the repository and install the dependencies:

```bash
git clone https://github.com/sinch/sinch-sdk-voice-v2-demo.git
cd sinch-sdk-voice-v2-demo/python
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
cp .env.example .env
```

Fill in `.env`:

| Variable | Purpose |
| --- | --- |
| `SINCH_PROJECT_ID`, `SINCH_KEY_ID`, `SINCH_KEY_SECRET` | SDK credentials. |
| `SINCH_SERVICE_ID` | The service that answers the number. |
| `SINCH_NUMBER` | The number assigned to that service. |
| `TECHNICIAN_NUMBERS` | Three technician mobiles in dialing order, E.164, comma-separated. Repeat a number if you have fewer. |
| `HOLD_MUSIC_URL` | A public URL of an MP3 to play while technicians ring. |
| `LLM_API_KEY` | Your Gemini API key. |
| `RELAY_PUBLIC_URL` | Your server's public WebSocket URL. You set it in the next step. |

Start a tunnel in a separate terminal:

```bash
ngrok http 8000
```

Copy the `https://<host>` forwarding URL and set it in `.env` as a WebSocket URL:

```
RELAY_PUBLIC_URL=wss://<host>/relay
```

## Connect the number to your agent

The service's call behavior decides what happens when someone calls the number. This demo uses a STATIC call behavior, because every call starts the same way: answer the customer, put them in main-bridge, and dial the Voice Relay leg into the same bridge.

The SDK's SVAML helpers build the sequence:

<!-- snippet: service-call-behavior -->
```python
client.voice.v2.services.update(
    config.sinch_service_id,
    call_behavior=CallBehavior.static(
        commands=CommandsSequenceCreator()
        .answer()
        .bridge_call(MAIN_BRIDGE)
        .dial(
            Destination.voice_relay(
                config.relay_public_url, config.tts_voice, config.stt_language
            ),
            name=AI_AGENT,
            on_answer=CommandsSequenceCreator().bridge_call(MAIN_BRIDGE),
        ),
        name=INBOUND,
        # When the caller hangs up, hang up the AI leg and every technician leg.
        on_hangup=CommandsSequenceCreator()
        .hangup(AI_AGENT)
        .hangup("tech-1")
        .hangup("tech-2")
        .hangup("tech-3"),
    ),
)
```

- `name=INBOUND` names the customer's leg `inbound`.
- The `on_hangup` cleans up every other leg when the customer hangs up, including technicians who are still ringing.

## Talk to the caller over Voice Relay

Your server exposes one WebSocket endpoint, `/relay`. When a call starts, Sinch sends `connect` with the `callId` of the `ai-agent` leg. The server keeps that ID, answers, and greets the caller. After that, each transcript goes to the agent, and each reply goes back as text.

This is the core of the message loop:

<!-- snippet: relay-loop -->
```python
while True:
    message = RelayMessages.parse(await websocket.receive_text())
    command = message["command"]

    if command == RelayMessages.CONNECT:
        session = CallSession(message["callId"], SupportAgent(config))
        await send(websocket, RelayMessages.answer())
        await send(websocket, RelayMessages.text(config.greeting))
    elif session.handoff_started:
        continue
    elif command == RelayMessages.TEXT and not message.get("isCorrection"):
        await handle_transcript(websocket, session, config, client, message["text"])
    elif command == RelayMessages.DTMF and "1" in message.get("sequence", ""):
        await request_technician(websocket, session, config, client)
```

Each transcript goes to the agent, except corrections: Sinch can send a corrected version of a transcript it already sent, and the demo skips those so the agent doesn't answer the same sentence twice. A `dtmf` message with a 1 starts the transfer without asking the agent.

## Let the agent decide: the transfer tool

The agent has a single tool, `transfer_to_human`. The system prompt tells it when to use it and which `reason` to pass:

| When | `reason` |
| --- | --- |
| The customer asks for a person. | `customer_request` |
| Cancellation, billing dispute, technician visit or ownership change. | `out_of_scope` |
| The problem needs a technician, such as no signal at the fiber terminal. | `needs_technician` |
| The conversation makes no progress. | `stuck` |

The tool is a plain Python function. ADK builds the tool declaration from its signature and docstring. The tool doesn't transfer anything. It only records the request:

<!-- snippet: transfer-tool -->
```python
def transfer_to_human(reason: TransferReason, summary: str) -> str:
    """Transfer the call to a human technician.

    Args:
        reason: Why the call is transferred.
        summary: Two or three sentences for the technician: account number if known,
            the problem, and what was already tried.
    """
    self.transfer_request = TransferRequest(reason, summary)
    return "Transfer requested."
```

The server checks whether the agent called the tool and, if it did, starts the transfer:

<!-- snippet: handle-transcript -->
```python
reply = await agent.reply(text)
if agent.transfer_request is not None:
    request = agent.transfer_request
    await start_handoff(websocket, session, config, client, request.reason, request.summary)
    return
await send(websocket, RelayMessages.text(reply or APOLOGY))
```

The `summary` is what the technician hears, so the prompt asks for two or three sentences that can be spoken in under 15 seconds.


## Build the handoff

This is the command sequence the server sends to the `inbound` leg. Only the first technician's `dial` is shown:

<!-- snippet: handoff-commands -->
```python
whisper = "Acme Fiber transfer. " + summary

handoff = (
    CommandsSequenceCreator()
    .messages(name=HOLD_MUSIC)
    .text("I'm connecting you with a technician.", voice)
    .play(config.hold_music_url)
    .dial(
        Destination.phone(config.technician_numbers[0]),
        from_=Destination.phone(config.sinch_number),
        name="tech-1",
        timeout_duration_seconds=15,
        on_answer=CommandsSequenceCreator().text(
            whisper,
            voice,
            # Stop the music, join the customer and drop the AI.
            on_finish=CommandsSequenceCreator()
            .stop_messages(HOLD_MUSIC)
            .bridge_call(MAIN_BRIDGE)
            .hangup(AI_AGENT),
        ),
        on_timeout=...,  # The same dial for tech-2, which nests tech-3.
        on_hangup=CommandsSequenceCreator().hangup(INBOUND),
    )
)
```

Step by step:

1. **Hold music.** The transfer announcement and the hold music go in a named group of messages (`HOLD_MUSIC`), so a later command can stop them.
2. **Dial.** While they play, `dial` calls the first technician from your Sinch number and waits up to 15 seconds.
3. **`onAnswer`.** If the technician answers, they hear the summary first (the "whisper"). When it finishes, the music stops, the technician is bridged with the customer and the AI leg hangs up.
4. **`onTimeout`.** If nobody answers, the same `dial` runs for the next technician.
5. **`onHangup`.** If the technician hangs up later, the customer's call ends too.

The whisper plays only on the technician's leg. The customer keeps hearing the hold music until the bridge, and the technician knows what the call is about.

## Send the handoff

The server knows the `callId` of the `ai-agent` leg from the `connect` message, but the handoff goes to the `inbound` leg. Both are in the same session, so the server looks up the session and addresses the customer's leg by its name:

<!-- snippet: send-handoff -->
```python
session_id = client.voice.v2.calls.get(ai_agent_call_id).session_id
client.voice.v2.calls.interact_by_call_name(session_id, INBOUND, handoff)
```

Before sending it, the server sends `interrupt` on the WebSocket to cut off anything the AI is still saying:

<!-- snippet: start-handoff -->
```python
async def start_handoff(websocket, session, config, client, reason, summary):
    session.handoff_started = True
    await send(websocket, RelayMessages.interrupt())  # Stop any AI speech.
    await asyncio.to_thread(handoff.run_handoff, client, config, session.call_id, reason, summary)
```

## Try it

Start the server:

```bash
.venv/bin/uvicorn --factory app:create_app --port 8000
```

In another terminal, set the service's call behavior. Run it again whenever your ngrok URL changes:

```bash
.venv/bin/python setup_service.py
```

```
Service <id> updated: STATIC call behavior dialing wss://<host>/relay
```

Call your Sinch number from a phone that isn't in `TECHNICIAN_NUMBERS`. Things to try:

- Say your internet is down and the light on the fiber terminal is red. The agent should ask for your account number and then transfer you with `needs_technician`.
- Ask to cancel your contract. That's `out_of_scope`.
- Press 1. You're transferred right away.
- Let the technician phones ring. You hear the "no technician" message.


## Production considerations

- **Hold music doesn't loop.** Make the file last longer than the worst case: three dial timeouts plus the whisper, about 70 seconds in this demo.
- **The technician list is fixed in the command.** A real contact center would pick technicians by availability or skill.
- **Tools for real data.** The agent only has the transfer tool. More tools could be added to improve the agent context of the customer like `lookup_account` or `get_ticket_status`.
- **Region.** The SDK client in `config.py` uses the North America voice region. Change it if your calls live in another one.


## What's next

- Choose technicians dynamically, for example from an on-call schedule, before building the dial chain.
- Give the agent real tools, like an account lookup, so it solves more calls without a human.
- Record and transcribe the call with Voice API v2, and store the transcript with the handoff summary in your ticketing system.


## Additional resources

- [Connect AI Agents to Phone Calls with Sinch Voice API v2](https://dev.to/gunnargrosch/connect-ai-agents-to-phone-calls-with-sinch-voice-api-v2-4o1p)
- [Demo repository](https://github.com/sinch/sinch-sdk-voice-v2-demo)
- [Sinch Python SDK](https://github.com/sinch/sinch-sdk-python)
- [Google Agent Development Kit (ADK)](https://google.github.io/adk-docs/)
