"""Builds the handoff SVAML with the SDK helpers, sends it to the inbound leg and writes the handoff log."""

import json
from datetime import datetime, timezone

from config import Config
from sinch import SinchClient
from sinch.domains.voice.helpers.v2.destinations import Destination
from sinch.domains.voice.helpers.v2.svaml import CommandsSequenceCreator
from sinch.domains.voice.models.v2.types import VoiceNameEnum

# Call names shared with setup_service.py.
INBOUND: str = "inbound"
AI_AGENT: str = "ai-agent"
MAIN_BRIDGE: str = "main-bridge"
# Dialing order. Same length as config.TECHNICIANS.
TECHNICIAN_CALL_NAMES: list[str] = ["tech-1", "tech-2", "tech-3"]

WHISPER_PREFIX: str = "Acme Fiber transfer. "
# messagesName of the transfer notice and hold music, stopped when a technician answers.
HOLD_MUSIC: str = "hold-music"
TRANSFER_NOTICE: str = "I'm connecting you with a technician."
NO_TECHNICIAN_TEXT: str = "Sorry, no technician is available right now. Please call us again later."
HANDOFF_FAILED_TEXT: str = "Sorry, I couldn't reach a technician. Please call us again later."
# A TTS message accepts at most 600 characters.
MAX_SAY_LENGTH: int = 600
HANDOFF_LOG_PATH: str = "handoffs.jsonl"


def build_handoff_commands(config: Config, summary: str) -> CommandsSequenceCreator:
    """Inbound leg: transfer notice and hold music, then dial tech-1, tech-2, tech-3 in turn.
    The first to answer hears the whisper and joins the customer; if nobody answers, NO_TECHNICIAN_TEXT and hang up.
    """
    voice: VoiceNameEnum = VoiceNameEnum(config.tts_voice)
    whisper_text: str = (WHISPER_PREFIX + summary)[:MAX_SAY_LENGTH]
    notice_and_music: CommandsSequenceCreator = (
        CommandsSequenceCreator().messages(name=HOLD_MUSIC).text(TRANSFER_NOTICE, voice).play(config.hold_music_url)
    )
    return notice_and_music.dial(
        Destination.phone(config.technician_numbers[0]),
        from_=Destination.phone(config.sinch_number),
        name=TECHNICIAN_CALL_NAMES[0],
        timeout_duration_seconds=config.dial_timeout_seconds,
        on_answer=CommandsSequenceCreator().text(
            whisper_text,
            voice,
            # Stop the music, join the customer and drop the AI.
            on_finish=CommandsSequenceCreator()
            .stop_messages(HOLD_MUSIC)
            .bridge_call(MAIN_BRIDGE)
            .hangup(AI_AGENT),
        ),
        on_timeout=CommandsSequenceCreator().dial(
            Destination.phone(config.technician_numbers[1]),
            from_=Destination.phone(config.sinch_number),
            name=TECHNICIAN_CALL_NAMES[1],
            timeout_duration_seconds=config.dial_timeout_seconds,
            on_answer=CommandsSequenceCreator().text(
                whisper_text,
                voice,
                # Stop the music, join the customer and drop the AI.
                on_finish=CommandsSequenceCreator()
                .stop_messages(HOLD_MUSIC)
                .bridge_call(MAIN_BRIDGE)
                .hangup(AI_AGENT),
            ),
            on_timeout=CommandsSequenceCreator().dial(
                Destination.phone(config.technician_numbers[2]),
                from_=Destination.phone(config.sinch_number),
                name=TECHNICIAN_CALL_NAMES[2],
                timeout_duration_seconds=config.dial_timeout_seconds,
                on_answer=CommandsSequenceCreator().text(
                    whisper_text,
                    voice,
                    # Stop the music, join the customer and drop the AI.
                    on_finish=CommandsSequenceCreator()
                    .stop_messages(HOLD_MUSIC)
                    .bridge_call(MAIN_BRIDGE)
                    .hangup(AI_AGENT),
                ),
                on_timeout=CommandsSequenceCreator().stop_messages(HOLD_MUSIC).text(
                    NO_TECHNICIAN_TEXT,
                    voice,
                    on_finish=CommandsSequenceCreator().hangup(AI_AGENT).hangup(INBOUND),
                ),
                on_hangup=CommandsSequenceCreator().hangup(INBOUND),
            ),
            on_hangup=CommandsSequenceCreator().hangup(INBOUND),
        ),
        on_hangup=CommandsSequenceCreator().hangup(INBOUND),
    )


def build_handoff_failed_commands(config: Config) -> CommandsSequenceCreator:
    """Inbound leg: apologize, then hang up the AI leg and the caller."""
    return CommandsSequenceCreator().text(
        HANDOFF_FAILED_TEXT,
        VoiceNameEnum(config.tts_voice),
        on_finish=CommandsSequenceCreator().hangup(AI_AGENT).hangup(INBOUND),
    )


def run_handoff(client: SinchClient, config: Config, ai_agent_call_id: str, reason: str, summary: str) -> None:
    """PATCH the handoff. If it fails, PATCH the apology and hang-up, then raise."""
    write_handoff_log(ai_agent_call_id, reason, summary)
    try:
        send_handoff(client, ai_agent_call_id, build_handoff_commands(config, summary))
    except Exception:
        send_handoff(client, ai_agent_call_id, build_handoff_failed_commands(config))
        raise


def send_handoff(client: SinchClient, ai_agent_call_id: str, commands: CommandsSequenceCreator) -> None:
    """PATCH the inbound leg, found by name in the ai-agent leg's session."""
    session_id: str = client.voice.v2.calls.get(ai_agent_call_id).session_id
    client.voice.v2.calls.interact_by_call_name(session_id, INBOUND, commands)


def write_handoff_log(call_id: str, reason: str, summary: str) -> None:
    entry: dict[str, str] = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "callId": call_id,
        "reason": reason,
        "summary": summary,
    }
    with open(HANDOFF_LOG_PATH, "a", encoding="utf-8") as log_file:
        log_file.write(json.dumps(entry) + "\n")
