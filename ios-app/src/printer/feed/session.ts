import { Store } from '../../core/store';
import type { PrinterRoutes } from './api';
import type { PrinterFeedSource } from './feed';
import type { Preset, SharedPrinter, StateAnswer } from './wire';
import { PrinterWords, wordsForFailure } from './words';

/** What the Printer page shows (C39 §4.3, §6.2). */
export type PrinterScreenState =
  | { kind: 'noJarvis' }
  | { kind: 'notShared' }
  | { kind: 'connecting' }
  /**
   * The printer as Jarvis last said it. `offsetMs` is Jarvis's clock minus the phone's at that answer, so the Controls countdown is right on a phone whose clock is off.
   * `stale` when the page has left and come back and nothing newer has arrived yet.
   */
  | { kind: 'live'; data: StateAnswer; receivedAt: number; offsetMs: number; stale: boolean }
  /** Edith cannot reach Jarvis: the last answer (if any) is shown greyed with `why`. */
  | { kind: 'away'; last: StateAnswer | null; why: string };

/** Jarvis holds a poll up to 25 s; 20 leaves room for the network. */
export const WAIT_S = 20;
export const TOO_MANY_WAIT_MS = 3_000;
/** After the n-th failure in a row: 2 s, 4 s, 8 s ... at most 30 s. */
export const backoff = (failures: number): number => Math.min(2_000 * 2 ** Math.min(Math.max(failures - 1, 0), 10), 30_000);

export interface SessionDeps {
  feed: PrinterFeedSource;
  now?: () => number;
  /** A wait that ends at once when `signal` aborts (the real one is `setTimeout`). */
  delay?: (ms: number, signal: AbortSignal) => Promise<void>;
}

const realDelay = (ms: number, signal: AbortSignal) =>
  new Promise<void>((resolve) => {
    if (signal.aborted) return resolve();
    const t = setTimeout(resolve, ms);
    signal.addEventListener('abort', () => {
      clearTimeout(t);
      resolve();
    });
  });

/**
 * Holds the printer's state while the Printer page is on screen (C39 §6, C67 E3). `attach` starts a loop that long-polls Jarvis's `state` route and publishes each
 * change; `detach` stops it. Nothing runs while the page is away. The loop never spins: an answer with an unchanged revision arrives only after Jarvis's wait, and a
 * failure waits 2, 4, 8 ... 30 seconds before asking again.
 */
export class PrinterSession {
  readonly state = new Store<PrinterScreenState>({ kind: 'connecting' });
  /** What Jarvis last said about the printer (its name, whether phones may control it, the presets and cameras). */
  readonly shared = new Store<SharedPrinter | null>(null);
  /** The last good answer, kept while Jarvis is away and between visits to the page. */
  lastLive: StateAnswer | null = null;
  /** True when the screen is off or the page is not in front: Jarvis then sends live readings less often. */
  quiet = false;
  private currentRoutes: PrinterRoutes | null = null;
  private ctl: AbortController | null = null;
  private readonly now: () => number;
  private readonly delay: (ms: number, signal: AbortSignal) => Promise<void>;

  constructor(private readonly deps: SessionDeps) {
    this.now = deps.now ?? Date.now;
    this.delay = deps.delay ?? realDelay;
  }

  /** The routes of the printer Jarvis shares, once the session has found them (the cards ask them for the chart, the files, the history). */
  routes(): PrinterRoutes | null {
    return this.currentRoutes;
  }

  /** The page came on screen. */
  attach(): void {
    this.ctl?.abort();
    const last = this.lastLive;
    this.state.set(last ? this.live(last, true) : { kind: 'connecting' });
    this.start();
  }

  /** The page left: stop asking, and turn Controls off if they are on (best effort; Jarvis also ends them on its own timer). */
  detach(): void {
    this.ctl?.abort();
    this.ctl = null;
    const last = this.lastLive;
    if (last && this.state.get().kind === 'live') this.state.set(this.live(last, true));
    if (last?.arm.armed && this.currentRoutes) void this.currentRoutes.controls(false).catch(() => {});
  }

  /** Asks again at once, without the wait (after a command, so the card updates without waiting for the next change). */
  refreshNow(): void {
    if (!this.ctl) return;
    this.ctl.abort();
    this.start();
  }

  /** Jarvis saved new presets (the editor): the page shows them at once; the next visit reads them from Jarvis again. */
  updatePresets(presets: Preset[]): void {
    const s = this.shared.get();
    if (s) this.shared.set({ ...s, presets });
  }

  private start() {
    const ctl = new AbortController();
    this.ctl = ctl;
    void this.run(ctl.signal);
  }

  private live(answer: StateAnswer, stale = false): PrinterScreenState {
    const now = this.now();
    return { kind: 'live', data: answer, receivedAt: now, offsetMs: answer.serverTime - now, stale };
  }

  private away(why: string) {
    this.state.set({ kind: 'away', last: this.lastLive, why });
  }

  private async run(signal: AbortSignal): Promise<void> {
    let failures = 0;
    let access: Awaited<ReturnType<PrinterFeedSource['access']>> | null = null;
    let rev: number | null = null;
    let epoch: number | null = null;
    const wait = async (ms: number) => {
      await this.delay(ms, signal);
    };
    while (!signal.aborted) {
      if (!access) access = await this.deps.feed.access();
      if (signal.aborted) return;
      switch (access.kind) {
        case 'noJarvis':
          this.state.set({ kind: 'noJarvis' });
          return;
        case 'notShared':
          this.state.set({ kind: 'notShared' });
          return;
        case 'away':
          this.away(access.why);
          if (access.removed) return;
          failures++;
          await wait(backoff(failures));
          access = null;
          break;
        case 'ready': {
          this.currentRoutes = access.api;
          this.shared.set(access.shared);
          const r = await access.api.state(rev, epoch, rev === null ? 0 : WAIT_S, { quiet: this.quiet, signal });
          if (signal.aborted) return;
          switch (r.kind) {
            case 'ok': {
              failures = 0;
              const answer = r.value;
              const current = this.state.get();
              // An unchanged revision is Jarvis's "nothing new": nothing to publish, unless the page is showing something older.
              const changed = answer.rev !== rev || answer.epoch !== epoch || current.kind !== 'live' || current.stale;
              rev = answer.rev;
              epoch = answer.epoch;
              if (changed) {
                this.lastLive = answer;
                this.state.set(this.live(answer));
              }
              break;
            }
            case 'notShared':
              this.state.set({ kind: 'notShared' });
              return;
            case 'failed':
              if (r.code === 'PRINTER_NOT_SHARED') {
                this.state.set({ kind: 'notShared' });
                return;
              }
              failures++;
              this.away(wordsForFailure(r));
              rev = null;
              await wait(backoff(failures));
              break;
            case 'busy':
              await wait(TOO_MANY_WAIT_MS);
              break;
            case 'unauthorized':
              this.away(PrinterWords.UNAUTHORIZED);
              return;
            case 'unreachable':
              failures++;
              this.away(PrinterWords.UNREACHABLE);
              rev = null;
              access = null; // read what Jarvis shares again when it is back
              await wait(backoff(failures));
              break;
            default:
              // timeout, unavailable: a failure like any other
              failures++;
              this.away(wordsForFailure(r));
              rev = null;
              await wait(backoff(failures));
          }
          break;
        }
      }
    }
  }
}
