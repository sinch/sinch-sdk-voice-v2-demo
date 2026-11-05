/**
 * Test helper: rings your phone from a Sinch number and bridges you with a call to SINCH_NUMBER.
 *
 * For you it is an incoming call; the outbound legs are charged to the Sinch account.
 * Usage: npm run call-sinch-phone-number
 */

import { Voice } from '@sinch/sdk-core';
import { createSinchClient, loadConfig } from './config.ts';

const TESTER = 'tester';
const DEMO_LINE = 'demo-line';
const TEST_BRIDGE = 'test-bridge';

const MAX_CALL_DURATION_SECONDS = 60;

async function main(): Promise<void> {
  const config = loadConfig();
  const client = createSinchClient(config);

  // Your phone and SINCH_NUMBER join the same bridge. Each leg hangs up the other.
  const response = await client.voice.v2.calls.start({
    createCallRequestBody: {
      commands: new Voice.v2.CommandsSequenceCreator()
        .dial((tester) =>
          tester
            .to(Voice.v2.Destination.phone(config.testPhoneNumber))
            .from(Voice.v2.Destination.phone(config.testFromNumber))
            .name(TESTER)
            .maxDurationSeconds(MAX_CALL_DURATION_SECONDS)
            .onAnswer((onAnswer) =>
              onAnswer.bridgeCall(TEST_BRIDGE).dial((demoLine) =>
                demoLine
                  .to(Voice.v2.Destination.phone(config.sinchNumber))
                  .from(Voice.v2.Destination.phone(config.testFromNumber))
                  .name(DEMO_LINE)
                  .maxDurationSeconds(MAX_CALL_DURATION_SECONDS)
                  .onAnswer((demoAnswer) => demoAnswer.bridgeCall(TEST_BRIDGE))
                  .onHangup((demoHangup) => demoHangup.hangup({ name: TESTER })),
              ),
            )
            .onHangup((testerHangup) => testerHangup.hangup({ name: DEMO_LINE })),
        )
        .build(),
    },
  });
  console.log(`Test call started: sessionId=${response.sessionId}. Your phone should ring now.`);
}

await main();
