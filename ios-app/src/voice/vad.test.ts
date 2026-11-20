import { SpeechDetector, VAD, thresholdFor, type VadEvent } from './vad';

const SAMPLES_PER_S = 10;
/** `n` samples of one level. */
const run = (level: number, n: number) => Array.from({ length: n }, () => level);
/** Feeds the levels and returns each event with the sample index it happened at. */
function feed(levels: number[], options?: ConstructorParameters<typeof SpeechDetector>[0]) {
  const vad = new SpeechDetector(options);
  const events: [number, VadEvent][] = [];
  levels.forEach((l, i) => {
    const e = vad.push(l);
    if (e) events.push([i, e]);
  });
  return { vad, events };
}
const QUIET = run(0.02, 7); // the 700 ms the room's floor is measured over

describe('SpeechDetector', () => {
  it('hears nothing in silence', () => {
    expect(feed([...QUIET, ...run(0.02, 100)]).events).toEqual([]);
  });

  it('does not decide anything while it measures the room', () => {
    const { events, vad } = feed(run(0.9, 6));
    expect(events).toEqual([]);
    expect(vad.calibrating).toBe(true);
  });

  it('finds the start of speech after 250 ms above the threshold, and its end after the pause', () => {
    const { events } = feed([...QUIET, ...run(0.6, 10), ...run(0.02, 12)]);
    expect(events).toEqual([
      [9, 'speech_start'], // the third loud sample: 300 ms
      [27, 'speech_end'], // the 11th quiet sample (1100 ms) after the last loud one (index 16)
    ]);
  });

  it('ignores a cough of 150 ms', () => {
    const { events, vad } = feed([...QUIET, ...run(0.7, 1), ...run(0.02, 2), ...run(0.7, 1), ...run(0.02, 30)]);
    expect(events).toEqual([]);
    expect(vad.isSpeaking).toBe(false);
  });

  it('does not end speech at a pause of 600 ms inside it', () => {
    const { events } = feed([...QUIET, ...run(0.6, 6), ...run(0.02, 6), ...run(0.6, 6), ...run(0.02, 12)]);
    expect(events.map(([, e]) => e)).toEqual(['speech_start', 'speech_end']);
    expect(events[1][0]).toBeGreaterThan(7 + 6 + 6 + 6 + 8);
  });

  it('drops an utterance with less than 400 ms of speech as a false start', () => {
    const { events } = feed([...QUIET, ...run(0.6, 3), ...run(0.02, 15)]);
    expect(events.map(([, e]) => e)).toEqual(['speech_start', 'false_start']);
  });

  it('never starts in constant noise, because the floor rises with it', () => {
    expect(feed(run(0.6, 200)).events).toEqual([]);
    // ... but speech over that noise does start: the threshold is floor + margin.
    const { events } = feed([...run(0.3, 7), ...run(0.62, 6), ...run(0.3, 14)]);
    expect(events.map(([, e]) => e)).toEqual(['speech_start', 'speech_end']);
    expect(feed([...run(0.3, 7), ...run(0.35, 30)]).events).toEqual([]); // louder than the floor, below floor + margin
  });

  it('starts again for a second utterance after the first ended', () => {
    const one = [...run(0.6, 6), ...run(0.02, 12)];
    const { events } = feed([...QUIET, ...one, ...one]);
    expect(events.map(([, e]) => e)).toEqual(['speech_start', 'speech_end', 'speech_start', 'speech_end']);
  });

  it('shifts the threshold with sensitivity', () => {
    const quietSpeech = [...QUIET, ...run(0.2, 8), ...run(0.02, 12)];
    expect(feed(quietSpeech, { sensitivity: 0.5 }).events.map(([, e]) => e)).toEqual(['speech_start', 'speech_end']);
    expect(feed(quietSpeech, { sensitivity: 0 }).events).toEqual([]); // 0.2 is under 0.225
    const barely = [...QUIET, ...run(0.1, 8), ...run(0.02, 12)];
    expect(feed(barely, { sensitivity: 0.5 }).events).toEqual([]);
    expect(feed(barely, { sensitivity: 1 }).events.map(([, e]) => e)).toEqual(['speech_start', 'speech_end']);
    expect(thresholdFor(0.02, 0.5)).toBeCloseTo(0.15);
    expect(thresholdFor(0.3, 0.5)).toBeCloseTo(0.42);
    expect(thresholdFor(0.3, 1)).toBeCloseTo(0.36);
    expect(thresholdFor(0.02, 0)).toBeCloseTo(0.225);
  });

  it('waits as long as the pause length says', () => {
    const talk = [...QUIET, ...run(0.6, 6)];
    const long = feed([...talk, ...run(0.02, 15)], { endSilenceMs: 2000 });
    expect(long.events.map(([, e]) => e)).toEqual(['speech_start']);
    const longer = feed([...talk, ...run(0.02, 21)], { endSilenceMs: 2000 });
    expect(longer.events.map(([, e]) => e)).toEqual(['speech_start', 'speech_end']);
    const short = feed([...talk, ...run(0.02, 8)], { endSilenceMs: 700 });
    expect(short.events.map(([, e]) => e)).toEqual(['speech_start', 'speech_end']);
  });

  it('keeps its numbers in one place', () => {
    expect(VAD.sampleMs * SAMPLES_PER_S).toBe(1000);
    expect([VAD.calibrateMs, VAD.startMs, VAD.minSpeechMs, VAD.endSilenceMs, VAD.maxUtteranceMs]).toEqual([700, 250, 400, 1100, 60_000]);
  });
});
