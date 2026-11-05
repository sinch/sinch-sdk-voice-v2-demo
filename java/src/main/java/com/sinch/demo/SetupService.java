package com.sinch.demo;

import static com.sinch.demo.Handoff.AI_AGENT;
import static com.sinch.demo.Handoff.INBOUND;
import static com.sinch.demo.Handoff.MAIN_BRIDGE;
import static com.sinch.demo.Handoff.TECHNICIAN_CALL_NAMES;

import com.sinch.sdk.SinchClient;
import com.sinch.sdk.domains.voice.models.v2.helpers.CallBehaviorHelper;
import com.sinch.sdk.domains.voice.models.v2.helpers.CommandsSequenceCreator;
import com.sinch.sdk.domains.voice.models.v2.helpers.Destination;
import com.sinch.sdk.domains.voice.models.v2.services.request.UpdateServiceRequest;
import java.util.function.Consumer;

/**
 * Sets the service STATIC call behavior: answer the caller, bridge it and dial the Voice Relay
 * leg.
 */
public final class SetupService {

  private SetupService() {}

  public static void main(String[] args) {
    Config config = Config.load();
    SinchClient client = config.createSinchClient();

    Consumer<CommandsSequenceCreator> bridgeAgent = onAnswer -> onAnswer.bridgeCall(MAIN_BRIDGE);

    Consumer<CommandsSequenceCreator> answerAndDialAgent =
        commands ->
            commands
                .answer()
                .bridgeCall(MAIN_BRIDGE)
                .dial(
                    Destination.voiceRelay(
                        config.relayPublicUrl(), config.ttsVoice(), config.sttLanguage()),
                    dial -> dial.name(AI_AGENT).onAnswer(bridgeAgent));

    // When the caller hangs up, hang up the AI leg and every technician leg.
    Consumer<CommandsSequenceCreator> hangupLegs =
        onHangup -> {
          onHangup.hangup(AI_AGENT);
          TECHNICIAN_CALL_NAMES.forEach(onHangup::hangup);
        };

    client
        .voice()
        .v2()
        .services()
        .update(
            config.sinchServiceId(),
            UpdateServiceRequest.builder()
                .setCallBehavior(
                    CallBehaviorHelper.staticBehavior(answerAndDialAgent)
                        .name(INBOUND)
                        .onHangup(hangupLegs)
                        .build())
                .build());
    System.out.println(
        "Service "
            + config.sinchServiceId()
            + " updated: STATIC call behavior dialing "
            + config.relayPublicUrl());
  }
}
