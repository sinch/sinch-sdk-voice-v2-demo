"""Test helper: rings your phone from a Sinch number and bridges you with a call to SINCH_NUMBER.

For you it is an incoming call; the outbound legs are charged to the Sinch account.
Usage: python call_sinch_phone_number.py
"""

import logging
import sys

from config import Config, create_sinch_client, load_config
from sinch import SinchClient
from sinch.domains.voice.helpers.v2.destinations import Destination
from sinch.domains.voice.helpers.v2.svaml import CommandsSequenceCreator
from sinch.domains.voice.models.v2.calls.response.start_call_response import StartCallResponse

TESTER: str = "tester"
DEMO_LINE: str = "demo-line"
TEST_BRIDGE: str = "test-bridge"

MAX_CALL_DURATION_SECONDS: int = 60


def main() -> int:
    config: Config = load_config()

    logging.basicConfig()
    logging.getLogger().setLevel(logging.DEBUG)

    client: SinchClient = create_sinch_client(config)

    # Your phone and SINCH_NUMBER join the same bridge. Each leg hangs up the other.
    response: StartCallResponse = client.voice.v2.calls.start(
        commands=CommandsSequenceCreator().dial(
            Destination.phone(config.test_phone_number),
            from_=Destination.phone(config.test_from_number),
            name=TESTER,
            max_duration_seconds=MAX_CALL_DURATION_SECONDS,
            on_answer=CommandsSequenceCreator()
            .bridge_call(TEST_BRIDGE)
            .dial(
                Destination.phone(config.sinch_number),
                from_=Destination.phone(config.test_from_number),
                name=DEMO_LINE,
                max_duration_seconds=MAX_CALL_DURATION_SECONDS,
                on_answer=CommandsSequenceCreator().bridge_call(TEST_BRIDGE),
                on_hangup=CommandsSequenceCreator().hangup(TESTER),
            ),
            on_hangup=CommandsSequenceCreator().hangup(DEMO_LINE),
        ),
    )
    print(f"Test call started: sessionId={response.session_id}. Your phone should ring now.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
