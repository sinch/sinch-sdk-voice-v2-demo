/** AI support agent (Google ADK + Gemini). */

import { randomUUID } from 'node:crypto';
import { FunctionTool, Gemini, InMemorySessionService, LlmAgent, Runner, isFinalResponse } from '@google/adk';
import type { ToolUnion } from '@google/adk';
import { z } from 'zod';
import type { Config } from './config.ts';

const APP_NAME = 'acme_fiber';
const USER_ID = 'caller';

// ADK declares these values to the model as an enum.
const TRANSFER_REASONS = ['customer_request', 'out_of_scope', 'stuck', 'needs_technician'] as const;
export type TransferReason = (typeof TRANSFER_REASONS)[number];

const SYSTEM_PROMPT = `You are the phone support agent of Acme Fiber, an internet provider.
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
After calling transfer_to_human, do not say anything else: the system tells the customer.`;

const SUMMARY_PROMPT = `The call is being transferred to a technician. Do not call any tool.
Write only the handoff summary: two or three sentences that can be spoken in under 15 seconds,
with the account number if known, the problem, and what was already tried.`;

export interface TransferRequest {
  reason: TransferReason;
  summary: string;
}

export class SupportAgent {
  transferRequest: TransferRequest | null = null;
  // One in-memory session per call holds the conversation history.
  private readonly sessionId: string = randomUUID();
  private readonly runner: Runner;
  private conversation = false;

  constructor(config: Config) {
    const agent = new LlmAgent({
      name: 'acme_fiber_support',
      model: new Gemini({ model: config.llmModel, apiKey: config.llmApiKey }),
      instruction: SYSTEM_PROMPT,
      tools: this.createTools(),
    });
    this.runner = new Runner({
      appName: APP_NAME,
      agent,
      sessionService: new InMemorySessionService(),
      autoCreateSession: true,
    });
  }

  async reply(text: string): Promise<string> {
    this.transferRequest = null;
    this.conversation = true;
    return this.run(text);
  }

  hasConversation(): boolean {
    return this.conversation;
  }

  async summarize(): Promise<string> {
    return this.run(SUMMARY_PROMPT);
  }

  private async run(text: string): Promise<string> {
    let reply = '';
    for await (const event of this.runner.runAsync({
      userId: USER_ID,
      sessionId: this.sessionId,
      newMessage: { role: 'user', parts: [{ text }] },
    })) {
      if (isFinalResponse(event) && event.content?.parts) {
        reply = event.content.parts.map((part) => part.text ?? '').join('');
      }
    }
    return reply.trim();
  }

  private createTools(): ToolUnion[] {
    // Add your own tools here as FunctionTools (ADK sends the description and the zod schema
    // to the model), and list them in the return value and in SYSTEM_PROMPT. For example:
    //
    // const lookupAccount = new FunctionTool({
    //   name: 'lookup_account',
    //   description: 'Look up a customer account by account number.',
    //   parameters: z.object({
    //     account_number: z.string().describe('The account number given by the customer, digits only.'),
    //   }),
    //   execute: ({ account_number }) => yourCrm.getAccount(account_number),
    // });
    //
    // const getTicketStatus = new FunctionTool({
    //   name: 'get_ticket_status',
    //   description: 'Get the open support ticket of an account, if any.',
    //   parameters: z.object({
    //     account_number: z.string().describe('A verified account number.'),
    //   }),
    //   execute: ({ account_number }) => yourTicketing.getOpenTicket(account_number),
    // });

    const transferToHuman = new FunctionTool({
      name: 'transfer_to_human',
      description: 'Transfer the call to a human technician.',
      parameters: z.object({
        reason: z.enum(TRANSFER_REASONS).describe('Why the call is transferred.'),
        summary: z
          .string()
          .describe(
            'Two or three sentences for the technician: account number if known, the problem, and what was already tried.',
          ),
      }),
      execute: ({ reason, summary }) => {
        // Only records the request; the server runs the handoff.
        this.transferRequest = { reason, summary };
        return 'Transfer requested.';
      },
    });

    return [transferToHuman];
  }
}
