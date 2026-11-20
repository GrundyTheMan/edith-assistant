import type { CallOptions, FeedResult } from '../../jarvis/api';
import type { JarvisClient } from '../../jarvis/service';
import { commandObject, type PrinterCommand } from './commands';
import { PrinterWords, wordsForError, wordsForFailure } from './words';
import {
  parseCheck,
  parseCheckErrorBody,
  parseCommandSent,
  parseConsole,
  parseControlsAnswer,
  parseFileDetail,
  parseFiles,
  parseHistory,
  parseMacros,
  parseStateAnswer,
  parseTemperatures,
  type ArmState,
  type Check,
  type ConsoleAnswer,
  type FilesAnswer,
  type HistoryAnswer,
  type MacrosAnswer,
  type PrinterFileDetail,
  type StateAnswer,
  type TempSeries,
} from './wire';

/** What sending a command came to (C39 §5.2; the mapping from the server's codes is §7.5). */
export type CommandAnswer =
  /** Jarvis sent it: `sent` is what it said it sent, `risk` how carefully it was asked. */
  | { kind: 'sent'; sent: string; risk: string }
  /** A dry run: the check; nothing was sent. */
  | { kind: 'checked'; check: Check }
  /** The server wants the owner's yes first (`CONFIRM_REQUIRED`): show `check.question`, send again with `confirmed`. */
  | { kind: 'needsConfirm'; check: Check }
  /** Jarvis's own refusal, in its words. */
  | { kind: 'refused'; reason: string }
  /** Controls are off (any more): the page switches its Controls switch off. */
  | { kind: 'controlsOff' }
  /** Anything else; `words` are for the owner, `code` is Jarvis's code (or UNREACHABLE / UNAUTHORIZED) for the page's logic. */
  | { kind: 'failed'; words: string; code: string | null };

/** The routes of the printer feed as the rest of the app uses them (the session and the page take this, so they are tested with fakes). */
export interface PrinterRoutes {
  state(after: number | null, epoch: number | null, waitS: number, opts?: { quiet?: boolean; signal?: AbortSignal }): Promise<FeedResult<StateAnswer>>;
  temperatures(): Promise<FeedResult<TempSeries[]>>;
  macros(): Promise<FeedResult<MacrosAnswer>>;
  console(after?: number | null, count?: number): Promise<FeedResult<ConsoleAnswer>>;
  files(fresh: boolean): Promise<FeedResult<FilesAnswer>>;
  fileDetail(path: string): Promise<FeedResult<PrinterFileDetail>>;
  history(start: number): Promise<FeedResult<HistoryAnswer>>;
  snapshot(cameraId: string, signal?: AbortSignal): Promise<FeedResult<Uint8Array>>;
  controls(on: boolean): Promise<FeedResult<ArmState>>;
  command(command: PrinterCommand, via: 'phone' | 'chat', opts: { confirmed?: boolean; dryRun?: boolean; requestId?: string }): Promise<CommandAnswer>;
}

