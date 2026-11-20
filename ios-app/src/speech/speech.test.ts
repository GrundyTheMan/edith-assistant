import { SettingsRepository, memoryStore } from '../settings/settings';
import { cleanForSpeech } from './cleanForSpeech';
import { SentenceChunker } from './sentenceChunker';
import { LIVE_REPLY, SpeechOutput, type TtsEngine } from './speechOutput';

function chunk(text: string, pieceSize = text.length): string[] {
  const c = new SentenceChunker();
  const out: string[] = [];
  for (let i = 0; i < text.length; i += pieceSize) out.push(...c.push(text.slice(i, i + pieceSize)));
  out.push(...c.flush());
  return out;
}

describe('SentenceChunker', () => {
  it('splits on . ! ? followed by a space, however the text is cut into deltas', () => {
    const text = 'Hello there, friend. How are you today? I am great! Thanks for asking.';
    const expected = ['Hello there, friend.', 'How are you today?', 'I am great!', 'Thanks for asking.'];
    expect(chunk(text)).toEqual(expected);
    expect(chunk(text, 1)).toEqual(expected);
    expect(chunk(text, 7)).toEqual(expected);
  });

  it('does not split abbreviations, initials, decimals or times', () => {
    expect(chunk('Dr. Smith met Mr. Jones at 3.5 percent.')).toEqual(['Dr. Smith met Mr. Jones at 3.5 percent.']);
    expect(chunk('It starts at 2:30 p.m. tomorrow, i.e. after lunch, e.g. at noon.')).toEqual([
      'It starts at 2:30 p.m. tomorrow, i.e. after lunch, e.g. at noon.',
    ]);
    expect(chunk('Ask J. R. R. Tolkien about it vs. his editor.')).toEqual(['Ask J. R. R. Tolkien about it vs. his editor.']);
    expect(chunk('Visit example.com for more. Then relax.')).toEqual(['Visit example.com for more.', 'Then relax.']);
  });

  it('handles ellipses and closing quotes', () => {
    expect(chunk('Well… that is odd. "Really?" she asked. Yes.')).toEqual(['Well… that is odd.', '"Really?" she asked.', 'Yes.']);
    expect(chunk('Hmm... maybe so. Okay then.')).toEqual(['Hmm... maybe so.', 'Okay then.']);
  });

  it('waits for the character after a full stop before deciding, and flushes the remainder', () => {
    const c = new SentenceChunker();
    expect(c.push('Done.')).toEqual([]); // could still be "Done.5"
    expect(c.push(' Next one')).toEqual([]);
    expect(c.flush()).toEqual(['Done. Next one']);
  });

  it('ends a sentence at a line break, so list items are spoken one by one', () => {
    expect(chunk('Here is the plan:\n- Buy milk today\n- Walk the dog now\nEnjoy.')).toEqual([
      'Here is the plan:',
      '- Buy milk today',
      '- Walk the dog now',
      'Enjoy.',
    ]);
  });

  it('joins a one-word piece ("Sure.", "1.") to the next sentence', () => {
    expect(chunk('Sure. I can do that for you.')).toEqual(['Sure. I can do that for you.']);
    expect(chunk('1. Buy milk\n2. Walk the dog')).toEqual(['1. Buy milk', '2. Walk the dog']);
    expect(chunk('Yes.')).toEqual(['Yes.']); // a lone one-word reply is still spoken
  });

  it('cuts a long sentence at the first comma/semicolon/colon/dash after 25 characters (04-lessons L15)', () => {
    const long = 'When you get a moment this afternoon, please pick up the groceries and the dry cleaning before the stores close tonight.';
    const parts = chunk(long);
    expect(parts[0]).toBe('When you get a moment this afternoon,');
    expect(parts.join(' ')).toBe(long);
    expect(chunk('Short, but fine.')).toEqual(['Short, but fine.']);
  });

  it('cuts a long streaming sentence before its end arrives', () => {
    const c = new SentenceChunker();
    const first = c.push('When you get a moment this afternoon, please pick up the groceries and the dry cleaning before the stores close tonight and ');
    expect(first).toEqual(['When you get a moment this afternoon,']);
  });
});

describe('cleanForSpeech', () => {
  it('removes Markdown, keeping the words', () => {
    expect(cleanForSpeech('# Title\n**Bold** and _soft_ and `code`')).toBe('Title Bold and soft and code');
    expect(cleanForSpeech('> quoted\n- item one\n* item two\n• item three')).toBe('quoted item one item two item three');
    expect(cleanForSpeech('~~gone~~ stays')).toBe('gone stays');
  });

  it('turns links into their text and URLs into "a link", keeping the full stop', () => {
    expect(cleanForSpeech('See [the docs](https://example.com/a?b=1) now')).toBe('See the docs now');
    expect(cleanForSpeech('Go to https://example.com/page.')).toBe('Go to a link.');
    expect(cleanForSpeech('Try www.example.com, then stop')).toBe('Try a link, then stop');
  });

  it('removes emoji and joiners but keeps accented letters and punctuation', () => {
    expect(cleanForSpeech('Great 😀👍🏽 job ❤️ café — naïve!')).toBe('Great job café — naïve!');
  });

  it('collapses whitespace', () => {
    expect(cleanForSpeech('  a \n\n  b\t c  ')).toBe('a b c');
    expect(cleanForSpeech('**')).toBe('');
  });
});

