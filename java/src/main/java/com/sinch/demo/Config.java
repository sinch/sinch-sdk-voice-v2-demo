package com.sinch.demo;

import com.sinch.sdk.SinchClient;
import com.sinch.sdk.domains.voice.models.v2.VoiceRegion;
import com.sinch.sdk.models.Configuration;
import io.github.cdimascio.dotenv.Dotenv;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loads and validates environment variables, and creates the Sinch client. */
public record Config(
    String sinchProjectId,
    String sinchKeyId,
    String sinchKeySecret,
    String sinchServiceId,
    String sinchNumber,
    List<String> technicianNumbers,
    int dialTimeoutSeconds,
    String relayPublicUrl,
    String holdMusicUrl,
    String ttsVoice,
    String sttLanguage,
    String greeting,
    int port,
    String llmModel,
    String llmApiKey,
    String testFromNumber,
    String testPhoneNumber) {

  private static final List<String> REQUIRED =
      List.of(
          "SINCH_PROJECT_ID",
          "SINCH_KEY_ID",
          "SINCH_KEY_SECRET",
          "SINCH_SERVICE_ID",
          "SINCH_NUMBER",
          "TECHNICIAN_NUMBERS",
          "RELAY_PUBLIC_URL",
          "HOLD_MUSIC_URL",
          "LLM_API_KEY");

  // Demo settings
  private static final String TTS_VOICE = "Emma";
  private static final String STT_LANGUAGE = "en-US";
  private static final String GREETING =
      "Welcome to Acme Fiber support. Please describe your problem, or press 1 at any time to talk"
          + " to a technician.";
  private static final int PORT = 8000;
  private static final int DIAL_TIMEOUT_SECONDS = 15;
  private static final String LLM_MODEL = "gemini-3.5-flash-lite";

  // Only needed by CallSinchPhoneNumber.
  private static final List<String> OPTIONAL = List.of("TEST_FROM_NUMBER", "TEST_PHONE_NUMBER");

  // Must match the size of Handoff.TECHNICIAN_CALL_NAMES.
  public static final int TECHNICIANS = 3;

  public static Config load() {
    Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
    Map<String, String> values = new HashMap<>();
    List<String> missing = new ArrayList<>();
    for (String name : REQUIRED) {
      String value = dotenv.get(name, "").strip();
      if (value.isEmpty()) {
        missing.add(name);
      }
      values.put(name, value);
    }
    for (String name : OPTIONAL) {
      values.put(name, dotenv.get(name, "").strip());
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "Missing environment variables: " + String.join(", ", missing));
    }
    String relayPublicUrl = values.get("RELAY_PUBLIC_URL");
    if (!relayPublicUrl.startsWith("wss://") && !relayPublicUrl.startsWith("ws://")) {
      throw new IllegalStateException("RELAY_PUBLIC_URL must start with wss:// or ws://");
    }
    String holdMusicUrl = values.get("HOLD_MUSIC_URL");
    if (!holdMusicUrl.startsWith("https://") && !holdMusicUrl.startsWith("http://")) {
      throw new IllegalStateException("HOLD_MUSIC_URL must start with https:// or http://");
    }
    List<String> technicianNumbers =
        Arrays.stream(values.get("TECHNICIAN_NUMBERS").split(","))
            .map(String::strip)
            .filter(number -> !number.isEmpty())
            .toList();
    if (technicianNumbers.size() != TECHNICIANS) {
      throw new IllegalStateException(
          "TECHNICIAN_NUMBERS needs exactly "
              + TECHNICIANS
              + " numbers (repeat a number if you have fewer)");
    }
    return new Config(
        values.get("SINCH_PROJECT_ID"),
        values.get("SINCH_KEY_ID"),
        values.get("SINCH_KEY_SECRET"),
        values.get("SINCH_SERVICE_ID"),
        values.get("SINCH_NUMBER"),
        technicianNumbers,
        DIAL_TIMEOUT_SECONDS,
        relayPublicUrl,
        holdMusicUrl,
        TTS_VOICE,
        STT_LANGUAGE,
        GREETING,
        PORT,
        LLM_MODEL,
        values.get("LLM_API_KEY"),
        values.get("TEST_FROM_NUMBER"),
        values.get("TEST_PHONE_NUMBER"));
  }

  public SinchClient createSinchClient() {
    return new SinchClient(
        Configuration.builder()
            .setProjectId(sinchProjectId)
            .setKeyId(sinchKeyId)
            .setKeySecret(sinchKeySecret)
            .setVoiceV2Region(VoiceRegion.NORTH_AMERICA)
            .build());
  }
}
