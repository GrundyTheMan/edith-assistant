/**
 * How the Printer page writes numbers and times (C39 §8.2, C67 E4; ported from Android's `PrinterFormat.kt`): the rules of Jarvis's own page, copied. Pure functions; the clock
 * and the 12/24-hour choice are passed in so they can be tested. File and job titles are never worked out here: use what Jarvis sends.
 */
export const NONE = '—';
export const NOT_ENOUGH_PRINTED = 'Not enough printed yet to tell.';

const bad = (n: number | null | undefined): n is null | undefined => n === null || n === undefined || Number.isNaN(n) || n < 0;

/** 3 700 s → "1h 1m"; 3 600 s → "1h"; 3 120 s → "52m"; 45 s → "45s"; unknown or negative → "—". Minutes round down. */
export function duration(seconds: number | null | undefined): string {
  if (bad(seconds)) return NONE;
  const total = Math.floor(seconds);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  if (hours >= 1) return minutes === 0 ? `${hours}h` : `${hours}h ${minutes}m`;
  if (total >= 60) return `${minutes}m`;
  return `${total}s`;
}

/** "about 1h 14m": always "about", because it is an estimate. Unknown → "—". */
export const timeLeft = (seconds: number | null | undefined): string => (bad(seconds) ? NONE : `about ${duration(seconds)}`);

/** Where the estimate came from, for the little note under the time left. */
export function timeLeftSource(from: string | null | undefined): string | null {
  if (from === 'slicer') return 'Estimated from the slicer';
  if (from === 'progress') return 'Estimated from the time printed and the progress';
  return null;
}

/** Whether this phone shows 24-hour times (its own setting, read from the locale's formatting). */
export function phoneUses24Hour(): boolean {
  try {
    return !/AM|PM/i.test(new Date(2020, 0, 1, 13, 0).toLocaleTimeString());
  } catch {
    return false;
  }
}

/** "about 14:32" in the phone's own zone, 24 hours or 12 as the phone does. Unknown → "—". */
export function eta(nowMs: number, timeLeftSeconds: number | null | undefined, is24Hour: boolean): string {
  if (bad(timeLeftSeconds)) return NONE;
  const d = new Date(nowMs + Math.floor(timeLeftSeconds) * 1000);
  const m = String(d.getMinutes()).padStart(2, '0');
  if (is24Hour) return `about ${String(d.getHours()).padStart(2, '0')}:${m}`;
  return `about ${d.getHours() % 12 || 12}:${m} ${d.getHours() < 12 ? 'AM' : 'PM'}`;
}

const decimals = (v: number, places: number) => v.toFixed(places);
/** A temperature reading: one decimal, "219.8 °C". */
export const temperature = (value: number): string => `${decimals(value, 1)} °C`;
/** A target: a whole number, "220 °C"; 0 is "off". */
export const target = (value: number): string => (value <= 0 ? 'off' : `${Math.round(value)} °C`);
/** A position in millimetres, two decimals. */
export const position = (value: number): string => decimals(value, 2);
/** Filament used, from millimetres to metres with one decimal: 12 400 → "12.4 m". */
export const filament = (mm: number): string => `${decimals(mm / 1000, 1)} m`;
/** 0.4 → "40 %". The page writes a space before the sign. */
export const percent = (fraction: number): string => `${Math.round(fraction * 100)} %`;
/** A speed factor, 1.0 → "100 %". */
export const factor = percent;

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const dayNumber = (ms: number) => {
  const d = new Date(ms);
  return Math.floor(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86_400_000);
};

/** "2 min ago", "3 h ago", "yesterday", "4 days ago", then a short date for a week or more. Future times read "just now". */
export function age(thenMs: number, nowMs: number): string {
  const seconds = Math.floor((nowMs - thenMs) / 1000);
  if (seconds < 60) return 'just now';
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  const days = dayNumber(nowMs) - dayNumber(thenMs);
  if (days <= 0) return `${Math.floor(seconds / 3600)} h ago`;
  if (days === 1) return 'yesterday';
  if (days < 7) return `${days} days ago`;
  const d = new Date(thenMs);
  return `${MONTHS[d.getMonth()]} ${d.getDate()}`;
}

/** The time left of the Controls, "1:43", from the server's `until` and the clock offset (the phone's clock may be wrong). */
export function countdown(untilMs: number, nowMs: number, offsetMs: number): string {
  const left = Math.max(0, Math.floor((untilMs - (nowMs + offsetMs)) / 1000));
  return `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
}