// --- SpeechOutput with a fake voice ---------------------------------------------------------------------------------

interface Spoken {
  text: string;
  rate: number;
  finish(): void;
  stop(): void;
}

function fakeVoice() {
  const spoken: Spoken[] = [];
  const tts: TtsEngine = {
    speak: (text, rate, handlers) => spoken.push({ text, rate, finish: handlers.onDone, stop: handlers.onStopped }),
    stop: jest.fn(),
  };
  return { tts, spoken };
}

async function speechFixture(speakReplies = true, open = true) {
  const settings = new SettingsRepository(memoryStore());
  await settings.update((s) => ({ ...s, speakReplies, speechRate: 1.2 }));
  const { tts, spoken } = fakeVoice();
  const speech = new SpeechOutput(tts, settings, () => open);
  return { speech, spoken, tts, settings };
}

describe('SpeechOutput', () => {
  it('speaks a streaming reply sentence by sentence, in order, at the saved speed', async () => {
    const { speech, spoken } = await speechFixture();
    speech.onDelta(1, 'Hello there. How');
    expect(speech.speakingMessageId.get()).toBe(LIVE_REPLY);
    expect(spoken.map((s) => s.text)).toEqual(['Hello there.']);
    speech.onDelta(1, ' are you? Fine');
    speech.onFinished(1, 'Hello there. How are you? Fine', 42);
    expect(spoken.map((s) => s.text)).toEqual(['Hello there.', 'How are you?', 'Fine']);
    expect(spoken.every((s) => s.rate === 1.2)).toBe(true);
    expect(speech.speakingMessageId.get()).toBe(42); // the stored message's Play button lights up
    spoken.forEach((s) => s.finish());
    expect(speech.speakingMessageId.get()).toBeNull();
  });

  it('cleans Markdown before speaking and skips what is left empty', async () => {
    const { speech, spoken } = await speechFixture();
    speech.onDelta(1, '**Great** news! 😀 ');
    speech.onFinished(1, '', 1);
    expect(spoken.map((s) => s.text)).toEqual(['Great news!']);
  });

  it('Stop silences the rest of the streaming reply; the next reply speaks again', async () => {
    const { speech, spoken, tts } = await speechFixture();
    speech.onDelta(1, 'First sentence here. Second');
    speech.stop();
    expect(tts.stop).toHaveBeenCalled();
    expect(speech.speakingMessageId.get()).toBeNull();
    speech.onDelta(1, ' sentence follows. More');
    speech.onFinished(1, 'x', 5);
    expect(spoken.map((s) => s.text)).toEqual(['First sentence here.']);
    speech.onDelta(1, 'Brand new reply here. And');
    expect(spoken.map((s) => s.text)).toEqual(['First sentence here.', 'Brand new reply here.']);
  });

  it('ignores a late callback from a stopped utterance (it must not clear a newer one)', async () => {
    const { speech, spoken } = await speechFixture();
    speech.play(10, 'Old message to read.');
    const old = spoken[0];
    speech.play(11, 'Newer message to read.');
    expect(speech.speakingMessageId.get()).toBe(11);
    old.stop(); // arrives late, from the generation that was stopped
    old.finish();
    expect(speech.speakingMessageId.get()).toBe(11);
    spoken[1].finish();
    expect(speech.speakingMessageId.get()).toBeNull();
  });

  it('with "Speak Edith\'s replies" off: no automatic speech, but Play still works', async () => {
    const { speech, spoken } = await speechFixture(false);
    speech.onDelta(1, 'Quiet reply here. More');
    speech.onFinished(1, 'Quiet reply here. More', 3);
    expect(spoken).toHaveLength(0);
    expect(speech.speakingMessageId.get()).toBeNull();
    speech.play(3, 'Quiet reply here. More');
    expect(spoken.map((s) => s.text)).toEqual(['Quiet reply here.', 'More']);
    expect(speech.speakingMessageId.get()).toBe(3);
  });

  it('speaks only the open conversation\'s reply', async () => {
    const { speech, spoken } = await speechFixture(true, false);
    speech.onDelta(7, 'Hidden reply text. More');
    speech.onFinished(7, 'x', 9);
    expect(spoken).toHaveLength(0);
    expect(speech.speakingMessageId.get()).toBeNull();
  });

  it('a stopped Play clears its highlight; Play of an empty message clears at once; Test voice speaks', async () => {
    const { speech, spoken } = await speechFixture();
    speech.play(1, 'Some text to read out.');
    speech.stop();
    expect(speech.speakingMessageId.get()).toBeNull();
    speech.play(2, '😀');
    expect(speech.speakingMessageId.get()).toBeNull();
    speech.test();
    expect(spoken[spoken.length - 1].text).toBe("Hi, I'm Edith.");
  });

  it('a cancelled or failed reply stops speaking', async () => {
    const { speech, tts } = await speechFixture();
    speech.onDelta(1, 'Part of a reply. And');
    speech.onCancelled();
    expect(tts.stop).toHaveBeenCalled();
    expect(speech.speakingMessageId.get()).toBeNull();
    speech.onDelta(1, 'Another reply here. And');
    speech.onFailed();
    expect(speech.speakingMessageId.get()).toBeNull();
  });
});
