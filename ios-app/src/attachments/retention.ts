import type { Clock } from '../core/clock';
import type { ChatRepository, StoredAttachment } from '../data/chatRepository';
import type { AttachmentStore } from './attachments';

export interface RetentionDeps {
  repo: ChatRepository;
  store: AttachmentStore;
  clock: Pick<Clock, 'now'>;
}

/** O1 (06-decisions): the newest N of each direction are kept, counted across all chats. Resends never count (they have no file). */
export const DIRECTIONS = ['from_user', 'from_edith'] as const;

/** Attachments of one direction beyond the newest `keep`: what retention would remove. */
export async function beyondKeep(repo: ChatRepository, keep: number, direction: StoredAttachment['direction'] = 'from_user') {
  return (await repo.keptAttachments(direction)).slice(keep);
}

/** What would be removed in both directions if the number were `keep` (for "This will delete N older attachments"). */
export async function countBeyondKeep(repo: ChatRepository, keep: number): Promise<number> {
  let n = 0;
  for (const d of DIRECTIONS) n += (await beyondKeep(repo, keep, d)).length;
  return n;
}

/**
 * Keeps the newest `keep` attachments of each direction on the phone and removes the rest (files and bytes; the rows stay
 * with `removed_at`, so the chat says "no longer available"). With `keep` 0 nothing is kept across runs of the app, but
 * a picture just sent or shown must stay visible, so that case is only applied `atStartup`.
 */
export async function applyRetention(deps: RetentionDeps, keep: number, atStartup = false): Promise<number> {
  if (keep === 0 && !atStartup) return 0;
  let removed = 0;
  for (const direction of DIRECTIONS) {
    const doomed = await beyondKeep(deps.repo, keep, direction);
    if (doomed.length === 0) continue;
    const ids = doomed.map((a) => a.id);
    await deps.store.forget(ids);
    await deps.repo.markAttachmentsRemoved(ids, deps.clock.now());
    removed += ids.length;
  }
  return removed;
}

/** Removes every kept attachment, both directions, now ("Delete all attachments now"). */
export async function deleteAllAttachments(deps: RetentionDeps): Promise<number> {
  let removed = 0;
  for (const direction of DIRECTIONS) {
    const ids = (await deps.repo.keptAttachments(direction)).map((a) => a.id);
    await deps.store.forget(ids);
    await deps.repo.markAttachmentsRemoved(ids, deps.clock.now());
    removed += ids.length;
  }
  return removed;
}

/** At startup: files that no kept row of either direction points to (a deleted chat, a crash) are deleted. */
export async function removeOrphans(deps: RetentionDeps): Promise<number> {
  const kept = new Set<number>();
  for (const direction of DIRECTIONS) for (const a of await deps.repo.keptAttachments(direction)) kept.add(a.id);
  const orphans = (await deps.store.listFiles()).filter((id) => !kept.has(id));
  await deps.store.forget(orphans);
  return orphans.length;
}

export const MAX_KEEP = 50;

/** "12 photos, 8.4 MB" */
export function usageText(count: number, bytes: number): string {
  if (count === 0) return 'No photos kept';
  const mb = bytes / 1_000_000;
  return `${count} photo${count === 1 ? '' : 's'}, ${mb < 0.1 ? '<0.1' : mb.toFixed(1)} MB`;
}
