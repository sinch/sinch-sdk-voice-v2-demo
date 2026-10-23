"""Sets the service STATIC call behavior: answer the caller, bridge it and dial the Voice Relay leg."""

import sys

from config import Config, create_sinch_client, load_config
from handoff import AI_AGENT, INBOUND, MAIN_BRIDGE, TECHNICIAN_CALL_NAMES
from sinch import SinchClient
from sinch.domains.voice.helpers.v2.destinations import Destination
from sinch.domains.voice.helpers.v2.services import CallBehavior
from sinch.domains.voice.helpers.v2.svaml import CommandsSequenceCreator


def main() -> int:
    config: Config = load_config()
    client: SinchClient = create_sinch_client(config)

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
            .hangup(TECHNICIAN_CALL_NAMES[0])
            .hangup(TECHNICIAN_CALL_NAMES[1])
            .hangup(TECHNICIAN_CALL_NAMES[2]),
        ),
    )
    print(
        f"Service {config.sinch_service_id} updated: "
        f"STATIC call behavior dialing {config.relay_public_url}, "
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
