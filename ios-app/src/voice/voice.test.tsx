import { fireEvent, render, screen } from '@testing-library/react-native';
import { SettingsRepository, memoryStore } from '../settings/settings';
import { OllamaClient } from '../ollama/client';
import type { ModelSelection } from '../settings/modelSelection';
import { fakeFetch, jsonResponse } from '../testing/fakeHttp';
import { themes } from '../ui/theme';
import { RecordingRow, VoiceNoticeBar, formatElapsed } from '../ui/VoiceViews';
import {
  MAX_MEMO_MS,
  MIN_MEMO_MS,
  levelFromDb,
  OllamaTranscriber,
  TRANSCRIPTION_PROMPT,
  VoiceMemos,
  type AudioFiles,
  type RecordedAudio,
  type Recorder,
  type Transcriber,
} from './voiceMemos';

const modelWith = (capabilities: string[]) => ({ ensureModel: async () => ({ name: 'gemma4:12b', capabilities }) }) as unknown as ModelSelection;

function transcriberFixture(capabilities: string[], content: string) {
  const { fetch, requests } = fakeFetch(() => jsonResponse({ message: { role: 'assistant', content }, done: true }));
  const t = new OllamaTranscriber(new OllamaClient({ fetch }), new SettingsRepository(memoryStore()), modelWith(capabilities));
  return { t, requests };
}

describe('OllamaTranscriber', () => {
  it('sends the WAV as an image of the user message with the exact prompt, not streamed', async () => {
    const { t, requests } = transcriberFixture(['completion', 'audio'], '  Hello Edith.  ');
    expect(await t.transcribe('UklGRg==')).toBe('Hello Edith.');
    const body = JSON.parse(requests[0].body!);
    expect(requests[0].url.endsWith('/api/chat')).toBe(true);
    expect(body.stream).toBe(false);
    expect(body.model).toBe('gemma4:12b');
    expect(body.messages).toEqual([{ role: 'user', content: TRANSCRIPTION_PROMPT, images: ['UklGRg=='] }]);
    expect(TRANSCRIPTION_PROMPT).toContain('[NO_SPEECH]');
  });

  it('turns [NO_SPEECH] into an empty transcript', async () => {
    expect(await transcriberFixture(['audio'], '[NO_SPEECH]').t.transcribe('x')).toBe('');
    expect(await transcriberFixture(['audio'], 'Sorry: [NO_SPEECH].').t.transcribe('x')).toBe('');
  });

  it("refuses a model that can't hear, without calling it, and tries when capabilities are unknown", async () => {
    const deaf = transcriberFixture(['completion', 'tools'], 'x');
    await expect(deaf.t.transcribe('x')).rejects.toMatchObject({ kind: 'model_lacks_capability', message: expect.stringContaining('Hears audio') });
    expect(deaf.requests).toHaveLength(0);
    const unknown = transcriberFixture([], 'ok');
    expect(await unknown.t.transcribe('x')).toBe('ok');
    expect(unknown.requests).toHaveLength(1);
  });
});

class FakeRecorder implements Recorder {
  granted = true;
  startError: Error | null = null;
  durationMs = 3000;
  started = 0;
  cancelled = 0;
  stopped = 0;
  requestPermission = async () => this.granted;
  start = async () => {
    if (this.startError) throw this.startError;
    this.started++;
  };
  stop = async (): Promise<RecordedAudio> => {
    this.stopped++;
    return { uri: 'file:///rec.wav', durationMs: this.durationMs };
  };
  cancel = async () => {
    this.cancelled++;
  };
  level = () => 0.5;
}

