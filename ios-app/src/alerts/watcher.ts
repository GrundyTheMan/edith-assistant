import { AlertChoices, alertKindOf, type Activity, type ActivitySource, type Alert } from './model';

export const INTERVAL_MS = 45_000;
export const MAX_BACKOFF_MS = 5 * 60_000;
export const MIN_WAIT_MS = 5_000;
/** How far back each look reaches. */
export const WINDOW_MS = 10 * 60_000;
const FORGET_MS = 60 * 60_000;
/** The look when the owner returns to the app (C72 §3): up to a day back, at most this many items. */
export const CATCH_UP_WINDOW_MS = 24 * 3_600_000;
export const CATCH_UP_MAX = 20;

export interface Round {
  /** To show now, oldest first. */
  alerts: Alert[];
  /** How long until the next look is due. */
  nextInMs: number;
}

interface Track {
  learned: boolean;
  failures: number;
  nextAt: number;
  /** key -> when it happened */
  seen: Map<string, number>;
}

/**
 * Decides which camera events become alerts, one round at a time (C33 on Android, C72 here). It holds no timer and shows nothing: the loop calls `check`
 * and waits `nextInMs`.
 *
 * - The first successful look at a service only **learns** what is already there; nothing old is announced.
 * - Each look covers the last `WINDOW_MS`, so an event the service lists late is still caught, and what was seen is remembered, so nothing is announced
 *   twice. Events of a kind that is off are remembered too: switching it on later doesn't announce the past.
 * - At most `maxPerCheck` alerts per service per look (the newest); the rest are dropped, not queued.
 * - A look that fails waits twice as long each time (up to `maxBackoffMs`) and resets after a good one.
 */
export class AlertWatcher {
  private readonly tracks = new Map<string, Track>();
  constructor(
    private readonly source: ActivitySource,
    private readonly now: () => number = Date.now,
    private readonly options: { maxPerCheck?: number; intervalMs?: number; maxBackoffMs?: number } = {},
  ) {}

  private get maxPerCheck() {
    return this.options.maxPerCheck ?? 3;
  }
  private get intervalMs() {
    return this.options.intervalMs ?? INTERVAL_MS;
  }
  private get maxBackoffMs() {
    return this.options.maxBackoffMs ?? MAX_BACKOFF_MS;
  }

  async check(choices: AlertChoices): Promise<Round> {
    const now = this.now();
    const wanted = new Set(choices.backends);
    for (const id of [...this.tracks.keys()]) if (!wanted.has(id)) this.tracks.delete(id); // switched off: forget, so switching on again learns afresh
    const alerts: Alert[] = [];
    for (const backend of wanted) {
      let track = this.tracks.get(backend);
      if (!track) this.tracks.set(backend, (track = { learned: false, failures: 0, nextAt: 0, seen: new Map() }));
      if (now < track.nextAt) continue;
      let found;
      try {
        found = await this.source.activity(backend, now - WINDOW_MS);
      } catch {
        track.failures++;
        track.nextAt = now + Math.min(this.intervalMs * 2 ** Math.min(track.failures, 10), this.maxBackoffMs);
        continue;
      }
      track.failures = 0;
      track.nextAt = now + this.intervalMs;
      const fresh = found.filter((a) => !track.seen.has(a.key));
      for (const a of fresh) track.seen.set(a.key, a.at);
      for (const [k, at] of track.seen) if (at < now - FORGET_MS) track.seen.delete(k);
      if (!track.learned) {
        track.learned = true;
        continue;
      }
      alerts.push(...this.toAlerts(backend, fresh, choices).slice(0, this.maxPerCheck));
    }
    const soonest = Math.min(...[...this.tracks.values()].map((t) => t.nextAt));
    const wait = this.tracks.size === 0 ? this.intervalMs : Math.min(this.maxBackoffMs, Math.max(MIN_WAIT_MS, soonest - now));
    return { alerts: alerts.sort((a, b) => a.at - b.at), nextInMs: wait };
  }

  /**
   * The owner is back after a while (C72 §3): everything unseen in the last `windowMs`, newest `max` of it, across the services that have an alert on.
   * What was already announced is not repeated; the failures are skipped (the next look tries again).
   */
  async catchUp(choices: AlertChoices, windowMs = CATCH_UP_WINDOW_MS, max = CATCH_UP_MAX): Promise<Alert[]> {
    const now = this.now();
    const out: Alert[] = [];
    for (const backend of choices.backends) {
      let track = this.tracks.get(backend);
      if (!track) this.tracks.set(backend, (track = { learned: false, failures: 0, nextAt: 0, seen: new Map() }));
      let found;
      try {
        found = await this.source.activity(backend, now - windowMs);
      } catch {
        continue;
      }
      const fresh = found.filter((a) => !track.seen.has(a.key));
      for (const a of fresh) track.seen.set(a.key, a.at);
      track.learned = true;
      out.push(...this.toAlerts(backend, fresh, choices));
    }
    return out.sort((a, b) => b.at - a.at).slice(0, max).reverse();
  }

  private toAlerts(backend: string, fresh: Activity[], choices: AlertChoices): Alert[] {
    const out: Alert[] = [];
    for (const a of fresh) {
      const kind = alertKindOf(a.kind);
      if (kind && choices.enabled(backend, kind)) out.push({ backend, device: a.device, kind, at: a.at, key: a.key });
    }
    return out.sort((a, b) => b.at - a.at);
  }
}

