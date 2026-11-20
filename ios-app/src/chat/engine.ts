import { base64Bytes, type AttachmentStore, type PendingAttachment } from '../attachments/attachments';
import { utf8Bytes } from '../core/md5';
import type { NewAttachment } from '../data/chatRepository';
import { filePlaceholder, mimeForName, parseReply, plainReply } from './replyFormat';
import { AppError, isAbortError, toAppError } from '../core/errors';
import { log } from '../core/log';
import { Store } from '../core/store';
import type { ChatRepository, MessageSource } from '../data/chatRepository';
import type { OllamaClient } from '../ollama/client';
import type { ModelSelection } from '../settings/modelSelection';
import type { SettingsRepository } from '../settings/settings';
import { canSee } from '../ollama/types';
import { buildContext } from './contextBuilder';
import type { PromptBuilder } from './promptBuilder';
import { systemClock, type Clock } from '../core/clock';
import { ToolRegistry } from '../tools/framework/toolRegistry';
import type { ConfirmationRequest } from '../tools/framework/tool';
import { runToolTurn } from '../tools/framework/toolRunner';

export type EngineState =
  | { kind: 'idle' }
  | { kind: 'working'; conversationId: number; label: string; partialText: string; confirmation?: ConfirmationRequest }
  | { kind: 'failed'; conversationId: number; error: AppError };

/** Hears a reply as it streams (spoken replies, titles). Every callback names its conversation. */
export interface ReplyListener {
  onDelta?(conversationId: number, text: string): void;
  onFinished?(conversationId: number, text: string, messageId: number | null): void;
  /** Text said before a tool call was dropped; it is not part of the reply. */
  onTextReset?(conversationId: number): void;
  onCancelled?(conversationId: number): void;
  onFailed?(conversationId: number): void;
}

export interface EngineDeps {
  repo: ChatRepository;
  settings: SettingsRepository;
  selection: ModelSelection;
  client: OllamaClient;
  prompts: PromptBuilder;
  listeners: ReplyListener[];
  /** What Edith can do (C41). Empty or missing: a plain chat. */
  tools?: ToolRegistry;
  /** Where sent pictures are held for this run of the app (C15). */
  attachments?: AttachmentStore;
  /** Runs after a message with pictures is stored (retention, C16 §2). Failures are ignored. */
  afterAttachmentsSent?: () => Promise<void>;
  clock?: Clock;
  /** Minimum time between UI-facing updates while text streams (~30/s). 0 in tests. */
  throttleMs?: number;
  now?: () => number;
}

/**
 * The brain of a chat turn (C06 + C06b): persist the owner's message, build the context, stream the reply, persist
 * the result. The engine lives for the whole app, not for a screen, so a turn survives navigating away. One turn at
 * a time across all conversations (one Ollama model); every read and write of a turn uses the conversation id
 * captured when it started, even if the owner switches chats meanwhile.
 */
