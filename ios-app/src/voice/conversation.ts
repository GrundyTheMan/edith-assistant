/**
 * Conversation mode (C57, ported from C14 without the wake word, the foreground service or barge-in): tap once; Edith listens, transcribes what was said, answers
 * aloud and listens again for a follow-up for a few seconds. Silence ends it. The app stays open with the screen on; iOS gives no background microphone in Expo Go.
 *
 * ```
 * off -> listening -> capturing -> transcribing -> thinking -> speaking -> followup -> (capturing ...) -> off
 * ```
 * The microphone is **closed while Edith speaks** (her voice would be recorded), anything that needs approval waits for a **tap** (never a spoken yes), and the
 * recording file is always deleted. Pure logic with injected parts, so it is tested with fakes; the real recorder, speech and keep-awake are wired in `core/services`.
 */

import { toAppError } from '../core/errors';
import { log } from '../core/log';
import { Store } from '../core/store';
import type { ChatEngine, EngineState } from '../chat/engine';
import type { SettingsRepository } from '../settings/settings';
import { SpeechDetector, VAD } from './vad';
import type { AudioFiles, Recorder, Transcriber, VoiceNotice } from './voiceMemos';

export type ConversationKind = 'off' | 'listening' | 'capturing' | 'transcribing' | 'thinking' | 'speaking' | 'followup' | 'waiting_tap';
export interface ConversationState {
  kind: ConversationKind;
}

/** The banner's words for each state (C57 §4). */
export const STATE_TEXT: Record<Exclude<ConversationKind, 'off'>, string> = {
  listening: 'Listening…',
  capturing: 'Hearing you…',
  transcribing: 'Transcribing…',
  thinking: 'Thinking…',
  speaking: 'Speaking…',
  followup: 'Listening for a follow-up…',
  waiting_tap: 'Waiting for your tap',
};

/** Nobody speaks for this long after the mode starts (or after a recording that held no words): the mode ends. */
export const IDLE_LISTEN_MS = 30_000;
/** Three failures in a row end the mode. */
export const MAX_ERRORS = 3;
/** The recorder is given this long to start before "it stopped by itself" is read as another app taking the microphone. */
const START_GRACE_MS = 600;
/** The Settings test listens this long. */
export const TEST_MS = 15_000;
export const TAP_PROMPT = 'Tap Confirm on the screen.';

export interface ConversationDeps {
  recorder: Recorder & { isRecording?(): boolean };
  files: AudioFiles;
  transcriber: Transcriber;
  engine: Pick<ChatEngine, 'send' | 'isBusy'> & { state: Store<EngineState> };
  speech: { speakingMessageId: Store<number | null>; stop(): void; announce(text: string): void };
  settings: SettingsRepository;
  /** A voice memo is being recorded: the microphone is taken. */
  memoBusy: () => boolean;
  /** Keeps the screen on while the mode is on (`expo-keep-awake`). */
  keepAwake: { on(): void | Promise<void>; off(): void | Promise<void> };
  now?: () => number;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

export class Conversation {
  readonly state = new Store<ConversationState>({ kind: 'off' });
  /** Why the mode ended, or something the owner should know ("Edith will speak her replies"); cleared when a new one starts. */
  readonly notice = new Store<VoiceNotice | null>(null);
  /** Settings → Conversation → Test: the live level, and whether speech was heard (C57 §4). Null when not testing. */
  readonly testing = new Store<{ level: number; heard: boolean } | null>(null);
  private testTimer: unknown = null;
  private testEpoch = 0;
  private epoch = 0;
  private conversationId = 0;
  private waiting: 'listening' | 'followup' = 'listening';
  private errors = 0;
  private vad: SpeechDetector | null = null;
  private timer: unknown = null;
  private listenStartedAt = 0;
  private captureStartedAt = 0;
  private windowMs = IDLE_LISTEN_MS;
  private restoreSpeaking = false;
  private readonly now: () => number;
  private readonly setTimer: (fn: () => void, ms: number) => unknown;
  private readonly clearTimer: (handle: unknown) => void;

  constructor(private readonly deps: ConversationDeps) {
    this.now = deps.now ?? Date.now;
    this.setTimer = deps.setTimer ?? ((fn, ms) => setTimeout(fn, ms));
    this.clearTimer = deps.clearTimer ?? ((h) => clearTimeout(h as ReturnType<typeof setTimeout>));
  }

  get on(): boolean {
    return this.state.get().kind !== 'off';
  }
  level(): number {
    return this.deps.recorder.level();
  }
  dismiss() {
    this.notice.set(null);
  }
  private set(kind: ConversationKind) {
    this.state.set({ kind });
  }