function memoFixture(transcribe: Transcriber['transcribe'] = async () => 'what is the weather') {
  const recorder = new FakeRecorder();
  const deleted: string[] = [];
  const files: AudioFiles = { readBase64: async () => 'QUJD', delete: async (u) => void deleted.push(u) };
  const sent: { id: number; text: string; source: string }[] = [];
  const engine = { isBusy: false, send: jest.fn(async (id: number, text: string, source?: string) => void sent.push({ id, text, source: source ?? 'typed' })) };
  const timers: { fn: () => void; ms: number }[] = [];
  const cleared: unknown[] = [];
  const stopSpeaking = jest.fn();
  const memos = new VoiceMemos({
    recorder,
    files,
    transcriber: { transcribe },
    engine,
    stopSpeaking,
    now: () => 1000,
    setTimer: (fn, ms) => {
      timers.push({ fn, ms });
      return timers.length;
    },
    clearTimer: (h) => void cleared.push(h),
  });
  return { memos, recorder, deleted, sent, engine, timers, cleared, stopSpeaking };
}

describe('VoiceMemos', () => {
  it('records, transcribes, sends the words to the chat it started in as voice_memo, and deletes the file', async () => {
    const f = memoFixture();
    await f.memos.start(7);
    expect(f.memos.state.get()).toEqual({ kind: 'recording', conversationId: 7, startedAt: 1000 });
    expect(f.stopSpeaking).toHaveBeenCalled();
    await f.memos.stop();
    expect(f.sent).toEqual([{ id: 7, text: 'what is the weather', source: 'voice_memo' }]);
    expect(f.deleted).toEqual(['file:///rec.wav']);
    expect(f.memos.state.get()).toEqual({ kind: 'idle' });
    expect(f.memos.notice.get()).toBeNull();
  });

  it('shows Transcribing while it works', async () => {
    let release: (t: string) => void = () => {};
    const f = memoFixture(() => new Promise<string>((r) => (release = r)));
    await f.memos.start(1);
    const stopping = f.memos.stop();
    await Promise.resolve();
    await Promise.resolve();
    expect(f.memos.state.get().kind).toBe('transcribing');
    expect(f.memos.busy).toBe(true);
    release('hi');
    await stopping;
    expect(f.memos.busy).toBe(false);
  });

  it('too short: nothing is transcribed or sent and the file goes', async () => {
    const transcribe = jest.fn(async () => 'x');
    const f = memoFixture(transcribe);
    f.recorder.durationMs = MIN_MEMO_MS - 1;
    await f.memos.start(1);
    await f.memos.stop();
    expect(transcribe).not.toHaveBeenCalled();
    expect(f.sent).toHaveLength(0);
    expect(f.deleted).toEqual(['file:///rec.wav']);
    expect(f.memos.notice.get()?.text).toBe('That was too short to send.');
  });

  it('silence: "No speech was heard" and nothing is sent', async () => {
    const f = memoFixture(async () => '');
    await f.memos.start(1);
    await f.memos.stop();
    expect(f.sent).toHaveLength(0);
    expect(f.deleted).toHaveLength(1);
    expect(f.memos.notice.get()?.text).toBe('No speech was heard in that recording.');
  });

  it('a failed transcription says why, sends nothing and still deletes the file', async () => {
    const f = memoFixture(async () => {
      throw new Error('boom');
    });
    await f.memos.start(1);
    await f.memos.stop();
    expect(f.sent).toHaveLength(0);
    expect(f.deleted).toEqual(['file:///rec.wav']);
    expect(f.memos.notice.get()?.text).toContain("Couldn't transcribe the recording");
    expect(f.memos.notice.get()?.text).toContain('boom');
    expect(f.memos.state.get().kind).toBe('idle');
  });

  it('cancel: stops, sends nothing, no transcription', async () => {
    const transcribe = jest.fn(async () => 'x');
    const f = memoFixture(transcribe);
    await f.memos.start(1);
    await f.memos.cancel();
    expect(f.recorder.cancelled).toBe(1);
    expect(f.memos.state.get().kind).toBe('idle');
    expect(f.cleared).toHaveLength(1); // the 5-minute timer
    expect(transcribe).not.toHaveBeenCalled();
    expect(f.sent).toHaveLength(0);
  });

  it('a refused microphone gives the message with Open settings and does not record', async () => {
    const f = memoFixture();
    f.recorder.granted = false;
    await f.memos.start(1);
    expect(f.recorder.started).toBe(0);
    expect(f.memos.notice.get()).toEqual({ text: expect.stringContaining('microphone access'), openSettings: true });
    expect(f.memos.state.get().kind).toBe('idle');
  });

  it("a microphone that won't open says so and goes back to idle", async () => {
    const f = memoFixture();
    f.recorder.startError = new Error('busy');
    await f.memos.start(1);
    expect(f.memos.notice.get()?.text).toContain("Couldn't start recording");
    expect(f.memos.state.get().kind).toBe('idle');
  });

  it('stops by itself after 5 minutes and sends what was said', async () => {
    const f = memoFixture();
    await f.memos.start(1);
    expect(f.timers).toHaveLength(1);
    expect(f.timers[0].ms).toBe(MAX_MEMO_MS);
    f.timers[0].fn();
    await new Promise((r) => setImmediate(r));
    expect(f.sent).toHaveLength(1);
  });

  it('leaving the app mid-recording drops the memo with a notice', async () => {
    const f = memoFixture();
    await f.memos.start(1);
    await f.memos.leftTheApp();
    expect(f.recorder.cancelled).toBe(1);
    expect(f.sent).toHaveLength(0);
    expect(f.memos.notice.get()?.text).toContain('left the app');
    await f.memos.leftTheApp(); // idle: nothing happens
    expect(f.recorder.cancelled).toBe(1);
  });

  it('ignores Record while a reply is running or a memo is going, and a second start while recording', async () => {
    const f = memoFixture();
    f.engine.isBusy = true;
    await f.memos.start(1);
    expect(f.recorder.started).toBe(0);
    f.engine.isBusy = false;
    await f.memos.start(1);
    await f.memos.start(2);
    expect(f.recorder.started).toBe(1);
    await f.memos.stop();
    await f.memos.stop(); // idle: nothing
    expect(f.sent).toHaveLength(1);
  });
});

