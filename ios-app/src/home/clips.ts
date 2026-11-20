import type { Home } from './home';
import type { DeviceClip } from './model';

/** The Clips list looks back in windows of 3 days, up to 60 days, and a quiet window loads the next older one (up to 4 per load) so the list isn't empty while older clips exist (C53 §2). */
export const WINDOW_MS = 3 * 86_400_000;
export const MAX_DAYS = 60;
export const WINDOWS_PER_LOAD = 4;

export interface ClipsState {
  clips: DeviceClip[];
  /** How far back has been asked (ms since the epoch): the next window ends here. */
  reached: number;
  problems: string[];
  /** Services that keep no recordings for this account: said once, never an error. */
  noPlan: string[];
  /** Nothing older is asked for: 60 days back were looked at. */
  done: boolean;
}

export const emptyClips = (now: number): ClipsState => ({ clips: [], reached: now, problems: [], noPlan: [], done: false });

const union = (a: string[], b: string[]) => [...new Set([...a, ...b])];

/** Loads older windows until one has clips, four windows at most; the clips already there are kept. */
export async function loadOlder(home: Home, state: ClipsState, now: number): Promise<ClipsState> {
  const floor = now - MAX_DAYS * 86_400_000;
  let next = state;
  for (let i = 0; i < WINDOWS_PER_LOAD && !next.done; i++) {
    const until = next.reached;
    const since = Math.max(floor, until - WINDOW_MS);
    const got = await home.clips(since, until);
    const seen = new Set(next.clips.map((c) => `${c.backend}:${c.id}`));
    const fresh = got.clips.filter((c) => !seen.has(`${c.backend}:${c.id}`));
    next = {
      clips: [...next.clips, ...fresh].sort((a, b) => b.at - a.at),
      reached: since,
      problems: union(next.problems, got.problems),
      noPlan: union(next.noPlan, got.noPlan),
      done: since <= floor,
    };
    if (fresh.length > 0) break;
  }
  return next;
}

const KIND_WORDS: Record<DeviceClip['kind'], string> = { ring: 'Ring', motion: 'Motion', person: 'A person', live: 'Live view', other: 'Recording' };
export const clipKindWords = (c: DeviceClip) => KIND_WORDS[c.kind];

const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const sameDay = (a: Date, b: Date) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();

/** "Today 3:42 PM", "Yesterday 9:05 AM", "Mon Nov 16 3:42 PM", in the phone's time zone. */
export function clipTime(at: number, now: number): string {
  const d = new Date(at);
  const today = new Date(now);
  const yesterday = new Date(now - 86_400_000);
  const h = d.getHours() % 12 || 12;
  const clock = `${h}:${String(d.getMinutes()).padStart(2, '0')} ${d.getHours() < 12 ? 'AM' : 'PM'}`;
  if (sameDay(d, today)) return `Today ${clock}`;
  if (sameDay(d, yesterday)) return `Yesterday ${clock}`;
  return `${DAYS[d.getDay()]} ${MONTHS[d.getMonth()]} ${d.getDate()} ${clock}`;
}

/** The length when the service gave one ("0:23"); Blink gives none, so nothing. */
export const clipLength = (c: DeviceClip): string | null =>
  c.durationSec === undefined ? null : `${Math.floor(c.durationSec / 60)}:${String(c.durationSec % 60).padStart(2, '0')}`;

/** Said once above the list for a service that keeps no recordings for this account. Never an error. */
export const noPlanText = (name: string) => (name === 'Ring' ? "Ring keeps recordings only with a Ring Protect plan, and this account doesn't have one." : `${name} has no recordings for this account.`);