export class ChatEngine {
  readonly state = new Store<EngineState>({ kind: 'idle' });
  private busy = false;
  private activeId: number | null = null;
  private abort: AbortController | null = null;
  private cancelling = false;
  private turn: Promise<void> = Promise.resolve();
  private readonly throttleMs: number;
  private readonly now: () => number;
  private lastPublish = 0;
  private pending: (() => void) | null = null;
  /** The approval the turn is waiting for, if any (C08 §6). */
  private awaiting: { request: ConfirmationRequest; settle: (approved: boolean) => void } | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly deps: EngineDeps) {
    this.throttleMs = deps.throttleMs ?? 33;
    this.now = deps.now ?? Date.now;
  }

  get activeConversationId(): number | null {
    return this.activeId;
  }
  get isBusy(): boolean {
    return this.busy;
  }

  /** Sends the owner's message and runs the turn. The returned promise resolves when the turn has ended. */
  send(conversationId: number, text: string, source: MessageSource = 'typed', attachments: PendingAttachment[] = []): Promise<void> {
    if ((!text.trim() && attachments.length === 0) || this.busy) return Promise.resolve();
    this.begin(conversationId);
    this.turn = this.run(conversationId, text, source, false, undefined, attachments);
    return this.turn;
  }

  /** Only when idle and the newest message is Edith's: drops it and runs the turn again from the history. */
  async regenerateLast(conversationId: number): Promise<void> {
    if (this.busy) return;
    this.begin(conversationId);
    const latest = await this.deps.repo.latest(conversationId);
    if (!latest || latest.role !== 'assistant') {
      this.finish();
      return;
    }
    this.turn = this.run(conversationId, null, 'typed', true, latest.id);
    return this.turn;
  }

  /** The owner's answer to the card in the reply bubble; ignored when no such request is waiting. */
  resolveConfirmation(id: string, approved: boolean): void {
    const a = this.awaiting;
    if (!a || a.request.id !== id) return;
    this.awaiting = null;
    a.settle(approved);
  }

  /** Waits for the owner; a cancelled turn rejects, so nothing is run. */
  private askOwner(conversationId: number, label: () => string, partial: () => string, request: ConfirmationRequest): Promise<boolean> {
    const signal = this.abort?.signal;
    return new Promise<boolean>((resolve, reject) => {
      const cleanup = () => signal?.removeEventListener('abort', onAbort);
      const onAbort = () => {
        this.awaiting = null;
        reject(Object.assign(new Error('Aborted'), { name: 'AbortError' }));
      };
      if (signal?.aborted) return onAbort();
      signal?.addEventListener('abort', onAbort);
      this.awaiting = {
        request,
        settle: (approved) => {
          cleanup();
          this.publishWorking(conversationId, label(), partial());
          resolve(approved);
        },
      };
      this.publishWorking(conversationId, label(), partial());
    });
  }

  /** Stops the running reply (and Ollama's generation); partial text is kept as a "stopped" message. */
  cancel(): void {
    if (!this.busy || !this.abort) return;
    this.cancelling = true;
    this.abort.abort();
  }

  /** Cancels the turn if it belongs to `conversationId` and waits until its cancelled message is stored. */
  async cancelAndJoin(conversationId: number): Promise<void> {
    if (this.busy && this.activeId === conversationId) {
      this.cancel();
      await this.turn;
    }
  }

  private begin(conversationId: number) {
    this.busy = true; // synchronously, so a double tap can't start two turns
    this.activeId = conversationId;
    this.cancelling = false;
    this.abort = new AbortController();
  }

  /** The turn is released *before* Idle/Failed is published, so a send made the moment a reply ends is accepted. */
  private finish(next: EngineState = { kind: 'idle' }) {
    this.busy = false;
    this.awaiting = null;
    this.activeId = null;
    this.abort = null;
    this.flush();
    this.state.set(next);
  }

  private publishWorking(conversationId: number, label: string, partialText: string) {
    const publish = () =>
      this.state.set({
        kind: 'working',
        conversationId,
        label,
        partialText,
        ...(this.awaiting ? { confirmation: this.awaiting.request } : {}),
      });
    const elapsed = this.now() - this.lastPublish;
    if (this.throttleMs === 0 || elapsed >= this.throttleMs) {
      this.flush();
      this.lastPublish = this.now();
      publish();
    } else {
      this.pending = publish;
      if (!this.timer) {
        this.timer = setTimeout(() => {
          this.timer = null;
          const p = this.pending;
          this.pending = null;
          if (p) {
            this.lastPublish = this.now();
            p();
          }
        }, this.throttleMs - elapsed);
      }
    }
  }

  private flush() {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.pending = null;
  }

  private async run(
    conversationId: number,
    userText: string | null,
    source: MessageSource,
    regenerate: boolean,
    replaceId?: number,
    attachments: PendingAttachment[] = [],
  ): Promise<void> {
    const { repo, listeners } = this.deps;
    let modelName: string | null = null;
    let partial = '';
    let label = 'Edith is thinking…';
    try {
      this.state.set({ kind: 'working', conversationId, label: 'Edith is thinking…', partialText: '' });
      if (regenerate && replaceId !== undefined) {
        const gone = await repo.deleteMessage(replaceId); // its pictures' files go with it (C49)
        await this.deps.attachments?.forget(gone);
      }
      if (userText !== null) {
        const id = await repo.addUserMessage(
          conversationId,
          userText,
          source,
          attachments.map((a) => ({ kind: a.kind, fileName: a.fileName, mimeType: a.mimeType, sizeBytes: a.sizeBytes })),
        );
        if (id === null) return this.finish(); // the conversation was deleted
        if (attachments.length > 0) {
          // The rows were numbered in order; hand each picture to the store under its row id.
          const rows = (await repo.attachments(conversationId)).filter((r) => r.messageId === id);
          await Promise.all(rows.map((row, i) => this.deps.attachments?.put(row.id, attachments[i].base64)));
          await this.deps.afterAttachmentsSent?.().catch(() => {});
        }
      }
      const model = await this.deps.selection.ensureModel();
      modelName = model.name;
      const settings = await this.deps.settings.current();
      const history = await repo.recent(conversationId, 200);
      const stored = await repo.attachments(conversationId);
      const store = this.deps.attachments;
      // After a restart the pictures are on disk, not in memory: load the ones the context window can use.
      await store?.hydrate([...new Set(stored.filter((a) => !a.removedAt).slice(-20).map((a) => a.resendOf ?? a.id))]);
      // Pictures to a model that can't see them: don't call it (C15 §3). Only the newest message is checked.
      const newest = [...history].reverse().find((m) => m.role === 'user');
      if (newest && stored.some((a) => a.messageId === newest.id && a.kind === 'image' && !a.removedAt) && !canSee(model)) {
        throw AppError.lacksCapability(model.name, 'images');
      }
      const messages = buildContext(history, this.deps.prompts.system(model), undefined, {
        attachments: stored,
        image: (id) => store?.image(id) ?? null,
        fileText: (id) => store?.fileText(id) ?? null,
        canSee: canSee(model),
      });

      const turn = await runToolTurn({
        client: this.deps.client,
        baseUrl: settings.serverUrl,
        model,
        messages,
        registry: this.deps.tools ?? new ToolRegistry([]),
        toolContext: { clock: this.deps.clock ?? systemClock, signal: this.abort?.signal },
        signal: this.abort?.signal,
        confirm: (request) => this.askOwner(conversationId, () => label, () => partial, request),
        hooks: {
          onDelta: (text) => {
            partial += text;
            this.publishWorking(conversationId, label, partial);
            listeners.forEach((l) => l.onDelta?.(conversationId, text));
          },
          // Words said before a tool call are not the answer: clear them, and the listeners (speech) drop them too.
          onTextReset: () => {
            partial = '';
            this.publishWorking(conversationId, label, '');
            listeners.forEach((l) => l.onTextReset?.(conversationId));
          },
          onStatus: (next) => {
            label = next;
            this.publishWorking(conversationId, label, partial);
          },
        },
      });
      // Her reply may hold files she wrote and attachments she sends back (C16 §3, C56): turn them into rows, and the text into placeholders.
      let text = '';
      const files: { name: string; content: string }[] = [];
      const resends: NewAttachment[] = [];
      for (const part of parseReply(turn.text)) {
        if (part.kind === 'text') text += part.text;
        else if (part.kind === 'file') {
          files.push({ name: part.name, content: part.content });
          text += filePlaceholder(part.name);
        } else {
          const original = await repo.attachmentByNumber(part.number);
          if (original && !original.removedAt) {
            resends.push({ kind: original.kind, fileName: original.fileName, mimeType: original.mimeType, sizeBytes: original.sizeBytes, resendOf: original.id });
          } else {
            text += `Attachment ${part.number} isn't available.`;
          }
        }
      }
      text = text.replace(/\n{3,}/g, '\n\n').trim();
      if (!text && files.length === 0 && resends.length === 0 && turn.images.length === 0) throw AppError.emptyReply();
      const rows: NewAttachment[] = [
        ...turn.images.map((i) => ({ kind: 'image' as const, fileName: i.fileName, mimeType: i.mimeType, sizeBytes: base64Bytes(i.base64) })),
        ...files.map((f) => ({ kind: 'file' as const, fileName: f.name, mimeType: mimeForName(f.name), sizeBytes: utf8Bytes(f.content).length })),
        ...resends,
      ];
      const messageId = await repo.addAssistantMessage(conversationId, text, model.name, turn.toolsUsed, rows);
      if (messageId !== null && rows.length > 0) {
        // The rows were numbered in order: hand each one's bytes to the store under its row id (a resend has none), then retention.
        const stored = (await repo.attachments(conversationId)).filter((r) => r.messageId === messageId);
        await Promise.all(
          stored.map((row, i) => {
            if (i < turn.images.length) return this.deps.attachments?.put(row.id, turn.images[i].base64);
            const f = files[i - turn.images.length];
            return f ? this.deps.attachments?.putFile(row.id, f.content) : undefined;
          }),
        );
        await this.deps.afterAttachmentsSent?.().catch(() => {});
      }
      this.finish();
      listeners.forEach((l) => l.onFinished?.(conversationId, text, messageId));
    } catch (e) {
      if (this.cancelling || isAbortError(e)) {
        if (partial.trim()) await repo.addCancelledAssistantMessage(conversationId, plainReply(partial) || partial, modelName);
        this.finish();
        listeners.forEach((l) => l.onCancelled?.(conversationId));
        return;
      }
      const error = toAppError(e);
      log.warn('Engine', `Turn failed: ${error.kind}`);
      try {
        await repo.addFailedAssistantMessage(conversationId, modelName, error);
      } catch (storeError) {
        log.warn('Engine', `Couldn't store the failed reply: ${String(storeError)}`);
      }
      this.finish({ kind: 'failed', conversationId, error });
      listeners.forEach((l) => l.onFailed?.(conversationId));
    }
  }
}