describe('helpers and views', () => {
  it('levelFromDb maps −60..0 dBFS to 0..1', () => {
    expect(levelFromDb(-160)).toBe(0);
    expect(levelFromDb(-60)).toBe(0);
    expect(levelFromDb(-30)).toBeCloseTo(0.5);
    expect(levelFromDb(0)).toBe(1);
    expect(levelFromDb(undefined)).toBe(0);
    expect(levelFromDb(NaN)).toBe(0);
  });

  it('formats the elapsed time as m:ss', () => {
    expect(formatElapsed(0)).toBe('0:00');
    expect(formatElapsed(7400)).toBe('0:07');
    expect(formatElapsed(65_000)).toBe('1:05');
  });

  it('RecordingRow has Cancel and Stop and send while recording, and says Transcribing after', async () => {
    const onCancel = jest.fn();
    const onStop = jest.fn();
    const { rerender } = await render(
      <RecordingRow state={{ kind: 'recording', conversationId: 1, startedAt: 0 }} level={() => 0.4} onCancel={onCancel} onStop={onStop} theme={themes.light} now={() => 7400} />,
    );
    expect(screen.getByText('0:07')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Cancel recording'));
    await fireEvent.press(screen.getByLabelText('Stop and send recording'));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onStop).toHaveBeenCalledTimes(1);
    await rerender(<RecordingRow state={{ kind: 'transcribing', conversationId: 1 }} level={() => 0} onCancel={onCancel} onStop={onStop} theme={themes.light} />);
    expect(screen.getByText('Transcribing…')).toBeTruthy();
    expect(screen.queryByLabelText('Stop and send recording')).toBeNull();
  });

  it('the notice bar offers Open settings only for a refused microphone, and dismisses', async () => {
    const onDismiss = jest.fn();
    const { rerender } = await render(<VoiceNoticeBar notice={{ text: 'Needs mic', openSettings: true }} onDismiss={onDismiss} theme={themes.light} />);
    expect(screen.getByLabelText('Open settings')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Dismiss'));
    expect(onDismiss).toHaveBeenCalled();
    await rerender(<VoiceNoticeBar notice={{ text: 'Too short' }} onDismiss={onDismiss} theme={themes.light} />);
    expect(screen.queryByLabelText('Open settings')).toBeNull();
  });
});
