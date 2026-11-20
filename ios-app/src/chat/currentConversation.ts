import { Store } from '../core/store';
import type { ChatRepository } from '../data/chatRepository';
import type { SettingsRepository } from '../settings/settings';
import type { ChatEngine } from './engine';

/** The chat the app is showing; reopened on the last one (C06b §4). */
export class CurrentConversation {
  /** null only until `resolve()` has finished. */
  readonly id = new Store<number | null>(null);

  constructor(
    private readonly repo: ChatRepository,
    private readonly settings: SettingsRepository,
    private readonly engine: ChatEngine,
  ) {}

  /** At startup: the saved chat if it still exists, else the most recently updated, else a new one. Prunes empty chats. */
  async resolve(): Promise<number> {
    const saved = (await this.settings.current()).currentConversationId;
    let id: number | null = null;
    if (saved !== null && (await this.repo.conversation(saved))) id = saved;
    if (id === null) id = (await this.repo.conversations())[0]?.id ?? null;
    if (id === null) id = await this.repo.createConversation();
    await this.repo.deleteEmptyExcept(id);
    await this.set(id);
    return id;
  }

  async select(id: number): Promise<void> {
    await this.set(id);
  }

  /** Reuses the current conversation when it's already empty. */
  async newChat(): Promise<number> {
    const current = this.id.get();
    if (current !== null && (await this.repo.isEmpty(current))) return current;
    const id = await this.repo.createConversation();
    await this.set(id);
    return id;
  }

  /** Stops a reply running there first, deletes, then opens the most recent remaining chat (or a new one). */
  async delete(id: number): Promise<void> {
    await this.engine.cancelAndJoin(id);
    await this.repo.deleteConversation(id);
    if (this.id.get() === id) {
      const next = (await this.repo.conversations())[0]?.id ?? (await this.repo.createConversation());
      await this.set(next);
    }
  }

  private async set(id: number) {
    this.id.set(id);
    await this.settings.update((s) => ({ ...s, currentConversationId: id }));
  }
}
