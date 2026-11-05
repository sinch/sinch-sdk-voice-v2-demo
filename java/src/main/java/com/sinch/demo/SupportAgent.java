package com.sinch.demo;

import com.fasterxml.jackson.annotation.JsonValue;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.Gemini;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** AI support agent (Google ADK + Gemini). */
public class SupportAgent {

  private static final String APP_NAME = "acme_fiber";
  private static final String USER_ID = "caller";

  private static final String SYSTEM_PROMPT =
      """
      You are the phone support agent of Acme Fiber, an internet provider.
      Everything you write is spoken to the customer by text-to-speech:
      - Answer in one or two short sentences. No lists, no markdown, no emojis.
      - Spell out numbers the way you would say them.

      How to help:
      - Ask for the account number, so the technician gets it if the call is transferred.
      - Help with simple connection problems, such as restarting the router.

      When to call transfer_to_human:
      - reason "out_of_scope": the customer asks about cancellation, a billing dispute, a technician visit or an ownership change.
      - reason "needs_technician": the problem needs a technician, such as no signal at the fiber terminal.
      - reason "stuck": the conversation makes no progress, because you cannot understand the problem or the customer keeps repeating it.
      - reason "customer_request": the customer asks for a person.
      The summary must be two or three sentences that can be spoken in under 15 seconds:
      the account number if known, the problem, and what was already tried.
      After calling transfer_to_human, do not say anything else: the system tells the customer.""";

  private static final String SUMMARY_PROMPT =
      """
      The call is being transferred to a technician. Do not call any tool.
      Write only the handoff summary: two or three sentences that can be spoken in under 15 seconds,
      with the account number if known, the problem, and what was already tried.""";

  // One in-memory session per call holds the conversation history.
  private static final RunConfig RUN_CONFIG = RunConfig.builder().autoCreateSession(true).build();

  /** ADK declares these values to the model as an enum. */
  public enum TransferReason {
    CUSTOMER_REQUEST("customer_request"),
    OUT_OF_SCOPE("out_of_scope"),
    STUCK("stuck"),
    NEEDS_TECHNICIAN("needs_technician");

    private final String value;

    TransferReason(String value) {
      this.value = value;
    }

    @JsonValue
    @Override
    public String toString() {
      return value;
    }
  }

  public record TransferRequest(TransferReason reason, String summary) {}

  private TransferRequest transferRequest;
  private final String sessionId = UUID.randomUUID().toString();
  private final Runner runner;
  private boolean conversation = false;

  public SupportAgent(Config config) {
    LlmAgent agent =
        LlmAgent.builder()
            .name("acme_fiber_support")
            .model(Gemini.builder().modelName(config.llmModel()).apiKey(config.llmApiKey()).build())
            .instruction(SYSTEM_PROMPT)
            .tools(createTools())
            .build();
    this.runner =
        new Runner.Builder()
            .appName(APP_NAME)
            .agent(agent)
            .sessionService(new InMemorySessionService())
            .build();
  }

  /** The transfer requested during the last reply, or null. */
  public TransferRequest transferRequest() {
    return transferRequest;
  }

  public String reply(String text) {
    transferRequest = null;
    conversation = true;
    return run(text);
  }

  public boolean hasConversation() {
    return conversation;
  }

  public String summarize() {
    return run(SUMMARY_PROMPT);
  }

  private String run(String text) {
    Content message = Content.builder().role("user").parts(Part.fromText(text)).build();
    String reply = "";
    for (Event event :
        runner.runAsync(USER_ID, sessionId, message, RUN_CONFIG).blockingIterable()) {
      if (event.finalResponse() && event.content().flatMap(Content::parts).isPresent()) {
        reply =
            event.content().get().parts().get().stream()
                .map(part -> part.text().orElse(""))
                .collect(Collectors.joining());
      }
    }
    return reply.strip();
  }

  private List<BaseTool> createTools() {
    // Add your own tools here as public methods annotated with @Schema (ADK reads their signature
    // and descriptions), and list them in the return value and in SYSTEM_PROMPT. For example:
    //
    // @Schema(name = "lookup_account", description = "Look up a customer account by account number.")
    // public Map<String, Object> lookupAccount(
    //     @Schema(name = "account_number", description = "The account number given by the customer, digits only.")
    //         String accountNumber) {
    //   return yourCrm.getAccount(accountNumber);
    // }
    //
    // @Schema(name = "get_ticket_status", description = "Get the open support ticket of an account, if any.")
    // public Map<String, Object> getTicketStatus(
    //     @Schema(name = "account_number", description = "A verified account number.")
    //         String accountNumber) {
    //   return yourTicketing.getOpenTicket(accountNumber);
    // }
    //
    // and: FunctionTool.create(this, "lookupAccount"), FunctionTool.create(this, "getTicketStatus")

    return List.of(FunctionTool.create(this, "transferToHuman"));
  }

  @Schema(name = "transfer_to_human", description = "Transfer the call to a human technician.")
  public String transferToHuman(
      @Schema(name = "reason", description = "Why the call is transferred.") TransferReason reason,
      @Schema(
              name = "summary",
              description =
                  "Two or three sentences for the technician: account number if known, the"
                      + " problem, and what was already tried.")
          String summary) {
    // Only records the request; the server runs the handoff.
    transferRequest = new TransferRequest(reason, summary);
    return "Transfer requested.";
  }
}
