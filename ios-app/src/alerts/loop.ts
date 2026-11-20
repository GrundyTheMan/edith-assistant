import { log } from '../core/log';
import { Store } from '../core/store';
import type { AlertChoices, Alert } from './model';
import { summaryText, type Notifier } from './notifications';
import { MAX_BACKOFF_MS, type AlertWatcher } from './watcher';

/** Back for longer than this: the owner gets a "while you were away" summary (C72 §3). */
export const AWAY_MS = 2 * 60_000;

/** What the Home tab shows after the owner returns. There are no pictures here: a snapshot now would not be of the event. */
export interface CatchUpSummary {
  text: string;
  alerts: Alert[];
}

export interface LoopDeps {
  watcher: AlertWatcher;
  choices: Store<AlertChoices>;
  notifier: Notifier;
  /** A picture of the alert's camera now (base64 JPEG), or null. Shown a few seconds after the alert, as on Android. */
  photo: (alert: Alert) => Promise<string | null>;
  now?: () => number;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

/**
 * The foreground loop (C72 option A). iOS suspends an app that leaves the screen, so alerts are looked for **only while Edith is active**: every 45 s
 * (the watcher says when), an immediate look when the app comes back, and when it comes back after more than two minutes one look with a longer reach-back
 * that becomes a summary on the Home tab instead of a burst of notifications. Nothing runs in the background.
 */
export class AlertLoop {
  /** The summary the Home tab shows, or null. */
  readonly catchUp = new Store<CatchUpSummary | null>(null);
  private active = false;
  private awaySince: number | null = null;
  private timer: unknown = null;
  private epoch = 0;
  private started = false;
  private readonly now: () => number;
  private readonly setTimer: (fn: () => void, ms: number) => unknown;
  private readonly clearTimer: (handle: unknown) => void;

  constructor(private readonly deps: LoopDeps) {
    this.now = deps.now ?? Date.now;
    this.setTimer = deps.setTimer ?? ((fn, ms) => setTimeout(fn, ms));
    this.clearTimer = deps.clearTimer ?? ((h) => clearTimeout(h as ReturnType<typeof setTimeout>));
  }

  /** Begins reacting to the owner's switches: the first one switched on starts looking (it only learns), the last one off stops. */
  start(): void {
    if (this.started) return;
    this.started = true;
    let was = this.deps.choices.get().any;
    this.deps.choices.subscribe(() => {
      const now = this.deps.choices.get().any;
      if (now && !was && this.active) void this.look();
      if (!now) {
        this.cancel();
        this.catchUp.set(null);
      }
      was = now;
    });
  }

  /** The app became active or left the screen (`AppState`). Leaving pauses; coming back looks at once, or catches up after a long absence. */
  setActive(active: boolean): void {
    if (active === this.active) return;
    this.active = active;
    if (!active) {
      this.awaySince = this.now();
      this.cancel();
      return;
    }
    const since = this.awaySince;
    this.awaySince = null;
    if (!this.deps.choices.get().any) return;
    if (since !== null && this.now() - since > AWAY_MS) void this.catchUpLook(since);
    else void this.look();
  }

  dismissCatchUp(): void {
    this.catchUp.set(null);
  }

  private cancel() {
    this.epoch++;
    if (this.timer !== null) this.clearTimer(this.timer);
    this.timer = null;
  }

  private schedule(epoch: number, ms: number) {
    if (this.timer !== null) this.clearTimer(this.timer);
    this.timer = this.setTimer(() => void this.look(epoch), ms);
  }

  private async look(epoch = ++this.epoch): Promise<void> {
    if (!this.active || epoch !== this.epoch) return;
    const choices = this.deps.choices.get();
    if (!choices.any) return;
    let next = MAX_BACKOFF_MS;
    try {
      const round = await this.deps.watcher.check(choices);
      if (!this.active || epoch !== this.epoch) return;
      next = round.nextInMs;
      for (const alert of round.alerts) {
        await this.deps.notifier.show(alert).catch(() => {});
        void this.addPicture(alert);
      }
    } catch (e) {
      log.warn('alerts', `a look failed (${e instanceof Error ? e.name : 'error'})`); // a surprise from a service: carry on
    }
    if (this.active && epoch === this.epoch) this.schedule(epoch, next);
  }

  private async catchUpLook(since: number): Promise<void> {
    const epoch = ++this.epoch;
    try {
      const alerts = await this.deps.watcher.catchUp(this.deps.choices.get());
      if (!this.active || epoch !== this.epoch) return;
      const text = summaryText(alerts, since);
      this.catchUp.set(text ? { text, alerts } : null);
    } catch (e) {
      log.warn('alerts', `the catch-up failed (${e instanceof Error ? e.name : 'error'})`);
    }
    await this.look(epoch);
  }

  /** Every alert gets its camera's picture a few seconds later (a battery doorbell must wake first); the alert is already showing and is replaced quietly. */
  private async addPicture(alert: Alert): Promise<void> {
    try {
      const picture = await this.deps.photo(alert);
      if (picture) await this.deps.notifier.show(alert, picture);
    } catch {
      // the alert stays as it was
    }
  }
}
