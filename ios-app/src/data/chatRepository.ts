import { AppError } from '../core/errors';
import type { Clock } from '../core/clock';
import { log } from '../core/log';
import type { Db } from './db';
import { firstMessageTitle } from './conversationTitle';

export type MessageRole = 'user' | 'assistant';
export type MessageStatus = 'complete' | 'failed' | 'cancelled';
export type MessageSource = 'typed' | 'dictated' | 'voice_memo' | 'hands_free';
export type TitleSource = 'none' | 'first_message' | 'edith' | 'owner';

export interface StoredMessage {
  id: number;
  conversationId: number;
  role: MessageRole;
  content: string;
  createdAt: number;
  status: MessageStatus;
  source: MessageSource;
  model: string | null;
  toolsUsed: string[];
  errorMessage: string | null;
}
export interface StoredAttachment {
  id: number;
  messageId: number;
  /** The number the model sees in "[Attachment 3: name (image)]"; unique across all chats. */
  number: number;
  direction: 'from_user' | 'from_edith';
  kind: 'image' | 'audio' | 'file';
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  createdAt: number;
  removedAt: number | null;
  /** For a resend (C56): the attachment it shows again. It has no file of its own. */
  resendOf: number | null;
}
export interface NewAttachment {
  /** Who produced it. Default: the owner. */
  direction?: 'from_user' | 'from_edith';
  /** Shows an earlier attachment again (C56): no file of its own, not counted by retention. */
  resendOf?: number;
  kind: 'image' | 'audio' | 'file';
  fileName: string;
  mimeType: string;
  sizeBytes: number;
}

export interface Conversation {
  id: number;
  title: string;
  titleSource: TitleSource;
  createdAt: number;
  updatedAt: number;
}
export interface ConversationSummary {
  id: number;
  title: string;
  updatedAt: number;
  preview: string;
}

interface MessageRow {
  id: number;
  conversation_id: number;
  role: MessageRole;
  content: string;
  created_at: number;
  status: MessageStatus;
  source: MessageSource;
  model: string | null;
  tools_used: string | null;
  error_message: string | null;
}
interface AttachmentRow {
  id: number;
  message_id: number;
  number: number;
  direction: StoredAttachment['direction'];
  kind: StoredAttachment['kind'];
  file_name: string;
  mime_type: string;
  size_bytes: number;
  created_at: number;
  removed_at: number | null;
  resend_of: number | null;
}
const toAttachment = (r: AttachmentRow): StoredAttachment => ({
  id: r.id,
  messageId: r.message_id,
  number: r.number,
  direction: r.direction,
  kind: r.kind,
  fileName: r.file_name,
  mimeType: r.mime_type,
  sizeBytes: r.size_bytes,
  createdAt: r.created_at,
  removedAt: r.removed_at,
  resendOf: r.resend_of ?? null,
});

interface ConversationRow {
  id: number;
  title: string;
  title_source: TitleSource;
  created_at: number;
  updated_at: number;
}

const TITLE_RANK: Record<TitleSource, number> = { none: 0, first_message: 1, edith: 2, owner: 3 };
const TOOL_CALL = /<tool_call>[\s\S]*?<\/tool_call>|<tool_call>[\s\S]*$/g;

/** Removes `<tool_call>…</tool_call>` text (and an unclosed one) from assistant text (04-lessons L3). */
export function stripToolCalls(text: string): string {
  const stripped = text.replace(TOOL_CALL, '');
  if (stripped !== text) log.warn('Chat', 'Removed <tool_call> text from a reply');
  return stripped;
}

function toMessage(r: MessageRow): StoredMessage {
  let tools: string[] = [];
  if (r.tools_used) {
    try {
      const parsed: unknown = JSON.parse(r.tools_used);
      if (Array.isArray(parsed)) tools = parsed.filter((t): t is string => typeof t === 'string');
    } catch {
      log.warn('Chat', 'Unreadable tools_used; treated as empty');
    }
  }
  return {
    id: r.id,
    conversationId: r.conversation_id,
    role: r.role,
    content: r.content,
    createdAt: r.created_at,
    status: r.status,
    source: r.source,
    model: r.model,
    toolsUsed: tools,
    errorMessage: r.error_message,
  };
}

/** Conversations and messages. Only user and assistant rows can be stored: there is no API for system or tool text. */
export class ChatRepository {
  private listeners = new Set<() => void>();

  constructor(
    private readonly db: Db,
    private readonly clock: Clock,
  ) {}

