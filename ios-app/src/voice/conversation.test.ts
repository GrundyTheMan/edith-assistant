import { AppError } from '../core/errors';
import { Store } from '../core/store';
import type { EngineState } from '../chat/engine';
import type { ConfirmationRequest } from '../tools/framework/tool';
import { SettingsRepository, memoryStore } from '../settings/settings';
import { Conversation, IDLE_LISTEN_MS, STATE_TEXT, TAP_PROMPT, TEST_MS, type ConversationKind } from './conversation';

const flush = async () => {
  for (let i = 0; i < 30; i++) await Promise.resolve();
};

/** Fakes for everything outside the state machine, and a clock that moves 100 ms at a time. */
async function harness(over: { settings?: Record<string, string> } = {}) {
  let t = 1_000_000;
  const timers: { fn: () => void; due: number; id: number }[] = [];
  let nextId = 1;
  const settings = new SettingsRepository(memoryStore(over.settings));
  await settings.current();

  const h = {
    level: 0,
    recording: false,
    started: 0,
    cancelled: 0,
    stoppedFiles: [] as string[],
    deleted: [] as string[],
    permission: true,
    startError: null as Error | null,
    transcripts: [] as (string | Error)[],
    sent: [] as [number, string, string][],
    announced: [] as string[],
    keepAwake: [] as string[],
    memoBusy: false,
    resolveSend: () => {},
    failSend: () => {},
    engineState: new Store<EngineState>({ kind: 'idle' }),
    speaking: new Store<number | null>(null),
    file: 0,
  };
  const recorder = {
    requestPermission: async () => h.permission,
    start: async () => {
      if (h.startError) throw h.startError;
      h.recording = true;
      h.started++;
    },
    stop: async () => {
      h.recording = false;
      const uri = `file:///conv-${++h.file}.wav`;
      h.stoppedFiles.push(uri);
      return { uri, durationMs: 2000 };
    },
    cancel: async () => {
      if (h.recording) h.cancelled++;
      h.recording = false;
    },
    level: () => h.level,
    isRecording: () => h.recording,
  };
  const engine = {
    isBusy: false,
    state: h.engineState,
    send: jest.fn(
      (id: number, text: string, source = 'typed') =>
        new Promise<void>((resolve) => {
          h.sent.push([id, text, source]);
          h.engineState.set({ kind: 'working', conversationId: id, label: '', partialText: '' });
          h.resolveSend = () => {
            h.engineState.set({ kind: 'idle' });
            resolve();
          };
          h.failSend = () => {
            h.engineState.set({ kind: 'failed', conversationId: id, error: AppError.insecureUrl('x') });
            resolve();
          };
        }),
    ),
  } as never;
  const speech = {
    speakingMessageId: h.speaking,
    stop: jest.fn(() => h.speaking.set(null)),
    announce: (text: string) => void h.announced.push(text),
  };
  const conversation = new Conversation({
    recorder,
    files: {
      readBase64: async () => 'B64',
      delete: async (uri) => void h.deleted.push(uri),
    },
    transcriber: {
      transcribe: async () => {
        const next = h.transcripts.shift() ?? '';
        if (next instanceof Error) throw next;
        return next;
      },
    },
    engine,
    speech,
    settings,
    memoBusy: () => h.memoBusy,
    keepAwake: { on: () => void h.keepAwake.push('on'), off: () => void h.keepAwake.push('off') },
    now: () => t,
    setTimer: (fn, ms) => {
      const id = nextId++;
      timers.push({ fn, due: t + ms, id });
      return id;
    },
    clearTimer: (id) => {
      const i = timers.findIndex((x) => x.id === id);
      if (i >= 0) timers.splice(i, 1);
    },
  });

  /** Moves the clock `ms` forward in 100 ms steps, running what is due and letting promises settle. */
  const advance = async (ms: number) => {
    for (let done = 0; done < ms; done += 100) {
      t += 100;
      const due = timers.filter((x) => x.due <= t);
      for (const d of due) {
        timers.splice(timers.indexOf(d), 1);
        d.fn();
      }
      await flush();
    }
  };
  const kind = (): ConversationKind => conversation.state.get().kind;
  /** Quiet while the room is measured, then `loud` samples of speech and a pause that ends it. */
  const say = async (loud = 6) => {
    h.level = 0.02;
    await advance(800);
    h.level = 0.6;
    await advance(loud * 100);
    h.level = 0.02;
    await advance(1200);
  };
  return { h, conversation, advance, kind, say, settings, speech, engine, setLevel: (l: number) => (h.level = l) };
}

