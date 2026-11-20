import type { Clock } from '../core/clock';
import type { ChatRepository } from '../data/chatRepository';
import type { AttachmentStore } from './attachments';

export interface RetentionDeps {
  repo: ChatRepository;
  store: AttachmentStore;
  clock: Pick<Clock, 'now'>;
}

/** The owner's attachments beyond the newest `keep`: what retention would remove (C16 §2, counted across all chats). */
export async function beyondKeep(repo: ChatRepository, keep: number) {
  return (await repo.keptAttachments('from_user')).slice(keep);
}

/**
 * Keeps the newest `keep` of the owner's pictures on the phone and removes the rest (files and bytes; the rows stay
 * with `removed_at`, so the chat says "no longer available"). With `keep` 0 nothing is kept across runs of the app, but
 * a picture just sent must stay visible, so that case is only applied `atStartup`.
 */
export async function applyRetention(deps: RetentionDeps, keep: number, atStartup = false): Promise<number> {
  if (keep === 0 && !atStartup) return 0;
  const doomed = await beyondKeep(deps.repo, keep);
  if (doomed.length === 0) return 0;
  const ids = doomed.map((a) => a.id);
  await deps.store.forget(ids);
  await deps.repo.markAttachmentsRemoved(ids, deps.clock.now());
  return ids.length;
}

/** Removes every kept picture now ("Delete all attachments now"). */
export async function deleteAllAttachments(deps: RetentionDeps): Promise<number> {
  const ids = (await deps.repo.keptAttachments('from_user')).map((a) => a.id);
  await deps.store.forget(ids);
  await deps.repo.markAttachmentsRemoved(ids, deps.clock.now());
  return ids.length;
}

/** At startup: files that no kept row points to (a deleted chat, a crash) are deleted. */
export async function removeOrphans(deps: RetentionDeps): Promise<number> {
  const keptIds = new Set((await deps.repo.keptAttachments('from_user')).map((a) => a.id));
  const orphans = (await deps.store.listFiles()).filter((id) => !keptIds.has(id));
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
