import { AppError, isAbortError, toAppError } from '../core/errors';
import { log } from '../core/log';
import { Store } from '../core/store';
import type { ChatRepository, MessageSource } from '../data/chatRepository';
import type { OllamaClient } from '../ollama/client';
import type { ModelSelection } from '../settings/modelSelection';
import type { SettingsRepository } from '../settings/settings';
import { buildContext } from './contextBuilder';
import type { PromptBuilder } from './promptBuilder';
import { systemClock, type Clock } from '../core/clock';
import { ToolRegistry } from '../tools/framework/toolRegistry';
import { runToolTurn } from '../tools/framework/toolRunner';

export type EngineState =
  | { kind: 'idle' }
  | { kind: 'working'; conversationId: number; label: string; partialText: string }
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
  send(conversationId: number, text: string, source: MessageSource = 'typed'): Promise<void> {
    if (!text.trim() || this.busy) return Promise.resolve();
    this.begin(conversationId);
    this.turn = this.run(conversationId, text, source, false);
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
    this.activeId = null;
    this.abort = null;
    this.flush();
    this.state.set(next);
  }

  private publishWorking(conversationId: number, label: string, partialText: string) {
    const publish = () => this.state.set({ kind: 'working', conversationId, label, partialText });
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
  ): Promise<void> {
    const { repo, listeners } = this.deps;
    let modelName: string | null = null;
    let partial = '';
    let label = 'Edith is thinking…';
    try {
      this.state.set({ kind: 'working', conversationId, label: 'Edith is thinking…', partialText: '' });
      if (regenerate && replaceId !== undefined) await repo.deleteMessage(replaceId);
      if (userText !== null) {
        const id = await repo.addUserMessage(conversationId, userText, source);
        if (id === null) return this.finish(); // the conversation was deleted
      }
      const model = await this.deps.selection.ensureModel();
      modelName = model.name;
      const settings = await this.deps.settings.current();
      const history = await repo.recent(conversationId, 200);
      const messages = buildContext(history, this.deps.prompts.system(model));

      const turn = await runToolTurn({
        client: this.deps.client,
        baseUrl: settings.serverUrl,
        model,
        messages,
        registry: this.deps.tools ?? new ToolRegistry([]),
        toolContext: { clock: this.deps.clock ?? systemClock, signal: this.abort?.signal },
        signal: this.abort?.signal,
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
      const text = turn.text.trim();
      if (!text) throw AppError.emptyReply();
      const messageId = await repo.addAssistantMessage(conversationId, text, model.name, turn.toolsUsed);
      this.finish();
      listeners.forEach((l) => l.onFinished?.(conversationId, text, messageId));
    } catch (e) {
      if (this.cancelling || isAbortError(e)) {
        if (partial.trim()) await repo.addCancelledAssistantMessage(conversationId, partial, modelName);
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
