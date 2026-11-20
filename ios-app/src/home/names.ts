import type { HomeDevice } from './model';

export type Match = { kind: 'found'; device: HomeDevice } | { kind: 'none' } | { kind: 'ambiguous'; candidates: HomeDevice[] };

const norm = (s: string) =>
  s
    .toLowerCase()
    .replace(/\(.*?\)\s*$/, '') // a trailing "(Light)" a model copied from the list
    .replace(/[^a-z0-9 ]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();

/**
 * C25 §3 (Jarvis's matchDevice, plus rooms): exact name; then "room name" or "name in room"; then the only name
 * containing the words. Nothing fuzzier. More than one candidate at any step is ambiguous.
 */
export function matchDevice(devices: HomeDevice[], query: string): Match {
  const q = norm(query);
  if (!q) return { kind: 'none' };
  const pick = (found: HomeDevice[]): Match | null =>
    found.length === 1 ? { kind: 'found', device: found[0] } : found.length > 1 ? { kind: 'ambiguous', candidates: found } : null;

  const exact = pick(devices.filter((d) => norm(d.name) === q));
  if (exact) return exact;

  const withRoom = pick(
    devices.filter((d) => {
      const n = norm(d.name);
      const r = norm(d.room);
      return r !== '' && (q === `${r} ${n}` || q === `${n} in ${r}` || q === `${n} in the ${r}` || q === `the ${r} ${n}`);
    }),
  );
  if (withRoom) return withRoom;

  const words = q.split(' ').filter((w) => w !== 'the');
  const contains = devices.filter((d) => {
    const hay = `${norm(d.room)} ${norm(d.name)}`;
    return words.length > 0 && words.every((w) => hay.includes(w));
  });
  return pick(contains) ?? { kind: 'none' };
}
