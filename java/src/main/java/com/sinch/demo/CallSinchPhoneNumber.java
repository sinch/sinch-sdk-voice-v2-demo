package com.sinch.demo;

import com.sinch.sdk.SinchClient;
import com.sinch.sdk.domains.voice.models.v2.calls.request.StartCallRequest;
import com.sinch.sdk.domains.voice.models.v2.calls.response.StartCallResponse;
import com.sinch.sdk.domains.voice.models.v2.helpers.CommandsSequenceCreator;
import com.sinch.sdk.domains.voice.models.v2.destination.Phone;
import com.sinch.sdk.domains.voice.models.v2.helpers.Destination;
import java.util.function.Consumer;

/**
 * Test helper: rings your phone from a Sinch number and bridges you with a call to SINCH_NUMBER.
 *
 * <p>For you it is an incoming call; the outbound legs are charged to the Sinch account. Usage:
 * mvn -q compile exec:java -Dexec.mainClass=com.sinch.demo.CallSinchPhoneNumber
 */
public final class CallSinchPhoneNumber {

  private static final String TESTER = "tester";
  private static final String DEMO_LINE = "demo-line";
  private static final String TEST_BRIDGE = "test-bridge";

  private static final int MAX_CALL_DURATION_SECONDS = 60;

  private CallSinchPhoneNumber() {}

  public static void main(String[] args) {
    Config config = Config.load();
    SinchClient client = config.createSinchClient();

    Phone from = Destination.phone(config.testFromNumber());
    Consumer<CommandsSequenceCreator> bridgeTestCall = sequence -> sequence.bridgeCall(TEST_BRIDGE);
    Consumer<CommandsSequenceCreator> hangupTester = sequence -> sequence.hangup(TESTER);
    Consumer<CommandsSequenceCreator> hangupDemoLine = sequence -> sequence.hangup(DEMO_LINE);

    Consumer<CommandsSequenceCreator> dialDemoLine =
        sequence ->
            sequence.dial(
                Destination.phone(config.sinchNumber()),
                demoLine ->
                    demoLine
                        .from(from)
                        .name(DEMO_LINE)
                        .maxDurationSeconds(MAX_CALL_DURATION_SECONDS)
                        .onAnswer(bridgeTestCall)
                        .onHangup(hangupTester));

    Consumer<CommandsSequenceCreator> bridgeAndDialDemoLine =
        sequence -> dialDemoLine.accept(sequence.bridgeCall(TEST_BRIDGE));

    // Your phone and SINCH_NUMBER join the same bridge. Each leg hangs up the other.
    StartCallResponse response =
        client
            .voice()
            .v2()
            .calls()
            .start(
                StartCallRequest.builder()
                    .setCommands(
                        CommandsSequenceCreator.create()
                            .dial(
                                Destination.phone(config.testPhoneNumber()),
                                tester ->
                                    tester
                                        .from(from)
                                        .name(TESTER)
                                        .maxDurationSeconds(MAX_CALL_DURATION_SECONDS)
                                        .onAnswer(bridgeAndDialDemoLine)
                                        .onHangup(hangupDemoLine))
                            .build())
                    .build());
    System.out.println(
        "Test call started: sessionId="
            + response.getSessionId()
            + ". Your phone should ring now.");
  }
}