  // --- start and stop ----------------------------------------------------------------------------------------------------------
  async start(conversationId: number): Promise<void> {
    if (this.on || this.deps.memoBusy() || this.deps.engine.isBusy) return;
    await this.stopTest();
    this.notice.set(null);
    const epoch = ++this.epoch;
    this.conversationId = conversationId;
    this.errors = 0;
    this.set('transcribing'); // claims the slot while the permission dialog is up
    try {
      if (!(await this.deps.recorder.requestPermission())) {
        this.set('off');
        this.notice.set({ text: 'Edith needs microphone access to hear you. You can allow it in Settings.', openSettings: true });
        return;
      }
    } catch {
      this.set('off');
      return;
    }
    if (epoch !== this.epoch) return;
    // Speech has to be on for a conversation; if it is off, turn it on for this session and say so.
    const saved = await this.deps.settings.current();
    if (!saved.speakReplies && saved.speakDuringConversation) {
      await this.deps.settings.update((s) => ({ ...s, speakReplies: true }));
      this.restoreSpeaking = true;
      this.notice.set({ text: 'Edith will speak her replies during the conversation.' });
    }
    try {
      await this.deps.keepAwake.on();
    } catch {
      // the screen may sleep: the mode still works
    }
    if (epoch !== this.epoch) return;
    await this.listen('listening', IDLE_LISTEN_MS);
  }

  /** Ends the mode: the microphone and any speech stop, the screen may sleep, the speaking setting is put back. */
  async stop(notice?: string): Promise<void> {
    if (!this.on) return;
    this.epoch++;
    this.cancelTimer();
    this.vad = null;
    this.set('off');
    this.deps.speech.stop();
    await this.deps.recorder.cancel().catch(() => {});
    try {
      await this.deps.keepAwake.off();
    } catch {
      // nothing to release
    }
    if (this.restoreSpeaking) {
      this.restoreSpeaking = false;
      await this.deps.settings.update((s) => ({ ...s, speakReplies: false })).catch(() => {});
    }
    if (notice) this.notice.set({ text: notice });
  }

  // --- the Settings test ---------------------------------------------------------------------------------------------------------
  /** Opens the microphone for a few seconds and shows the level and a check when speech is detected; nothing is recorded or kept. */
  async startTest(): Promise<void> {
    if (this.on || this.testing.get() || this.deps.memoBusy()) return;
    const epoch = ++this.testEpoch;
    try {
      if (!(await this.deps.recorder.requestPermission())) {
        this.notice.set({ text: 'Edith needs microphone access to hear you. You can allow it in Settings.', openSettings: true });
        return;
      }
      await this.deps.recorder.start();
    } catch (e) {
      this.notice.set({ text: `Couldn't start the microphone: ${toAppError(e).message}` });
      return;
    }
    if (epoch !== this.testEpoch) {
      await this.deps.recorder.cancel().catch(() => {});
      return;
    }
    const snap = this.deps.settings.snapshot();
    const vad = new SpeechDetector({ sensitivity: snap.conversationSensitivity, endSilenceMs: snap.conversationPauseMs });
    const began = this.now();
    let heard = false;
    this.testing.set({ level: 0, heard });
    const tick = () => {
      if (epoch !== this.testEpoch) return;
      const level = this.deps.recorder.level();
      vad.push(level);
      heard = heard || vad.isSpeaking;
      this.testing.set({ level, heard });
      if (this.now() - began > TEST_MS) void this.stopTest();
      else this.testTimer = this.setTimer(tick, VAD.sampleMs);
    };
    this.testTimer = this.setTimer(tick, VAD.sampleMs);
  }

  async stopTest(): Promise<void> {
    this.testEpoch++;
    if (this.testTimer !== null) this.clearTimer(this.testTimer);
    this.testTimer = null;
    if (this.testing.get() === null) return;
    this.testing.set(null);
    await this.deps.recorder.cancel().catch(() => {});
  }

  /** The app left the screen (or the phone locked): no background audio in Expo Go, so the mode ends (C57 §3). */
  async leftTheApp(): Promise<void> {
    await this.stop('Conversation stopped when you left the app.');
  }

  // --- listening ---------------------------------------------------------------------------------------------------------------
  private async listen(kind: 'listening' | 'followup', windowMs: number): Promise<void> {
    const epoch = this.epoch;
    this.waiting = kind;
    this.windowMs = windowMs;
    this.set(kind);
    const snap = this.deps.settings.snapshot();
    this.vad = new SpeechDetector({ sensitivity: snap.conversationSensitivity, endSilenceMs: snap.conversationPauseMs });
    try {
      await this.deps.recorder.start();
    } catch (e) {
      if (epoch === this.epoch) await this.fail(`Couldn't start the microphone: ${toAppError(e).message}`);
      return;
    }
    if (epoch !== this.epoch) {
      await this.deps.recorder.cancel().catch(() => {});
      return;
    }
    this.listenStartedAt = this.now();
    this.schedule(epoch);
  }

