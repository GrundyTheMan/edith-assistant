import type { FeedResult } from '../../jarvis/api';

/** Plain-English words for everything that can go wrong asking Jarvis about the printer (C39 §7.5, ported from `PrinterWords.kt`). These are the strings: do not reword them. */
export const PrinterWords = {
  UNREACHABLE: "Edith can't reach Jarvis. Is the PC on, Jarvis running, and Tailscale connected (away from home)?",
  UNAUTHORIZED: 'Jarvis removed this phone: pair it again.',
  NOT_SHARED: "Jarvis isn't sharing the printer with this phone.",
  CONTROL_OFF: 'Jarvis lets this phone look at the printer, not control it.',
  CONTROLS_OFF: 'Controls turned off. Turn them on to continue.',
  PRINTER_UNREACHABLE: "Jarvis can't reach the printer right now.",
  TIMEOUT: 'The printer took too long to answer.',
  TOO_LARGE: 'That file is too big to send from the phone.',
  NOT_FOUND: "Jarvis doesn't have that any more. Refresh the list.",
  TOO_MANY: 'Too many requests at once. Wait a moment and try again.',
  BAD_REQUEST: "Jarvis didn't understand that. (It may be an older version.)",
  UNEXPECTED: "Jarvis answered something Edith didn't expect.",
} as const;

/** The server's message when it reads as a sentence (it has spaces and is not a long dump), else null. */
const sentence = (message: string | undefined): string | null => {
  const t = (message ?? '').trim();
  return t.includes(' ') && t.length <= 300 ? t : null;
};

/** The words for an error answer by its code; Jarvis's own sentence is used where the table says "the server's message". */
export function wordsForError(code: string | undefined, message?: string): string {
  switch (code) {
    case 'PRINTER_NOT_SHARED':
      return PrinterWords.NOT_SHARED;
    case 'CONTROL_OFF':
      return PrinterWords.CONTROL_OFF;
    case 'CONTROLS_OFF':
      return PrinterWords.CONTROLS_OFF;
    case 'REFUSED':
    case 'AWAY_LIMITED':
      return sentence(message) ?? PrinterWords.UNEXPECTED;
    case 'PRINTER_UNREACHABLE':
      return PrinterWords.PRINTER_UNREACHABLE;
    case 'TIMEOUT':
      return PrinterWords.TIMEOUT;
    case 'TOO_LARGE':
      return PrinterWords.TOO_LARGE;
    case 'NOT_FOUND':
      return PrinterWords.NOT_FOUND;
    case 'TOO_MANY':
      return PrinterWords.TOO_MANY;
    case 'UNREADABLE':
      return PrinterWords.UNEXPECTED;
    case 'BAD_REQUEST':
      return PrinterWords.BAD_REQUEST;
    default:
      return sentence(message) ?? PrinterWords.UNEXPECTED;
  }
}

/** The words for any result that is not `ok`. */
export function wordsForFailure(r: FeedResult<unknown>): string {
  switch (r.kind) {
    case 'ok':
      return '';
    case 'unauthorized':
      return PrinterWords.UNAUTHORIZED;
    case 'unreachable':
      return PrinterWords.UNREACHABLE;
    case 'notShared':
      return PrinterWords.NOT_SHARED;
    case 'busy':
      return PrinterWords.TOO_MANY;
    case 'timeout':
      return PrinterWords.TIMEOUT;
    case 'unavailable':
      return r.message || PrinterWords.UNEXPECTED;
    case 'failed':
      return wordsForError(r.code, r.message);
  }
}