const card: ConfirmationRequest = { id: 'c1', title: 'Unlock the front door?', confirmLabel: 'Unlock' } as never;

describe('the conversation loop', () => {
  it('listens, hears, transcribes, sends, speaks, then listens for a follow-up and ends on silence', async () => {
    const { h, conversation, advance, kind } = await harness();
    h.transcripts.push("What's the weather in Boston?");
    await conversation.start(7);
    expect(kind()).toBe('listening');
    expect(h.recording).toBe(true);
    expect(h.keepAwake).toEqual(['on']);

    h.level = 0.02;
    await advance(800);
    h.level = 0.6;
    await advance(400);
    expect(kind()).toBe('capturing');
    h.level = 0.02;
    await advance(1200);
    expect(kind()).toBe('thinking');
    expect(h.sent).toEqual([[7, "What's the weather in Boston?", 'hands_free']]);
    expect(h.recording).toBe(false); // the microphone is closed while Edith answers
    expect(h.deleted).toEqual(['file:///conv-1.wav']);

    h.speaking.set(3);
    await flush();
    expect(kind()).toBe('speaking');
    expect(h.recording).toBe(false);
    h.resolveSend();
    await flush();
    expect(kind()).toBe('speaking'); // the reply is written but still being said
    h.speaking.set(null);
    await advance(100);
    expect(kind()).toBe('followup');
    expect(h.recording).toBe(true);

    await advance(8_200);
    expect(kind()).toBe('off');
    expect(conversation.notice.get()).toEqual({ text: 'The conversation ended.' });
    expect(h.recording).toBe(false);
    expect(h.keepAwake).toEqual(['on', 'off']);
  });

  it('takes a follow-up without a tap', async () => {
    const { h, conversation, advance, kind, say } = await harness();
    h.transcripts.push('first', 'second');
    await conversation.start(1);
    await say();
    h.resolveSend();
    await advance(100);
    expect(kind()).toBe('followup');
    await say(); // the room's floor is measured again
    expect(h.sent.map((s) => s[1])).toEqual(['first', 'second']);
    expect(h.sent.every((s) => s[2] === 'hands_free')).toBe(true);
    expect(h.deleted).toHaveLength(2);
  });

  it('returns quietly to listening when the recording held no words, and deletes the file', async () => {
    const { h, conversation, kind, say } = await harness();
    h.transcripts.push('');
    await conversation.start(1);
    await say();
    expect(h.sent).toEqual([]);
    expect(kind()).toBe('listening');
    expect(h.recording).toBe(true);
    expect(h.deleted).toHaveLength(1);
    expect(conversation.notice.get()).toBeNull();
  });

  it('ends when nobody speaks for the first 30 seconds', async () => {
    const { h, conversation, advance, kind } = await harness();
    await conversation.start(1);
    await advance(IDLE_LISTEN_MS - 500);
    expect(kind()).toBe('listening');
    await advance(1_000);
    expect(kind()).toBe('off');
    expect(h.recording).toBe(false);
    expect(conversation.notice.get()).toEqual({ text: 'The conversation ended.' });
  });

  it('ends after the reply when the follow-up window is 0', async () => {
    const { h, conversation, advance, kind, say } = await harness({ settings: { follow_up_seconds: '0' } });
    h.transcripts.push('hello');
    await conversation.start(1);
    await say();
    h.resolveSend();
    await advance(100);
    expect(kind()).toBe('off');
  });

  it('cuts an utterance at 60 seconds', async () => {
    const { h, conversation, advance, kind } = await harness();
    h.transcripts.push('a very long monologue');
    await conversation.start(1);
    h.level = 0.02;
    await advance(800);
    h.level = 0.6;
    await advance(60_500);
    expect(kind()).toBe('thinking');
    expect(h.sent[0][1]).toBe('a very long monologue');
  });

  it('Stop during speech goes on to the follow-up', async () => {
    const { h, conversation, advance, kind, say, speech } = await harness();
    h.transcripts.push('hi');
    await conversation.start(1);
    await say();
    h.speaking.set(2);
    await flush();
    expect(kind()).toBe('speaking');
    speech.stop(); // the banner's Stop button
    h.resolveSend();
    await advance(100);
    expect(kind()).toBe('followup');
  });
});