  private schedule(epoch: number) {
    this.cancelTimer();
    this.timer = this.setTimer(() => void this.poll(epoch), VAD.sampleMs);
  }
  private cancelTimer() {
    if (this.timer !== null) this.clearTimer(this.timer);
    this.timer = null;
  }

  private async poll(epoch: number): Promise<void> {
    if (epoch !== this.epoch || !this.vad) return;
    const now = this.now();
    // Something else took the microphone (a call, Siri): the recorder stops by itself.
    if (this.deps.recorder.isRecording?.() === false && now - this.listenStartedAt > START_GRACE_MS) {
      await this.stop('Conversation stopped: another app is using the microphone.');
      return;
    }
    const event = this.vad.push(this.deps.recorder.level());
    const speaking = this.vad.isSpeaking;
    if (event === 'speech_start') {
      this.captureStartedAt = now;
      this.set('capturing');
    } else if (event === 'false_start') {
      this.set(this.waiting);
    }
    if (event === 'speech_end' || (speaking && now - this.captureStartedAt > VAD.maxUtteranceMs)) {
      await this.finishCapture(epoch);
      return;
    }
    if (!speaking && now - this.listenStartedAt > this.windowMs) {
      await this.stop('The conversation ended.');
      return;
    }
    this.schedule(epoch);
  }

  // --- one utterance -----------------------------------------------------------------------------------------------------------
  private async finishCapture(epoch: number): Promise<void> {
    this.cancelTimer();
    this.set('transcribing');
    let uri: string | null = null;
    let text = '';
    try {
      const audio = await this.deps.recorder.stop();
      uri = audio.uri;
      text = (await this.deps.transcriber.transcribe(await this.deps.files.readBase64(audio.uri))).trim();
    } catch (e) {
      log.warn('voice', `conversation transcription failed: ${e instanceof Error ? e.name : 'error'}`);
      if (uri) await this.deps.files.delete(uri).catch(() => {});
      if (epoch === this.epoch) await this.fail(`Couldn't transcribe that: ${toAppError(e).message}`);
      return;
    }
    await this.deps.files.delete(uri).catch(() => {});
    if (epoch !== this.epoch) return;
    if (!text) {
      await this.listen(this.waiting, this.waiting === 'followup' ? this.windowMs : IDLE_LISTEN_MS); // nobody said anything: quietly listen again
      return;
    }
    await this.reply(epoch, text);
  }

  /** Sends the words as the owner's message and follows the reply: thinking, speaking, a card waiting for a tap. */
  private async reply(epoch: number, text: string): Promise<void> {
    this.set('thinking');
    const announced = new Set<string>();
    const follow = () => {
      if (epoch !== this.epoch) return;
      const es = this.deps.engine.state.get();
      if (es.kind === 'working' && es.confirmation) {
        if (!announced.has(es.confirmation.id)) {
          announced.add(es.confirmation.id);
          this.deps.speech.announce(TAP_PROMPT); // said once: the mode never approves by voice
        }
        this.set('waiting_tap');
      } else if (this.deps.speech.speakingMessageId.get() !== null) this.set('speaking');
      else this.set('thinking');
    };
    const unsubs = [this.deps.engine.state.subscribe(follow), this.deps.speech.speakingMessageId.subscribe(follow)];
    try {
      await this.deps.engine.send(this.conversationId, text, 'hands_free');
    } catch (e) {
      unsubs.forEach((u) => u());
      if (epoch === this.epoch) await this.fail(`Edith couldn't answer: ${toAppError(e).message}`);
      return;
    }
    unsubs.forEach((u) => u());
    if (epoch !== this.epoch) return;
    const end = this.deps.engine.state.get();
    if (end.kind === 'failed') {
      await this.fail("Edith couldn't answer that.");
      return;
    }
    this.errors = 0;
    await this.speechDone(epoch);
    if (epoch !== this.epoch) return;
    const followUpMs = this.deps.settings.snapshot().followUpSeconds * 1000;
    if (followUpMs <= 0) await this.stop();
    else await this.listen('followup', followUpMs);
  }

  /** Resolves when nothing is being spoken (immediately when nothing is). */
  private speechDone(epoch: number): Promise<void> {
    const store = this.deps.speech.speakingMessageId;
    if (store.get() === null) return Promise.resolve();
    return new Promise((resolve) => {
      const off = store.subscribe(() => {
        if (store.get() === null || epoch !== this.epoch) {
          off();
          resolve();
        }
      });
    });
  }

  /** One failure: a short notice and back to listening; three in a row end the mode. */
  private async fail(message: string): Promise<void> {
    this.errors++;
    if (this.errors >= MAX_ERRORS) {
      await this.stop(`Conversation stopped after ${MAX_ERRORS} errors in a row. The last: ${message}`);
      return;
    }
    this.notice.set({ text: message });
    await this.listen(this.waiting, this.waiting === 'followup' ? this.windowMs : IDLE_LISTEN_MS);
  }
}
