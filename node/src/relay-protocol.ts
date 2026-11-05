/** Voice Relay WebSocket messages. */

export type RelayMessage = Record<string, unknown> & { command: string };

/** Helpers to parse and build Voice Relay messages. */
export const RelayMessages = {
  CONNECT: 'connect',
  TEXT: 'text',
  DTMF: 'dtmf',

  parse(raw: string): RelayMessage {
    const message: unknown = JSON.parse(raw);
    if (
      typeof message !== 'object' ||
      message === null ||
      Array.isArray(message) ||
      typeof (message as Record<string, unknown>).command !== 'string'
    ) {
      throw new Error('Voice Relay message without a command');
    }
    return message as RelayMessage;
  },

  answer(): RelayMessage {
    return { command: 'answer' };
  },

  interrupt(): RelayMessage {
    return { command: 'interrupt' };
  },

  text(text: string, isLast = true, isInterruptible?: boolean): RelayMessage {
    const message: RelayMessage = { command: RelayMessages.TEXT, text, isLast };
    if (isInterruptible !== undefined) {
      message.isInterruptible = isInterruptible;
    }
    return message;
  },
};
