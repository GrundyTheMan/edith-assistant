/** Injected so tests can fix the time and the time zone (04-lessons L29: the UTC offset matters). */
export interface Clock {
  now(): number;
  /** IANA zone id, e.g. "America/Detroit". */
  zone(): string;
  /** Minutes east of UTC at `at` (Detroit in winter: -300). */
  offsetMinutes(at: number): number;
}

export const systemClock: Clock = {
  now: () => Date.now(),
  zone: () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
  offsetMinutes: (at) => -new Date(at).getTimezoneOffset(),
};
