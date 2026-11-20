/**
 * Logging. Conversation text (what the owner typed, what Edith said) is never logged in release builds, so
 * `sensitive` prints only when `__DEV__`. `info` and `warn` carry no conversation text, by convention.
 */
declare const __DEV__: boolean;

const dev = typeof __DEV__ !== 'undefined' && __DEV__;
/** Quiet under Jest. */
const quiet = typeof process !== 'undefined' && !!process.env?.JEST_WORKER_ID;

export const log = {
  info: (tag: string, message: string) => !quiet && console.log(`Edith/${tag}: ${message}`),
  warn: (tag: string, message: string) => !quiet && console.warn(`Edith/${tag}: ${message}`),
  /** Anything that may hold conversation text. Development builds only. */
  sensitive: (tag: string, message: string) => {
    if (dev && !quiet) console.log(`Edith/${tag}: ${message}`);
  },
};
