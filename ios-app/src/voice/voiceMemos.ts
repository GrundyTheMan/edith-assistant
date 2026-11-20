/** Voice memos (C12 on iOS): record, have the model transcribe, send the transcript as the owner's message. */

import { AppError, toAppError } from '../core/errors';
import { log } from '../core/log';
import { Store } from '../core/store';
import type { ChatEngine } from '../chat/engine';
import type { OllamaClient } from '../ollama/client';
import { canHear } from '../ollama/types';
import type { ModelSelection } from '../settings/modelSelection';
import type { SettingsRepository } from '../settings/settings';

/** Shorter than this is "too short to send" (C12 §2). */
export const MIN_MEMO_MS = 700;
/** Recording stops by itself at this length and what was said is sent (C12 §2). */
export const MAX_MEMO_MS = 5 * 60 * 1000;

/** 03-ollama-reference §5: the prompt that works with gemma4. A reply containing [NO_SPEECH] means silence. */
export const TRANSCRIPTION_PROMPT =
  'Transcribe this audio verbatim. Reply with only the transcript, no commentary. ' +
  'If there is no intelligible speech, reply with exactly [NO_SPEECH] and nothing else.';

export interface RecordedAudio {
  uri: string;
  durationMs: number;
}

/** The microphone (expo-audio); a fake in tests. Records 16 kHz mono 16-bit PCM WAV, the only format Ollama accepts. */
export interface Recorder {
  /** Asks for microphone access the first time; true when granted. Never throws. */
  requestPermission(): Promise<boolean>;
  start(): Promise<void>;
  stop(): Promise<RecordedAudio>;
  /** Stops and deletes the file. */
  cancel(): Promise<void>;
  /** 0..1, for the level meter. */
  level(): number;
}

/** The recording file on disk (expo-file-system); a fake in tests. */
export interface AudioFiles {
  readBase64(uri: string): Promise<string>;
  delete(uri: string): Promise<void>;
}

export interface Transcriber {
  /** The words in a WAV recording, or "" when nobody spoke. */
  transcribe(wavBase64: string): Promise<string>;
}

/** Transcribes with the chat model through Ollama; the model has to hear audio ("Hears audio" in Settings). */
export class OllamaTranscriber implements Transcriber {
  constructor(
    private readonly client: OllamaClient,
    private readonly settings: SettingsRepository,
    private readonly selection: ModelSelection,
  ) {}

  async transcribe(wavBase64: string): Promise<string> {
    const model = await this.selection.ensureModel();
    // Capabilities unknown (the server didn't say): try anyway, its error is clearer than a refusal here.
    if (model.capabilities.length > 0 && !canHear(model)) throw AppError.lacksCapability(model.name, 'audio');
    const { serverUrl } = await this.settings.current();
    const reply = await this.client.chat(serverUrl, {
      model: model.name,
      messages: [{ role: 'user', content: TRANSCRIPTION_PROMPT, images: [wavBase64] }],
    });
    const text = reply.content.trim();
    return text.includes('[NO_SPEECH]') ? '' : text;
  }
}

/** dBFS from the recorder's metering to 0..1 on a −60..0 scale (as C12). */
export function levelFromDb(db: number | undefined): number {
  if (db === undefined || !Number.isFinite(db)) return 0;
  return Math.min(1, Math.max(0, (db + 60) / 60));
}

export type VoiceState =
  | { kind: 'idle' }
  | { kind: 'recording'; conversationId: number; startedAt: number }
  | { kind: 'transcribing'; conversationId: number };

export interface VoiceNotice {
  text: string;
  /** The microphone was refused: offer "Open settings". */
  openSettings?: boolean;
}

