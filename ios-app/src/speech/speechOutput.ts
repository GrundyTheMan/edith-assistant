import { Store } from '../core/store';
import type { ReplyListener } from '../chat/engine';
import type { SettingsRepository } from '../settings/settings';
import { cleanForSpeech } from './cleanForSpeech';
import { SpeechFilter } from '../chat/replyFormat';
import { SentenceChunker } from './sentenceChunker';

/** The phone's voice (expo-speech in the app, a fake in tests). Utterances queue in the order they're spoken. */
export interface TtsEngine {
  speak(text: string, rate: number, handlers: { onDone(): void; onStopped(): void; onError(): void }): void;
  stop(): void;
}

/** What the chat UI's action row uses. */
export interface SpeechController {
  readonly speakingMessageId: Store<number | null>;
  play(messageId: number, text: string): void;
  stop(): void;
}

/** While a reply streams it has no message id yet (C13 As built). */
export const LIVE_REPLY = -1;
export const TEST_VOICE = -2;

interface Live {
  conversationId: number;
  chunker: SentenceChunker;
  /** Drops file blocks and tags as the reply streams (C56). */
  filter: SpeechFilter;
  /** Stopped, or speech is off: the rest of this reply is not spoken. */
  quiet: boolean;
}

/**
 * Spoken replies (C13): sentence by sentence while a reply streams, Play and Stop on stored messages. Every Play, Stop
 * and new reply starts a new "generation"; callbacks from older ones are ignored, so a late `onDone` from a stopped
 * utterance can't clear a newer one. Settings are read from the repository's snapshot, so nothing here is async.
 */
export class SpeechOutput implements SpeechController, ReplyListener {
  readonly speakingMessageId = new Store<number | null>(null);
  private generation = 0;
  private pending = 0;
  private live: Live | null = null;

  constructor(
    private readonly tts: TtsEngine,
    private readonly settings: SettingsRepository,
    /** Only the open conversation's reply is spoken. */
    private readonly isOpen: (conversationId: number) => boolean = () => true,
  ) {}

  // --- ReplyListener ---------------------------------------------------------------------------------------

  onDelta(conversationId: number, text: string): void {
    if (!this.live || this.live.conversationId !== conversationId) {
      this.beginGeneration();
      const on = this.settings.snapshot().speakReplies;
      this.live = { conversationId, chunker: new SentenceChunker(), filter: new SpeechFilter(), quiet: !on };
      if (on && this.isOpen(conversationId)) this.speakingMessageId.set(LIVE_REPLY);
    }
    const live = this.live;
    if (live.quiet || !this.isOpen(conversationId)) return;
    for (const s of live.chunker.push(live.filter.push(text))) this.enqueue(s);
  }

  onFinished(conversationId: number, _text: string, messageId: number | null): void {
    const live = this.live;
    if (!live || live.conversationId !== conversationId) return;
    this.live = null;
    if (live.quiet) return;
    if (this.isOpen(conversationId)) {
      for (const s of live.chunker.push(live.filter.flush())) this.enqueue(s);
      for (const s of live.chunker.flush()) this.enqueue(s);
    }
    if (this.pending > 0 && messageId !== null) this.speakingMessageId.set(messageId);
    else if (this.pending === 0) this.speakingMessageId.set(null);
  }

  /** Words said before a tool call aren't the answer: silence them, and the next round starts a fresh reply. */
  onTextReset(): void {
    this.stop();
    this.live = null;
  }

  onCancelled(): void {
    this.stop();
    this.live = null;
  }

  onFailed(): void {
    this.stop();
    this.live = null;
  }

  // --- SpeechController ------------------------------------------------------------------------------------

  /** Speaks a stored message (works with "Speak Edith's replies" off). */
  play(messageId: number, text: string): void {
    this.beginGeneration();
    if (this.live) this.live.quiet = true; // the rest of a streaming reply stays quiet
    const chunker = new SentenceChunker();
    const sentences = [...chunker.push(text), ...chunker.flush()];
    this.speakingMessageId.set(messageId);
    for (const s of sentences) this.enqueue(s);
    if (this.pending === 0) this.speakingMessageId.set(null);
  }

  /** Stops what's being spoken. A reply still streaming stays quiet; the next reply speaks again. */
  stop(): void {
    this.beginGeneration();
    if (this.live) this.live.quiet = true;
    this.speakingMessageId.set(null);
  }

  /** Settings → Test voice. */
  test(): void {
    this.play(TEST_VOICE, "Hi, I'm Edith.");
  }

  // --- internals -------------------------------------------------------------------------------------------

  private beginGeneration() {
    this.generation++;
    this.pending = 0;
    this.tts.stop();
  }

  private enqueue(sentence: string) {
    const text = cleanForSpeech(sentence);
    if (!text) return;
    const gen = this.generation;
    this.pending++;
    const ended = () => {
      if (gen !== this.generation) return; // a stopped utterance reporting late
      this.pending--;
      if (this.pending === 0 && (!this.live || this.live.quiet)) this.speakingMessageId.set(null);
    };
    this.tts.speak(text, this.settings.snapshot().speechRate, { onDone: ended, onStopped: ended, onError: ended });
  }
}