describe('errors', () => {
  it('a failed transcription says so and listens again, with the file deleted', async () => {
    const { h, conversation, kind, say } = await harness();
    h.transcripts.push(new Error('model offline'));
    await conversation.start(1);
    await say();
    expect(kind()).toBe('listening');
    expect(conversation.notice.get()?.text).toContain("Couldn't transcribe that");
    expect(h.deleted).toEqual(['file:///conv-1.wav']);
  });

  it('three failures in a row end the mode, and a success in between starts the count again', async () => {
    const a = await harness();
    a.h.transcripts.push(new Error('1'), new Error('2'), new Error('3'));
    await a.conversation.start(1);
    await a.say();
    await a.say();
    expect(a.kind()).toBe('listening');
    await a.say();
    expect(a.kind()).toBe('off');
    expect(a.conversation.notice.get()?.text).toContain('after 3 errors in a row');
    expect(a.h.deleted).toHaveLength(3);
    expect(a.h.keepAwake).toEqual(['on', 'off']);

    const b = await harness();
    b.h.transcripts.push(new Error('1'), new Error('2'), 'works', new Error('4'), new Error('5'));
    await b.conversation.start(1);
    await b.say();
    await b.say();
    await b.say();
    b.h.resolveSend();
    await b.advance(100);
    expect(b.kind()).toBe('followup');
    await b.say();
    await b.say();
    expect(b.kind()).not.toBe('off'); // the count was reset by the answer
  });

  it('a failed reply counts as an error and goes back to listening', async () => {
    const { h, conversation, kind, say, advance } = await harness();
    h.transcripts.push('hi');
    await conversation.start(1);
    await say();
    h.failSend();
    await advance(100);
    expect(kind()).toBe('listening');
    expect(conversation.notice.get()?.text).toContain("Edith couldn't answer");
  });

  it('a microphone that will not start fails three times and ends', async () => {
    const { h, conversation, kind } = await harness();
    h.startError = new Error('busy');
    await conversation.start(1);
    expect(kind()).toBe('off');
    expect(conversation.notice.get()?.text).toContain('after 3 errors in a row');
    expect(h.keepAwake).toEqual(['on', 'off']);
  });

  it('asks for the microphone, and says how to allow it when refused', async () => {
    const { h, conversation, kind } = await harness();
    h.permission = false;
    await conversation.start(1);
    expect(kind()).toBe('off');
    expect(conversation.notice.get()).toEqual({ text: 'Edith needs microphone access to hear you. You can allow it in Settings.', openSettings: true });
    expect(h.started).toBe(0);
  });

  it('does not start while a memo is recording', async () => {
    const { h, conversation, kind } = await harness();
    h.memoBusy = true;
    await conversation.start(1);
    expect(kind()).toBe('off');
    expect(h.started).toBe(0);
  });
});

describe('leaving', () => {
  it('leaving the app ends the mode with the notice, releasing everything', async () => {
    const { h, conversation, kind, speech } = await harness();
    await conversation.start(1);
    await conversation.leftTheApp();
    expect(kind()).toBe('off');
    expect(conversation.notice.get()).toEqual({ text: 'Conversation stopped when you left the app.' });
    expect(h.recording).toBe(false);
    expect(speech.stop).toHaveBeenCalled();
    expect(h.keepAwake).toEqual(['on', 'off']);
  });

  it('leaving while a recording is being transcribed sends nothing and keeps nothing', async () => {
    const { h, conversation, advance } = await harness();
    h.transcripts.push('late words');
    await conversation.start(1);
    h.level = 0.02;
    await advance(800);
    h.level = 0.6;
    await advance(500);
    h.level = 0.02;
    const pending = advance(1_100);
    await conversation.leftTheApp();
    await pending;
    await advance(500);
    expect(h.sent).toEqual([]);
    expect(h.recording).toBe(false);
  });

  it('another app taking the microphone ends the mode', async () => {
    const { h, conversation, advance, kind } = await harness();
    await conversation.start(1);
    await advance(1_000);
    h.recording = false; // a call came in: the recorder stopped by itself
    await advance(200);
    expect(kind()).toBe('off');
    expect(conversation.notice.get()?.text).toContain('another app is using the microphone');
  });

  it('End stops everything and can be called twice', async () => {
    const { conversation, kind, h } = await harness();
    await conversation.start(1);
    await conversation.stop();
    await conversation.stop();
    expect(kind()).toBe('off');
    expect(h.keepAwake).toEqual(['on', 'off']);
  });
});