  /** Called after any change (the UI re-reads). Returns an unsubscribe function. */
  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }
  private changed() {
    this.listeners.forEach((l) => l());
  }

  // --- conversations ---------------------------------------------------------------------------------------

  async conversations(): Promise<ConversationSummary[]> {
    const rows = await this.db.all<{ id: number; title: string; updated_at: number; preview: string | null }>(
      `SELECT c.id, c.title, c.updated_at,
         (SELECT content FROM messages m WHERE m.conversation_id = c.id AND m.status = 'complete'
            ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS preview
       FROM conversations c ORDER BY c.updated_at DESC, c.id DESC`,
    );
    return rows.map((r) => ({
      id: r.id,
      title: r.title,
      updatedAt: r.updated_at,
      preview: (r.preview ?? '').replace(/\s+/g, ' ').trim().slice(0, 80),
    }));
  }

  async conversation(id: number): Promise<Conversation | null> {
    const r = await this.db.first<ConversationRow>('SELECT * FROM conversations WHERE id = ?', [id]);
    return r && { id: r.id, title: r.title, titleSource: r.title_source, createdAt: r.created_at, updatedAt: r.updated_at };
  }

  async createConversation(): Promise<number> {
    const now = this.clock.now();
    const res = await this.db.run(
      `INSERT INTO conversations (title, title_source, created_at, updated_at) VALUES ('', 'none', ?, ?)`,
      [now, now],
    );
    this.changed();
    return res.lastInsertRowId;
  }

  /** An owner rename: never overwritten by an automatic title. */
  async rename(id: number, title: string): Promise<void> {
    await this.setTitle(id, title.trim(), 'owner');
  }

  /** A lower-ranked source never replaces a higher one (owner > edith > first_message); first_message is set once. */
  async setTitle(id: number, title: string, source: TitleSource): Promise<boolean> {
    const rank = TITLE_RANK[source];
    const res = await this.db.run(
      `UPDATE conversations SET title = ?, title_source = ?
       WHERE id = ? AND (CASE title_source WHEN 'owner' THEN 3 WHEN 'edith' THEN 2 WHEN 'first_message' THEN 1 ELSE 0 END) < ?`,
      [title, source, id, rank + (source === 'first_message' ? 0 : 1)],
    );
    if (res.changes > 0) this.changed();
    return res.changes > 0;
  }

  async deleteConversation(id: number): Promise<void> {
    await this.db.run('DELETE FROM conversations WHERE id = ?', [id]);
    this.changed();
  }

  async isEmpty(id: number): Promise<boolean> {
    const r = await this.db.first<{ n: number }>('SELECT COUNT(*) AS n FROM messages WHERE conversation_id = ?', [id]);
    return (r?.n ?? 0) === 0;
  }

  async deleteEmptyExcept(keepId: number | null): Promise<void> {
    await this.db.run(
      `DELETE FROM conversations WHERE id <> ? AND NOT EXISTS (SELECT 1 FROM messages WHERE conversation_id = conversations.id)`,
      [keepId ?? -1],
    );
    this.changed();
  }

  // --- messages --------------------------------------------------------------------------------------------

  /** Oldest first. */
  async messages(conversationId: number): Promise<StoredMessage[]> {
    const rows = await this.db.all<MessageRow>(
      'SELECT * FROM messages WHERE conversation_id = ? ORDER BY created_at, id',
      [conversationId],
    );
    return rows.map(toMessage);
  }

  /** The newest `limit` messages, oldest first. */
  async recent(conversationId: number, limit: number): Promise<StoredMessage[]> {
    const rows = await this.db.all<MessageRow>(
      'SELECT * FROM messages WHERE conversation_id = ? ORDER BY created_at DESC, id DESC LIMIT ?',
      [conversationId, limit],
    );
    return rows.map(toMessage).reverse();
  }

  async latest(conversationId: number): Promise<StoredMessage | null> {
    const r = await this.db.first<MessageRow>(
      'SELECT * FROM messages WHERE conversation_id = ? ORDER BY created_at DESC, id DESC LIMIT 1',
      [conversationId],
    );
    return r && toMessage(r);
  }

  /** Returns the new id, or null when the conversation was deleted meanwhile (nothing is stored). */
  async addUserMessage(
    conversationId: number,
    text: string,
    source: MessageSource = 'typed',
    attachments: NewAttachment[] = [],
  ): Promise<number | null> {
    return this.insert(conversationId, 'user', text, 'complete', source, null, [], null, true, attachments);
  }

  /** The attachments of a conversation's messages, oldest first (C15). */
  async attachments(conversationId: number): Promise<StoredAttachment[]> {
    const rows = await this.db.all<AttachmentRow>(
      `SELECT a.* FROM attachments a JOIN messages m ON m.id = a.message_id
       WHERE m.conversation_id = ? ORDER BY a.id`,
      [conversationId],
    );
    return rows.map(toAttachment);
  }

  /** An attachment by the number the model sees in its label (unique across all chats); a resend is never the answer (C56). */
  async attachmentByNumber(n: number): Promise<StoredAttachment | null> {
    const r = await this.db.first<AttachmentRow>('SELECT * FROM attachments WHERE number = ? AND resend_of IS NULL', [n]);
    return r ? toAttachment(r) : null;
  }

  /** Attachments still on the phone for one direction, newest first (C16 §2). */
  async keptAttachments(direction: StoredAttachment['direction']): Promise<StoredAttachment[]> {
    const rows = await this.db.all<AttachmentRow>(
      `SELECT * FROM attachments WHERE direction = ? AND removed_at IS NULL AND resend_of IS NULL ORDER BY id DESC`,
      [direction],
    );
    return rows.map(toAttachment);
  }

  /** Marks attachments as removed by retention; their bytes are gone, the rows stay for the labels. */
  async markAttachmentsRemoved(ids: number[], at: number): Promise<void> {
    if (ids.length === 0) return;
    await this.db.run(`UPDATE attachments SET removed_at = ? WHERE id IN (${ids.map(() => '?').join(',')})`, [at, ...ids]);
    this.changed();
  }

  /** Edith's reply, with the pictures her tools produced as `from_edith` attachments (C49). */
  async addAssistantMessage(conversationId: number, text: string, model: string, toolsUsed: string[] = [], attachments: NewAttachment[] = []) {
    return this.insert(conversationId, 'assistant', stripToolCalls(text).trim(), 'complete', 'typed', model, toolsUsed, null, false, attachments.map((a) => ({ ...a, direction: 'from_edith' as const })));
  }

  /** A failed turn: no text, the error is kept for display. It is never sent back to the model. */
  async addFailedAssistantMessage(conversationId: number, model: string | null, error: AppError) {
    return this.insert(conversationId, 'assistant', '', 'failed', 'typed', model, [], error.message, false);
  }

  /** A reply the owner stopped: the partial text, shown but never sent as context. */
  async addCancelledAssistantMessage(conversationId: number, text: string, model: string | null) {
    return this.insert(conversationId, 'assistant', stripToolCalls(text).trim(), 'cancelled', 'typed', model, [], null, false);
  }

  /** Deletes a message and returns the ids of its attachments, so their files can go too (C49). */
  async deleteMessage(id: number): Promise<number[]> {
    const ids = (await this.db.all<{ id: number }>('SELECT id FROM attachments WHERE message_id = ?', [id])).map((r) => r.id);
    await this.db.run('DELETE FROM messages WHERE id = ?', [id]);
    this.changed();
    return ids;
  }

  async clearConversation(conversationId: number): Promise<number[]> {
    const ids = (
      await this.db.all<{ id: number }>('SELECT a.id FROM attachments a JOIN messages m ON m.id = a.message_id WHERE m.conversation_id = ?', [conversationId])
    ).map((r) => r.id);
    await this.db.run('DELETE FROM messages WHERE conversation_id = ?', [conversationId]);
    this.changed();
    return ids;
  }

  private async insert(
    conversationId: number,
    role: MessageRole,
    content: string,
    status: MessageStatus,
    source: MessageSource,
    model: string | null,
    toolsUsed: string[],
    errorMessage: string | null,
    autoTitle: boolean,
    attachments: NewAttachment[] = [],
  ): Promise<number | null> {
    const now = this.clock.now();
    try {
      const id = await this.db.transaction(async () => {
        const conv = await this.db.first<ConversationRow>('SELECT * FROM conversations WHERE id = ?', [conversationId]);
        if (!conv) return null;
        const res = await this.db.run(
          `INSERT INTO messages (conversation_id, role, content, created_at, status, source, model, tools_used, error_message)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
          [conversationId, role, content, now, status, source, model, toolsUsed.length ? JSON.stringify(toolsUsed) : null, errorMessage],
        );
        await this.db.run('UPDATE conversations SET updated_at = ? WHERE id = ?', [now, conversationId]);
        if (autoTitle && conv.title_source === 'none' && content.trim()) {
          await this.db.run(`UPDATE conversations SET title = ?, title_source = 'first_message' WHERE id = ?`, [
            firstMessageTitle(content),
            conversationId,
          ]);
        }
        for (const a of attachments) {
          const next = await this.db.first<{ n: number }>('SELECT COALESCE(MAX(number), 0) + 1 AS n FROM attachments');
          await this.db.run(
            `INSERT INTO attachments (message_id, number, direction, kind, file_name, mime_type, size_bytes, created_at, resend_of)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
            [res.lastInsertRowId, next?.n ?? 1, a.direction ?? 'from_user', a.kind, a.fileName, a.mimeType, a.sizeBytes, now, a.resendOf ?? null],
          );
        }
        return res.lastInsertRowId;
      });
      if (id === null) log.warn('Chat', `Conversation ${conversationId} no longer exists; nothing stored`);
      else this.changed();
      return id;
    } catch (e) {
      if (e instanceof Error && /FOREIGN KEY/i.test(e.message)) {
        log.warn('Chat', `Conversation ${conversationId} was deleted mid-turn; nothing stored`);
        return null;
      }
      throw e;
    }
  }
}
