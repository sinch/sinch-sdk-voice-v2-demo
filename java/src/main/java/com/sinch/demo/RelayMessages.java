package com.sinch.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/** Helpers to parse and build Voice Relay WebSocket messages. */
public final class RelayMessages {

  public static final String CONNECT = "connect";
  public static final String TEXT = "text";
  public static final String DTMF = "dtmf";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private RelayMessages() {}

  public static JsonNode parse(String raw) throws JsonProcessingException {
    JsonNode message = MAPPER.readTree(raw);
    if (!message.isObject() || !message.path("command").isTextual()) {
      throw new IllegalArgumentException("Voice Relay message without a command");
    }
    return message;
  }

  public static String serialize(Map<String, Object> message) throws JsonProcessingException {
    return MAPPER.writeValueAsString(message);
  }

  public static Map<String, Object> answer() {
    return Map.of("command", "answer");
  }

  public static Map<String, Object> interrupt() {
    return Map.of("command", "interrupt");
  }

  public static Map<String, Object> text(String text) {
    return text(text, true, null);
  }

  public static Map<String, Object> text(String text, boolean isLast, Boolean isInterruptible) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("command", TEXT);
    message.put("text", text);
    message.put("isLast", isLast);
    if (isInterruptible != null) {
      message.put("isInterruptible", isInterruptible);
    }
    return message;
  }
}