describe('approvals', () => {
  it('a card waiting pauses the loop and says "Tap Confirm on the screen" once', async () => {
    const { h, conversation, advance, kind, say } = await harness();
    h.transcripts.push('unlock the front door');
    await conversation.start(1);
    await say();
    h.engineState.set({ kind: 'working', conversationId: 1, label: '', partialText: '', confirmation: card });
    await flush();
    expect(kind()).toBe('waiting_tap');
    h.engineState.set({ kind: 'working', conversationId: 1, label: 'x', partialText: 'more', confirmation: card }); // redrawn: not said again
    await flush();
    expect(h.announced).toEqual([TAP_PROMPT]);
    expect(h.recording).toBe(false); // no voice answer is listened for
    h.engineState.set({ kind: 'working', conversationId: 1, label: '', partialText: '' }); // the owner tapped
    await flush();
    expect(kind()).toBe('thinking');
    h.resolveSend();
    await advance(100);
    expect(kind()).toBe('followup');
  });
});

describe('speaking during a conversation', () => {
  it('turns speaking on for the session when it is off, says so, and puts it back', async () => {
    const { conversation, settings } = await harness({ settings: { speak_replies: 'false' } });
    await conversation.start(1);
    expect(settings.snapshot().speakReplies).toBe(true);
    expect(conversation.notice.get()).toEqual({ text: 'Edith will speak her replies during the conversation.' });
    await conversation.stop();
    expect(settings.snapshot().speakReplies).toBe(false);
  });

  it('leaves it alone when the owner does not want that', async () => {
    const { conversation, settings } = await harness({ settings: { speak_replies: 'false', speak_during_conversation: 'false' } });
    await conversation.start(1);
    expect(settings.snapshot().speakReplies).toBe(false);
    expect(conversation.notice.get()).toBeNull();
  });

  it('does not touch it when it is already on', async () => {
    const { conversation, settings } = await harness();
    await conversation.start(1);
    await conversation.stop();
    expect(settings.snapshot().speakReplies).toBe(true);
  });
});

describe('the Settings test', () => {
  it('shows the level and a check when speech is heard, then stops by itself, recording nothing', async () => {
    const { h, conversation, advance } = await harness();
    await conversation.startTest();
    expect(conversation.testing.get()).toEqual({ level: 0, heard: false });
    h.level = 0.02;
    await advance(800);
    expect(conversation.testing.get()).toEqual({ level: 0.02, heard: false });
    h.level = 0.6;
    await advance(400);
    expect(conversation.testing.get()).toEqual({ level: 0.6, heard: true });
    await advance(TEST_MS);
    expect(conversation.testing.get()).toBeNull();
    expect(h.recording).toBe(false);
    expect(h.sent).toEqual([]);
    expect(h.deleted).toEqual([]); // the file was cancelled by the recorder, never read
  });

  it('can be stopped, and a conversation starts cleanly after it', async () => {
    const { h, conversation, kind } = await harness();
    await conversation.startTest();
    await conversation.stopTest();
    expect(conversation.testing.get()).toBeNull();
    expect(h.recording).toBe(false);
    await conversation.start(1);
    expect(kind()).toBe('listening');
  });
});

describe('words', () => {
  it('has the banner text for every state', () => {
    expect(STATE_TEXT).toEqual({
      listening: 'Listening…',
      capturing: 'Hearing you…',
      transcribing: 'Transcribing…',
      thinking: 'Thinking…',
      speaking: 'Speaking…',
      followup: 'Listening for a follow-up…',
      waiting_tap: 'Waiting for your tap',
    });
  });
});