export const MAX_WAIT_S = 25;
const enc = encodeURIComponent;
/** A query string from the entries that are set (values are always encoded: never concatenate text into a URL). */
const qs = (q: Record<string, string | number | boolean | null | undefined>): string => {
  const parts = Object.entries(q)
    .filter(([, v]) => v !== null && v !== undefined)
    .map(([k, v]) => `${enc(k)}=${enc(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
};

const hasShape = (json: unknown, key: string): boolean => !!json && typeof json === 'object' && key in (json as object);

/**
 * One function per route of the printer feed (C39 §5.2, C67 E2), over the paired Jarvis's authenticated client (C66a). Every function returns a result; none throws for a
 * network or server problem, and each call can be cancelled. A 401 turns the connection to "pair it again" in the client, once.
 */
export class PrinterFeedApi implements PrinterRoutes {
  constructor(private readonly client: JarvisClient) {}

  private get<T>(path: string, parse: (j: unknown) => T | null, opts?: CallOptions) {
    return this.client.call('GET', `/v1/printer/${path}`, parse, opts);
  }

  /**
   * The long poll. Without `after` it answers at once; with the current `rev` Jarvis holds it up to `waitS` (at most 25) and the answer then has the same `rev` when nothing
   * changed. Send the `epoch` of the last answer. `quiet` when the page is not in front: Jarvis then sends live readings less often.
   */
  state(after: number | null, epoch: number | null, waitS: number, opts: { quiet?: boolean; signal?: AbortSignal } = {}) {
    const wait = Math.min(Math.max(waitS, 0), MAX_WAIT_S);
    return this.get('state' + qs({ after, epoch, wait: wait > 0 ? wait : null, quiet: opts.quiet ? 1 : null }), (j) => (hasShape(j, 'state') ? parseStateAnswer(j) : null), {
      timeoutMs: (wait + 15) * 1000,
      signal: opts.signal,
    });
  }

  temperatures() {
    return this.get('temperatures', (j) => (hasShape(j, 'series') ? parseTemperatures(j) : null));
  }
  macros() {
    return this.get('macros', (j) => (hasShape(j, 'macros') ? parseMacros(j) : null));
  }
  /** The newest `count` console lines, or only those after the line numbered `after`. */
  console(after: number | null = null, count = 200) {
    return this.get('console' + qs({ after, count }), (j) => (hasShape(j, 'lines') ? parseConsole(j) : null));
  }
  /** The cached list, or a fresh one (which freezes the printer for several seconds: never while printing). */
  files(fresh: boolean) {
    return this.get('files' + qs({ fresh: fresh ? 1 : 0 }), (j) => (hasShape(j, 'files') ? parseFiles(j) : null), { timeoutMs: 40_000 });
  }
  fileDetail(path: string) {
    return this.get('files/detail' + qs({ path }), (j) => (hasShape(j, 'path') ? parseFileDetail(j) : null));
  }
  history(start: number) {
    return this.get('history' + qs({ start }), (j) => (hasShape(j, 'jobs') ? parseHistory(j) : null), { timeoutMs: 40_000 });
  }
  /** A camera picture (JPEG or PNG bytes), about one a second. */
  snapshot(cameraId: string, signal?: AbortSignal) {
    return this.client.bytes(`/v1/printer/camera/${enc(cameraId)}/snapshot`, { signal });
  }
  controls(on: boolean) {
    return this.client.call('POST', '/v1/printer/controls', (j) => (hasShape(j, 'arm') ? parseControlsAnswer(j) : null), { body: { on } });
  }

  /**
   * Sends a typed command. `via` is "phone" (the page) or "chat" (Edith's chat tools: only pause, resume, cancel and light). A dry run asks the server what it would say, sending
   * nothing. A real send needs a `requestId`: a new UUID for each command and **the same one when the same send is retried**, so a retry after a timeout is not run twice.
   */
  async command(command: PrinterCommand, via: 'phone' | 'chat', opts: { confirmed?: boolean; dryRun?: boolean; requestId?: string } = {}): Promise<CommandAnswer> {
    const dryRun = !!opts.dryRun;
    const body: Record<string, unknown> = { command: commandObject(command), via };
    if (opts.confirmed) body.confirmed = true;
    if (dryRun) body.dryRun = true;
    else if (opts.requestId) body.requestId = opts.requestId;
    const r = await this.client.call<{ check: Check } | { sent: string; risk: string }>(
      'POST',
      '/v1/printer/command',
      (j) => {
        if (dryRun) return hasShape(j, 'allowed') ? { check: parseCheck(j) } : null;
        return j && typeof j === 'object' && typeof (j as { sent?: unknown }).sent === 'string' ? parseCommandSent(j) : null;
      },
      { body, timeoutMs: 30_000 },
    );
    switch (r.kind) {
      case 'ok':
        return 'check' in r.value ? { kind: 'checked', check: r.value.check } : { kind: 'sent', sent: r.value.sent, risk: r.value.risk };
      case 'failed': {
        switch (r.code) {
          case 'CONFIRM_REQUIRED': {
            const check = parseCheckErrorBody(r.body);
            return check ? { kind: 'needsConfirm', check } : { kind: 'failed', words: PrinterWords.UNEXPECTED, code: r.code };
          }
          case 'CONTROLS_OFF':
            return { kind: 'controlsOff' };
          case 'REFUSED':
            return { kind: 'refused', reason: wordsForError(r.code, r.message) };
          default:
            return { kind: 'failed', words: wordsForFailure(r), code: r.code ?? null };
        }
      }
      case 'unreachable':
        return { kind: 'failed', words: PrinterWords.UNREACHABLE, code: 'UNREACHABLE' };
      case 'unauthorized':
        return { kind: 'failed', words: PrinterWords.UNAUTHORIZED, code: 'UNAUTHORIZED' };
      default:
        return { kind: 'failed', words: wordsForFailure(r), code: r.kind === 'busy' ? 'TOO_MANY' : r.kind.toUpperCase() };
    }
  }
}
