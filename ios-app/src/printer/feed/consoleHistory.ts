/** The lines the owner typed into the console, newest first, at most 50 (kept between visits). */
export const ConsoleHistory = {
  MAX: 50,
  /** `line` goes to the front; the same line sent again moves to the front instead of repeating; the list stays at 50. */
  push(history: string[], line: string): string[] {
    const t = line.trim();
    if (!t) return history;
    return [t, ...history.filter((h) => h !== t)].slice(0, ConsoleHistory.MAX);
  },
  /** Where the up arrow goes from `index` (-1 means "nothing chosen yet"): one older, stopping at the oldest. */
  older: (history: string[], index: number): number => (history.length === 0 ? -1 : Math.min(index + 1, history.length - 1)),
  /** Where the down arrow goes: one newer; past the newest it is -1, which is an empty box again. */
  newer: (index: number): number => (index <= 0 ? -1 : index - 1),
  /** The text at `index`, or "" for -1. */
  at: (history: string[], index: number): string => history[index] ?? '',
};