export interface VoiceDeps {
  recorder: Recorder;
  files: AudioFiles;
  transcriber: Transcriber;
  engine: Pick<ChatEngine, 'send' | 'isBusy'>;
  /** Called when recording starts so Edith's voice isn't recorded (C13). */
  stopSpeaking: () => void;
  now?: () => number;
  /** Replaceable in tests. */
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

/**
 * Lives for the whole app, like the chat engine, so a memo goes to the chat it was started in even if the owner
 * switches chats. One at a time; the recording file is always deleted, whatever happens.
 */
export class VoiceMemos {
  readonly state = new Store<VoiceState>({ kind: 'idle' });
  /** A short message for the owner (nothing was sent, or why); cleared when a new memo starts or `dismiss` runs. */
  readonly notice = new Store<VoiceNotice | null>(null);
  private limitTimer: unknown = null;
  private readonly now: () => number;
  private readonly setTimer: (fn: () => void, ms: number) => unknown;
  private readonly clearTimer: (handle: unknown) => void;

  constructor(private readonly deps: VoiceDeps) {
    this.now = deps.now ?? Date.now;
    this.setTimer = deps.setTimer ?? ((fn, ms) => setTimeout(fn, ms));
    this.clearTimer = deps.clearTimer ?? ((h) => clearTimeout(h as ReturnType<typeof setTimeout>));
  }

  get busy(): boolean {
    return this.state.get().kind !== 'idle';
  }
  level(): number {
    return this.deps.recorder.level();
  }
  dismiss() {
    this.notice.set(null);
  }

  /** Starts recording for `conversationId`. Does nothing while a memo or a reply is already going on. */
  async start(conversationId: number): Promise<void> {
    if (this.busy || this.deps.engine.isBusy) return;
    this.notice.set(null);
    // Claim the slot now so a double tap can't start two recordings while the permission dialog is up.
    this.state.set({ kind: 'transcribing', conversationId });
    try {
      if (!(await this.deps.recorder.requestPermission())) {
        this.state.set({ kind: 'idle' });
        this.notice.set({ text: 'Edith needs microphone access to hear you. You can allow it in Settings.', openSettings: true });
        return;
      }
      this.deps.stopSpeaking();
      await this.deps.recorder.start();
    } catch (e) {
      this.state.set({ kind: 'idle' });
      this.notice.set({ text: `Couldn't start recording: ${toAppError(e).message}` });
      return;
    }
    this.state.set({ kind: 'recording', conversationId, startedAt: this.now() });
    this.limitTimer = this.setTimer(() => void this.stop(), MAX_MEMO_MS);
  }

  /** Ends the recording, transcribes it and sends the words as the owner's message (source `voice_memo`). */
  async stop(): Promise<void> {
    const s = this.state.get();
    if (s.kind !== 'recording') return;
    this.clearLimit();
    this.state.set({ kind: 'transcribing', conversationId: s.conversationId });
    let audio: RecordedAudio | null = null;
    try {
      audio = await this.deps.recorder.stop();
      if (audio.durationMs < MIN_MEMO_MS) {
        this.notice.set({ text: 'That was too short to send.' });
        return;
      }
      const b64 = await this.deps.files.readBase64(audio.uri);
      const text = (await this.deps.transcriber.transcribe(b64)).trim();
      if (!text) {
        this.notice.set({ text: 'No speech was heard in that recording.' });
        return;
      }
      this.state.set({ kind: 'idle' });
      void this.deps.engine.send(s.conversationId, text, 'voice_memo');
    } catch (e) {
      log.warn('voice', `memo failed: ${e instanceof Error ? e.name : 'error'}`);
      this.notice.set({ text: `Couldn't transcribe the recording: ${toAppError(e).message}` });
    } finally {
      if (audio) await this.deps.files.delete(audio.uri).catch(() => {});
      this.state.set({ kind: 'idle' });
    }
  }

  /** The ✕ button: nothing is sent and the file is deleted. */
  async cancel(): Promise<void> {
    if (this.state.get().kind !== 'recording') return;
    this.clearLimit();
    await this.deps.recorder.cancel().catch(() => {});
    this.state.set({ kind: 'idle' });
  }

  /** The app went to the background mid-recording: the mic is released and the memo is dropped, not sent. */
  async leftTheApp(): Promise<void> {
    if (this.state.get().kind !== 'recording') return;
    await this.cancel();
    this.notice.set({ text: "The recording stopped when you left the app, so it wasn't sent." });
  }

  private clearLimit() {
    if (this.limitTimer !== null) this.clearTimer(this.limitTimer);
    this.limitTimer = null;
  }
}
