import { log } from '../core/log';
import type { ChatRepository } from '../data/chatRepository';
import { cleanEdithTitle } from '../data/conversationTitle';
import type { OllamaClient } from '../ollama/client';
import type { SettingsRepository } from '../settings/settings';
import type { ReplyListener } from './engine';

const TITLE_SYSTEM = 'You write short titles for chats. Reply with only a title of 2 to 6 words that describes the conversation. No quotes, no punctuation at the end.';

/**
 * D12: Edith writes the title. After a reply finishes in a chat with no Edith or owner title, one non-streaming call
 * (same model) makes a title off the turn. One attempt per chat per app run; on failure the first-message title stays.
 */
export class ConversationTitler implements ReplyListener {
  private tried = new Set<number>();

  constructor(
    private readonly repo: ChatRepository,
    private readonly client: OllamaClient,
    private readonly settings: SettingsRepository,
  ) {}

  onFinished(conversationId: number, text: string): void {
    void this.title(conversationId, text);
  }

  async title(conversationId: number, reply: string): Promise<void> {
    if (this.tried.has(conversationId)) return;
    try {
      const conv = await this.repo.conversation(conversationId);
      if (!conv || conv.titleSource === 'edith' || conv.titleSource === 'owner') return;
      this.tried.add(conversationId);
      const { serverUrl, model } = await this.settings.current();
      if (!model) return;
      const history = await this.repo.recent(conversationId, 2);
      const firstUser = history.find((m) => m.role === 'user')?.content ?? '';
      const message = await this.client.chat(serverUrl, {
        model,
        messages: [
          { role: 'system', content: TITLE_SYSTEM },
          { role: 'user', content: `Owner: ${firstUser.slice(0, 500)}\nEdith: ${reply.slice(0, 500)}` },
        ],
      });
      const title = cleanEdithTitle(message.content);
      if (title) await this.repo.setTitle(conversationId, title, 'edith');
    } catch (e) {
      log.warn('Titler', `No title this time: ${e instanceof Error ? e.name : 'error'}`);
    }
  }
}
