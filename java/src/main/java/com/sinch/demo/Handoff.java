package com.sinch.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sinch.sdk.SinchClient;
import com.sinch.sdk.domains.voice.models.v2.calls.request.CallPatchRequest;
import com.sinch.sdk.domains.voice.models.v2.destination.Phone;
import com.sinch.sdk.domains.voice.models.v2.helpers.CommandsSequenceCreator;
import com.sinch.sdk.domains.voice.models.v2.helpers.Destination;
import com.sinch.sdk.domains.voice.models.v2.svaml.SvamlCommand;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds the handoff SVAML with the SDK helpers, sends it to the inbound leg and writes the
 * handoff log.
 */
public final class Handoff {

  // Call names shared with SetupService.
  public static final String INBOUND = "inbound";
  public static final String AI_AGENT = "ai-agent";
  public static final String MAIN_BRIDGE = "main-bridge";
  // Dialing order. Same size as Config.TECHNICIANS.
  public static final List<String> TECHNICIAN_CALL_NAMES = List.of("tech-1", "tech-2", "tech-3");

  private static final String WHISPER_PREFIX = "Acme Fiber transfer. ";
  // messagesName of the transfer notice and hold music, stopped when a technician answers.
  private static final String HOLD_MUSIC = "hold-music";
  private static final String TRANSFER_NOTICE = "I'm connecting you with a technician.";
  private static final String NO_TECHNICIAN_TEXT =
      "Sorry, no technician is available right now. Please call us again later.";
  private static final String HANDOFF_FAILED_TEXT =
      "Sorry, I couldn't reach a technician. Please call us again later.";
  // A TTS message accepts at most 600 characters.
  private static final int MAX_SAY_LENGTH = 600;
  private static final Path HANDOFF_LOG_PATH = Path.of("handoffs.jsonl");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Handoff() {}

  /**
   * Inbound leg: transfer notice and hold music, then dial tech-1, tech-2, tech-3 in turn. The
   * first to answer hears the whisper and joins the customer; if nobody answers,
   * NO_TECHNICIAN_TEXT and hang up.
   */
  public static List<SvamlCommand> buildHandoffCommands(Config config, String summary) {
    String voice = config.ttsVoice();
    String whisper = WHISPER_PREFIX + summary;
    String whisperText = whisper.substring(0, Math.min(whisper.length(), MAX_SAY_LENGTH));
    Phone from = Destination.phone(config.sinchNumber());
    List<String> technicians = config.technicianNumbers();
    // Whisper to the technician, then stop the music, join the customer and drop the AI.
    Consumer<CommandsSequenceCreator> whisperAndBridge =
        sequence ->
            sequence.text(
                whisperText,
                voice,
                whisperMessage ->
                    whisperMessage.onFinish(
                        onFinish ->
                            onFinish.stopMessages(HOLD_MUSIC).bridgeCall(MAIN_BRIDGE).hangup(AI_AGENT)));
    Consumer<CommandsSequenceCreator> hangupCaller = sequence -> sequence.hangup(INBOUND);

    Consumer<CommandsSequenceCreator> noTechnicianAvailable =
        sequence ->
            sequence
                .stopMessages(HOLD_MUSIC)
                .text(
                    NO_TECHNICIAN_TEXT,
                    voice,
                    apology -> apology.onFinish(hangup -> hangup.hangup(AI_AGENT).hangup(INBOUND)));
    Consumer<CommandsSequenceCreator> dialTech3 =
        sequence ->
            sequence.dial(
                Destination.phone(technicians.get(2)),
                tech3 ->
                    tech3
                        .from(from)
                        .name(TECHNICIAN_CALL_NAMES.get(2))
                        .timeoutDurationSeconds(config.dialTimeoutSeconds())
                        .onAnswer(whisperAndBridge)
                        .onTimeout(noTechnicianAvailable)
                        .onHangup(hangupCaller));
    Consumer<CommandsSequenceCreator> dialTech2 =
        sequence ->
            sequence.dial(
                Destination.phone(technicians.get(1)),
                tech2 ->
                    tech2
                        .from(from)
                        .name(TECHNICIAN_CALL_NAMES.get(1))
                        .timeoutDurationSeconds(config.dialTimeoutSeconds())
                        .onAnswer(whisperAndBridge)
                        .onTimeout(dialTech3)
                        .onHangup(hangupCaller));

    return CommandsSequenceCreator.create()
        .messages(
            messages ->
                messages.name(HOLD_MUSIC).text(TRANSFER_NOTICE, voice).play(config.holdMusicUrl()))
        .dial(
            Destination.phone(technicians.get(0)),
            tech1 ->
                tech1
                    .from(from)
                    .name(TECHNICIAN_CALL_NAMES.get(0))
                    .timeoutDurationSeconds(config.dialTimeoutSeconds())
                    .onAnswer(whisperAndBridge)
                    .onTimeout(dialTech2)
                    .onHangup(hangupCaller))
        .build();
  }

  /** Inbound leg: apologize, then hang up the AI leg and the caller. */
  public static List<SvamlCommand> buildHandoffFailedCommands(Config config) {
    return CommandsSequenceCreator.create()
        .text(
            HANDOFF_FAILED_TEXT,
            config.ttsVoice(),
            apology -> apology.onFinish(hangup -> hangup.hangup(AI_AGENT).hangup(INBOUND)))
        .build();
  }

  /** PATCH the handoff. If it fails, PATCH the apology and hang-up, then rethrow. */
  public static void runHandoff(
      SinchClient client, Config config, String aiAgentCallId, String reason, String summary)
      throws IOException {
    writeHandoffLog(aiAgentCallId, reason, summary);
    try {
      sendHandoff(client, aiAgentCallId, buildHandoffCommands(config, summary));
    } catch (RuntimeException exception) {
      sendHandoff(client, aiAgentCallId, buildHandoffFailedCommands(config));
      throw exception;
    }
  }

  /** PATCH the inbound leg, found by name in the ai-agent leg's session. */
  public static void sendHandoff(
      SinchClient client, String aiAgentCallId, List<SvamlCommand> commands) {
    String sessionId = client.voice().v2().calls().get(aiAgentCallId).getSessionId();
    client
        .voice()
        .v2()
        .calls()
        .interactByCallName(
            sessionId, INBOUND, CallPatchRequest.builder().setCommands(commands).build());
  }

  public static void writeHandoffLog(String callId, String reason, String summary)
      throws IOException {
    Map<String, String> entry = new LinkedHashMap<>();
    entry.put("timestamp", Instant.now().toString());
    entry.put("callId", callId);
    entry.put("reason", reason);
    entry.put("summary", summary);
    Files.writeString(
        HANDOFF_LOG_PATH,
        MAPPER.writeValueAsString(entry) + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }
}
