package com.sinch.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.sinch.sdk.SinchClient;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Voice Relay WebSocket server (/relay) with the AI agent and the handoff, plus GET /health.
 *
 * <ol>
 *   <li>Sinch sends connect: the server answers and greets the customer.
 *   <li>Each transcript goes to the agent, and its reply goes back as text.
 *   <li>On transfer_to_human or DTMF 1, the server PATCHes the customer's leg and stays silent.
 * </ol>
 *
 * <p>Run: mvn -q compile exec:java
 */
public final class App {

  private static final Logger LOG = LoggerFactory.getLogger("relay");
  // Raw Voice Relay messages are cut to this length in the log.
  private static final int MAX_LOGGED_MESSAGE_LENGTH = 200;

  private static final String APOLOGY =
      "Sorry, I'm having trouble right now. Could you say that again?";

  // Whisper for DTMF 1 when there is no conversation to summarize.
  private static final String KEYPAD_SUMMARY = "The customer pressed 1 to talk to a technician.";
  private static final String TECHNICIAN_KEY = "1";

  // WebSocket close code for an unexpected server error.
  private static final int INTERNAL_ERROR = 1011;
  // The WebSocket stays open and silent while technicians ring.
  private static final Duration WEBSOCKET_IDLE_TIMEOUT = Duration.ofMinutes(10);

  /** State of one call. */
  static final class CallSession {
    final String callId;
    final SupportAgent agent;
    boolean handoffStarted = false;

    CallSession(String callId, SupportAgent agent) {
      this.callId = callId;
      this.agent = agent;
    }
  }

  private final Config config;
  private final SinchClient client;
  // Call state by WebSocket session id.
  private final Map<String, CallSession> sessions = new ConcurrentHashMap<>();

  App(Config config) {
    this.config = config;
    this.client = config.createSinchClient();
  }

  static String preview(String raw) {
    return raw.length() <= MAX_LOGGED_MESSAGE_LENGTH
        ? raw
        : raw.substring(0, MAX_LOGGED_MESSAGE_LENGTH) + "…";
  }

  static void send(WsContext websocket, Map<String, Object> message) throws Exception {
    String raw = RelayMessages.serialize(message);
    LOG.info("  WS >> {}", preview(raw));
    websocket.send(raw);
  }

  /** From here on Sinch runs the call (see Handoff.runHandoff). */
  void startHandoff(WsContext websocket, CallSession session, String reason, String summary)
      throws Exception {
    session.handoffStarted = true;
    LOG.info("    handoff  reason={}  summary=\"{}\"", reason, summary);
    // Stop any AI speech.
    send(websocket, RelayMessages.interrupt());
    try {
      Handoff.runHandoff(client, config, session.callId, reason, summary);
      LOG.info("    PATCH handoff → sent");
    } catch (Exception exception) {
      LOG.warn("    [!] PATCH handoff failed: {}", exception.toString());
    }
  }

  /**
   * DTMF 1: hand off without asking the LLM. The LLM only writes the whisper, if there was a
   * conversation.
   */
  void requestTechnician(WsContext websocket, CallSession session) throws Exception {
    String summary = KEYPAD_SUMMARY;
    if (session.agent.hasConversation()) {
      try {
        String written = session.agent.summarize();
        summary = written.isEmpty() ? KEYPAD_SUMMARY : written;
      } catch (Exception exception) {
        LOG.warn("    [!] summary error, using the fixed one: {}", exception.toString());
      }
    }
    startHandoff(
        websocket, session, SupportAgent.TransferReason.CUSTOMER_REQUEST.toString(), summary);
  }

  void handleTranscript(WsContext websocket, CallSession session, String text) throws Exception {
    SupportAgent agent = session.agent;
    String reply;
    try {
      reply = agent.reply(text);
      LOG.info("    LLM ← \"{}\"", reply);
      SupportAgent.TransferRequest request = agent.transferRequest();
      if (request != null) {
        startHandoff(websocket, session, request.reason().toString(), request.summary());
        return;
      }
    } catch (Exception exception) {
      LOG.warn("    [!] LLM error: {}", exception.toString());
      if (!session.handoffStarted) {
        send(websocket, RelayMessages.text(APOLOGY));
      }
      return;
    }

    send(websocket, RelayMessages.text(reply.isEmpty() ? APOLOGY : reply));
  }

  void handleMessage(WsMessageContext websocket) throws Exception {
    String raw = websocket.message();
    LOG.info("WS << {}", preview(raw));
    JsonNode message = RelayMessages.parse(raw);
    String command = message.get("command").asText();
    if (command.equals(RelayMessages.CONNECT)) {
      CallSession session =
          new CallSession(message.path("callId").asText(), new SupportAgent(config));
      sessions.put(websocket.sessionId(), session);
      LOG.info(
          "    connect  callId={}  serviceId={}",
          session.callId,
          message.path("serviceId").asText());
      send(websocket, RelayMessages.answer());
      send(websocket, RelayMessages.text(config.greeting()));
      return;
    }
    CallSession session = sessions.get(websocket.sessionId());
    if (session == null) {
      throw new IllegalStateException("Voice Relay message before connect: " + command);
    }
    if (command.equals(RelayMessages.TEXT)
        && !message.path("isCorrection").asBoolean(false)
        && !session.handoffStarted) {
      LOG.info("    STT → \"{}\"", message.path("text").asText());
      handleTranscript(websocket, session, message.path("text").asText(""));
    } else if (command.equals(RelayMessages.DTMF)
        && message.path("sequence").asText("").contains(TECHNICIAN_KEY)
        && !session.handoffStarted) {
      LOG.info("    DTMF {} → technician requested", message.path("sequence").asText());
      requestTechnician(websocket, session);
    } else if (session.handoffStarted) {
      LOG.info("    ignored: handoff started");
    }
  }

  Javalin create() {
    return Javalin.create(
        javalin -> {
          javalin.startup.showJavalinBanner = false;
          javalin.jetty.modifyWebSocketServletFactory(
              factory -> factory.setIdleTimeout(WEBSOCKET_IDLE_TIMEOUT));
          javalin.routes.get("/health", ctx -> ctx.json(Map.of("status", "ok")));
          // Jetty delivers the messages of one WebSocket one at a time, in order.
          javalin.routes.ws(
              "/relay",
              ws -> {
                ws.onMessage(
                    websocket -> {
                      try {
                        handleMessage(websocket);
                      } catch (Exception exception) {
                        LOG.warn("    [!] {}", exception.toString());
                        websocket.closeSession(INTERNAL_ERROR, "Server error");
                      }
                    });
                ws.onClose(
                    websocket -> {
                      CallSession session = sessions.remove(websocket.sessionId());
                      LOG.info(
                          "    websocket closed  callId={}  code={}",
                          session != null ? session.callId : "-",
                          websocket.status());
                    });
              });
        });
  }

  public static void main(String[] args) {
    Config config = Config.load();
    new App(config).create().start(config.port());
  }
}
