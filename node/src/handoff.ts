/** Builds the handoff SVAML with the SDK helpers, sends it to the inbound leg and writes the handoff log. */

import { appendFileSync } from 'node:fs';
import { SinchClient, Voice } from '@sinch/sdk-core';
import type { Config } from './config.ts';

// Call names shared with setup-service.ts.
export const INBOUND = 'inbound';
export const AI_AGENT = 'ai-agent';
export const MAIN_BRIDGE = 'main-bridge';
// Dialing order. Same length as TECHNICIANS in config.ts.
export const TECHNICIAN_CALL_NAMES = ['tech-1', 'tech-2', 'tech-3'];

const WHISPER_PREFIX = 'Acme Fiber transfer. ';
// messagesName of the transfer notice and hold music, stopped when a technician answers.
const HOLD_MUSIC = 'hold-music';
const TRANSFER_NOTICE = "I'm connecting you with a technician.";
const NO_TECHNICIAN_TEXT = 'Sorry, no technician is available right now. Please call us again later.';
const HANDOFF_FAILED_TEXT = "Sorry, I couldn't reach a technician. Please call us again later.";
// A TTS message accepts at most 600 characters.
const MAX_SAY_LENGTH = 600;
const HANDOFF_LOG_PATH = 'handoffs.jsonl';

type CommandsSequence = Voice.v2.CommandsSequenceCreator;

/**
 * Inbound leg: transfer notice and hold music, then dial tech-1, tech-2, tech-3 in turn.
 * The first to answer hears the whisper and joins the customer; if nobody answers, NO_TECHNICIAN_TEXT and hang up.
 */
export function buildHandoffCommands(config: Config, summary: string): Voice.v2.SvamlCommand[] {
  const voice = config.ttsVoice;
  const whisperText = (WHISPER_PREFIX + summary).slice(0, MAX_SAY_LENGTH);
  const from = Voice.v2.Destination.phone(config.sinchNumber);
  // Whisper to the technician, then stop the music, join the customer and drop the AI.
  const whisperAndBridge = (sequence: CommandsSequence) =>
    sequence.text(whisperText, voice, {
      onFinish: (onFinish) =>
        onFinish.stopMessages(HOLD_MUSIC).bridgeCall(MAIN_BRIDGE).hangup({ name: AI_AGENT }),
    });
  const hangupCaller = (sequence: CommandsSequence) => sequence.hangup({ name: INBOUND });

  return new Voice.v2.CommandsSequenceCreator()
    .messages((messages) => messages.name(HOLD_MUSIC).text(TRANSFER_NOTICE, voice).play(config.holdMusicUrl))
    .dial((tech1) =>
      tech1
        .to(Voice.v2.Destination.phone(config.technicianNumbers[0]))
        .from(from)
        .name(TECHNICIAN_CALL_NAMES[0])
        .timeoutDurationSeconds(config.dialTimeoutSeconds)
        .onAnswer(whisperAndBridge)
        .onTimeout((onTimeout) =>
          onTimeout.dial((tech2) =>
            tech2
              .to(Voice.v2.Destination.phone(config.technicianNumbers[1]))
              .from(from)
              .name(TECHNICIAN_CALL_NAMES[1])
              .timeoutDurationSeconds(config.dialTimeoutSeconds)
              .onAnswer(whisperAndBridge)
              .onTimeout((onTimeout) =>
                onTimeout.dial((tech3) =>
                  tech3
                    .to(Voice.v2.Destination.phone(config.technicianNumbers[2]))
                    .from(from)
                    .name(TECHNICIAN_CALL_NAMES[2])
                    .timeoutDurationSeconds(config.dialTimeoutSeconds)
                    .onAnswer(whisperAndBridge)
                    .onTimeout((noTechnician) =>
                      noTechnician.stopMessages(HOLD_MUSIC).text(NO_TECHNICIAN_TEXT, voice, {
                        onFinish: (onFinish) => onFinish.hangup({ name: AI_AGENT }).hangup({ name: INBOUND }),
                      }),
                    )
                    .onHangup(hangupCaller),
                ),
              )
              .onHangup(hangupCaller),
          ),
        )
        .onHangup(hangupCaller),
    )
    .build();
}

/** Inbound leg: apologize, then hang up the AI leg and the caller. */
export function buildHandoffFailedCommands(config: Config): Voice.v2.SvamlCommand[] {
  return new Voice.v2.CommandsSequenceCreator()
    .text(HANDOFF_FAILED_TEXT, config.ttsVoice, {
      onFinish: (onFinish) => onFinish.hangup({ name: AI_AGENT }).hangup({ name: INBOUND }),
    })
    .build();
}

/** PATCH the handoff. If it fails, PATCH the apology and hang-up, then rethrow. */
export async function runHandoff(
  client: SinchClient,
  config: Config,
  aiAgentCallId: string,
  reason: string,
  summary: string,
): Promise<void> {
  writeHandoffLog(aiAgentCallId, reason, summary);
  try {
    await sendHandoff(client, aiAgentCallId, buildHandoffCommands(config, summary));
  } catch (error) {
    await sendHandoff(client, aiAgentCallId, buildHandoffFailedCommands(config));
    throw error;
  }
}

/** PATCH the inbound leg, found by name in the ai-agent leg's session. */
export async function sendHandoff(
  client: SinchClient,
  aiAgentCallId: string,
  commands: Voice.v2.SvamlCommand[],
): Promise<void> {
  const { sessionId } = await client.voice.v2.calls.get({ callId: aiAgentCallId });
  await client.voice.v2.calls.interactByCallName({
    sessionId,
    callName: INBOUND,
    callPatchRequestBody: { commands },
  });
}

export function writeHandoffLog(callId: string, reason: string, summary: string): void {
  const entry = {
    timestamp: new Date().toISOString(),
    callId,
    reason,
    summary,
  };
  appendFileSync(HANDOFF_LOG_PATH, JSON.stringify(entry) + '\n', 'utf-8');
}
