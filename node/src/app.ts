/**
 * Voice Relay WebSocket server (/relay) with the AI agent and the handoff, plus GET /health.
 *
 *   1. Sinch sends connect: the server answers and greets the customer.
 *   2. Each transcript goes to the agent, and its reply goes back as text.
 *   3. On transfer_to_human or DTMF 1, the server PATCHes the customer's leg and stays silent.
 *
 * Run: npm start
 */

import { createServer } from 'node:http';
import type { IncomingMessage, ServerResponse } from 'node:http';
import type { SinchClient } from '@sinch/sdk-core';
import { WebSocket, WebSocketServer } from 'ws';
import { SupportAgent } from './agent.ts';
import { createSinchClient, loadConfig } from './config.ts';
import type { Config } from './config.ts';
import * as handoff from './handoff.ts';
import { RelayMessages } from './relay-protocol.ts';
import type { RelayMessage } from './relay-protocol.ts';

// Raw Voice Relay messages are cut to this length in the log.
const MAX_LOGGED_MESSAGE_LENGTH = 200;

const APOLOGY = "Sorry, I'm having trouble right now. Could you say that again?";

// Whisper for DTMF 1 when there is no conversation to summarize.
const KEYPAD_SUMMARY = 'The customer pressed 1 to talk to a technician.';
const TECHNICIAN_KEY = '1';

// WebSocket close code for an unexpected server error.
const INTERNAL_ERROR = 1011;

/** State of one call. */
class CallSession {
  handoffStarted = false;

  constructor(
    readonly callId: string,
    readonly agent: SupportAgent,
  ) {}
}

function log(message: string): void {
  console.log(`${new Date().toTimeString().slice(0, 8)} ${message}`);
}

function preview(raw: string): string {
  return raw.length <= MAX_LOGGED_MESSAGE_LENGTH ? raw : raw.slice(0, MAX_LOGGED_MESSAGE_LENGTH) + '…';
}

function send(websocket: WebSocket, message: RelayMessage): void {
  const raw = JSON.stringify(message);
  log(`  WS >> ${preview(raw)}`);
  websocket.send(raw);
}

/** From here on Sinch runs the call (see handoff.runHandoff). */
async function startHandoff(
  websocket: WebSocket,
  session: CallSession,
  config: Config,
  client: SinchClient,
  reason: string,
  summary: string,
): Promise<void> {
  session.handoffStarted = true;
  log(`    handoff  reason=${reason}  summary=${JSON.stringify(summary)}`);
  // Stop any AI speech.
  send(websocket, RelayMessages.interrupt());
  try {
    await handoff.runHandoff(client, config, session.callId, reason, summary);
    log('    PATCH handoff → sent');
  } catch (error) {
    log(`    [!] PATCH handoff failed: ${error}`);
  }
}

/** DTMF 1: hand off without asking the LLM. The LLM only writes the whisper, if there was a conversation. */
async function requestTechnician(
  websocket: WebSocket,
  session: CallSession,
  config: Config,
  client: SinchClient,
): Promise<void> {
  let summary = KEYPAD_SUMMARY;
  if (session.agent.hasConversation()) {
    try {
      summary = (await session.agent.summarize()) || KEYPAD_SUMMARY;
    } catch (error) {
      log(`    [!] summary error, using the fixed one: ${error}`);
    }
  }
  await startHandoff(websocket, session, config, client, 'customer_request', summary);
}

async function handleTranscript(
  websocket: WebSocket,
  session: CallSession,
  config: Config,
  client: SinchClient,
  text: string,
): Promise<void> {
  const agent = session.agent;
  let reply: string;
  try {
    reply = await agent.reply(text);
    log(`    LLM ← ${JSON.stringify(reply)}`);
    if (agent.transferRequest !== null) {
      const request = agent.transferRequest;
      await startHandoff(websocket, session, config, client, request.reason, request.summary);
      return;
    }
  } catch (error) {
    log(`    [!] LLM error: ${error}`);
    if (!session.handoffStarted) {
      send(websocket, RelayMessages.text(APOLOGY));
    }
    return;
  }

  send(websocket, RelayMessages.text(reply || APOLOGY));
}

export function createApp(config: Config): ReturnType<typeof createServer> {
  const client = createSinchClient(config);

  const server = createServer((request: IncomingMessage, response: ServerResponse) => {
    if (request.method === 'GET' && request.url === '/health') {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ status: 'ok' }));
      return;
    }
    response.writeHead(404);
    response.end();
  });

  const relay = new WebSocketServer({ server, path: '/relay' });
  relay.on('connection', (websocket: WebSocket) => {
    let session: CallSession | null = null;

    async function handleMessage(raw: string): Promise<void> {
      log(`WS << ${preview(raw)}`);
      const message = RelayMessages.parse(raw);
      const command = message.command;
      if (command === RelayMessages.CONNECT) {
        session = new CallSession(String(message.callId), new SupportAgent(config));
        log(`    connect  callId=${session.callId}  serviceId=${message.serviceId}`);
        send(websocket, RelayMessages.answer());
        send(websocket, RelayMessages.text(config.greeting));
        return;
      }
      if (session === null) {
        throw new Error(`Voice Relay message before connect: ${command}`);
      }
      if (command === RelayMessages.TEXT && !message.isCorrection && !session.handoffStarted) {
        log(`    STT → ${JSON.stringify(message.text)}`);
        await handleTranscript(websocket, session, config, client, String(message.text ?? ''));
      } else if (
        command === RelayMessages.DTMF &&
        String(message.sequence ?? '').includes(TECHNICIAN_KEY) &&
        !session.handoffStarted
      ) {
        log(`    DTMF ${message.sequence} → technician requested`);
        await requestTechnician(websocket, session, config, client);
      } else if (session.handoffStarted) {
        log('    ignored: handoff started');
      }
    }

    // Handle one message at a time, in the order Sinch sends them.
    let queue: Promise<void> = Promise.resolve();
    websocket.on('message', (data) => {
      queue = queue
        .then(() => handleMessage(data.toString()))
        .catch((error) => {
          log(`    [!] ${error}`);
          websocket.close(INTERNAL_ERROR);
        });
    });
    websocket.on('close', (code: number) => {
      log(`    websocket closed  callId=${session ? session.callId : '-'}  code=${code}`);
    });
  });

  return server;
}

const config = loadConfig();
createApp(config).listen(config.port, () => log(`Listening on port ${config.port}`));
