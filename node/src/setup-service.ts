/** Sets the service STATIC call behavior: answer the caller, bridge it and dial the Voice Relay leg. */

import { Voice } from '@sinch/sdk-core';
import { createSinchClient, loadConfig } from './config.ts';
import { AI_AGENT, INBOUND, MAIN_BRIDGE, TECHNICIAN_CALL_NAMES } from './handoff.ts';

async function main(): Promise<void> {
  const config = loadConfig();
  const client = createSinchClient(config);

  await client.voice.v2.services.update({
    serviceId: config.sinchServiceId,
    updateServiceRequestBody: {
      callBehavior: Voice.v2.CallBehaviorHelper.static({
        commands: new Voice.v2.CommandsSequenceCreator()
          .answer()
          .bridgeCall(MAIN_BRIDGE)
          .dial((dial) =>
            dial
              .to(
                Voice.v2.Destination.voiceRelay({
                  endpoint: config.relayPublicUrl,
                  ttsVoice: config.ttsVoice,
                  sttLanguage: config.sttLanguage,
                }),
              )
              .name(AI_AGENT)
              .onAnswer((onAnswer) => onAnswer.bridgeCall(MAIN_BRIDGE)),
          )
          .build(),
        name: INBOUND,
        // When the caller hangs up, hang up the AI leg and every technician leg.
        onHangup: new Voice.v2.CommandsSequenceCreator()
          .hangup({ name: AI_AGENT })
          .hangup({ name: TECHNICIAN_CALL_NAMES[0] })
          .hangup({ name: TECHNICIAN_CALL_NAMES[1] })
          .hangup({ name: TECHNICIAN_CALL_NAMES[2] })
          .build(),
      }),
    },
  });
  console.log(`Service ${config.sinchServiceId} updated: STATIC call behavior dialing ${config.relayPublicUrl}`);
}

await main();
