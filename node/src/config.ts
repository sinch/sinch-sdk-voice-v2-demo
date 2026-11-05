/** Loads and validates environment variables, and creates the Sinch client. */

import 'dotenv/config';
import { SinchClient } from '@sinch/sdk-core';

const REQUIRED = [
  'SINCH_PROJECT_ID',
  'SINCH_KEY_ID',
  'SINCH_KEY_SECRET',
  'SINCH_SERVICE_ID',
  'SINCH_NUMBER',
  'TECHNICIAN_NUMBERS',
  'RELAY_PUBLIC_URL',
  'HOLD_MUSIC_URL',
  'LLM_API_KEY',
] as const;

// Demo settings
const TTS_VOICE = 'Emma';
const STT_LANGUAGE = 'en-US';
const GREETING = 'Welcome to Acme Fiber support. Please describe your problem, or press 1 at any time to talk to a technician.';
const PORT = 8000;
const DIAL_TIMEOUT_SECONDS = 15;
const LLM_MODEL = 'gemini-3.5-flash-lite';
// Voice API v2 North America region.
const VOICE_V2_HOSTNAME = 'https://us1.voice.api.sinch.com';

// Only needed by call-sinch-phone-number.ts.
const OPTIONAL = ['TEST_FROM_NUMBER', 'TEST_PHONE_NUMBER'] as const;

// Must match the length of TECHNICIAN_CALL_NAMES in handoff.ts.
export const TECHNICIANS = 3;

type VariableName = (typeof REQUIRED)[number] | (typeof OPTIONAL)[number];

export interface Config {
  sinchProjectId: string;
  sinchKeyId: string;
  sinchKeySecret: string;
  sinchServiceId: string;
  sinchNumber: string;
  technicianNumbers: string[];
  dialTimeoutSeconds: number;
  relayPublicUrl: string;
  holdMusicUrl: string;
  ttsVoice: string;
  sttLanguage: string;
  greeting: string;
  port: number;
  llmModel: string;
  llmApiKey: string;
  testFromNumber: string;
  testPhoneNumber: string;
}

export function loadConfig(): Config {
  const values = {} as Record<VariableName, string>;
  const missing: string[] = [];
  for (const name of REQUIRED) {
    const value = (process.env[name] ?? '').trim();
    if (!value) {
      missing.push(name);
    }
    values[name] = value;
  }
  for (const name of OPTIONAL) {
    values[name] = (process.env[name] ?? '').trim();
  }
  if (missing.length > 0) {
    throw new Error('Missing environment variables: ' + missing.join(', '));
  }
  if (!values.RELAY_PUBLIC_URL.startsWith('wss://') && !values.RELAY_PUBLIC_URL.startsWith('ws://')) {
    throw new Error('RELAY_PUBLIC_URL must start with wss:// or ws://');
  }
  if (!values.HOLD_MUSIC_URL.startsWith('https://') && !values.HOLD_MUSIC_URL.startsWith('http://')) {
    throw new Error('HOLD_MUSIC_URL must start with https:// or http://');
  }
  const technicianNumbers = values.TECHNICIAN_NUMBERS.split(',')
    .map((number) => number.trim())
    .filter((number) => number);
  if (technicianNumbers.length !== TECHNICIANS) {
    throw new Error(
      'TECHNICIAN_NUMBERS needs exactly ' + TECHNICIANS + ' numbers (repeat a number if you have fewer)',
    );
  }
  return {
    sinchProjectId: values.SINCH_PROJECT_ID,
    sinchKeyId: values.SINCH_KEY_ID,
    sinchKeySecret: values.SINCH_KEY_SECRET,
    sinchServiceId: values.SINCH_SERVICE_ID,
    sinchNumber: values.SINCH_NUMBER,
    technicianNumbers,
    dialTimeoutSeconds: DIAL_TIMEOUT_SECONDS,
    relayPublicUrl: values.RELAY_PUBLIC_URL,
    holdMusicUrl: values.HOLD_MUSIC_URL,
    ttsVoice: TTS_VOICE,
    sttLanguage: STT_LANGUAGE,
    greeting: GREETING,
    port: PORT,
    llmModel: LLM_MODEL,
    llmApiKey: values.LLM_API_KEY,
    testFromNumber: values.TEST_FROM_NUMBER,
    testPhoneNumber: values.TEST_PHONE_NUMBER,
  };
}

export function createSinchClient(config: Config): SinchClient {
  return new SinchClient({
    projectId: config.sinchProjectId,
    keyId: config.sinchKeyId,
    keySecret: config.sinchKeySecret,
    voiceV2Hostname: VOICE_V2_HOSTNAME,
  });
}
