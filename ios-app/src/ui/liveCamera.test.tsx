import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { AppState } from 'react-native';
import { FRAME_GAP_MS, LiveFrames, MAX_FAILURES, type Frame } from './LiveCamera';

const flush = async () => {
  for (let i = 0; i < 30; i++) await Promise.resolve();
};
const frame = (n: number, takenAt = 1_000_000): Frame => ({ jpegBase64: `FRAME${n}`, takenAt });
const uriOf = () => (screen.getByLabelText('Printer camera').props.source as { uri: string }).uri;

/** A camera that answers from a script, and waits that only end when the test says so. */
function harness(script: (Frame | Error)[]) {
  const asked: AbortSignal[] = [];
  const gaps: number[] = [];
  const waiting: (() => void)[] = [];
  const next = jest.fn(async (signal: AbortSignal) => {
    asked.push(signal);
    const s = script.length > 1 ? script.shift()! : script[0];
    if (s instanceof Error) throw s;
    return { ...s }; // a new picture each time, as a real camera gives
  });
  const delay = (ms: number) =>
    new Promise<void>((resolve) => {
      gaps.push(ms);
      waiting.push(resolve);
    });
  const tickGap = async () => {
    waiting.shift()?.();
    await flush();
  };
  return { next, delay, asked, gaps, tickGap, pending: () => waiting.length };
}

describe('LiveFrames', () => {
  it('shows the first picture, then replaces it as new ones arrive, asking again only after each one is on screen', async () => {
    const h = harness([frame(1), frame(2), frame(3)]);
    await render(<LiveFrames name="Printer" next={h.next} delay={h.delay} onClose={() => {}} now={() => 1_000_000} />);
    await flush();
    expect(uriOf()).toContain('FRAME1');
    expect(h.next).toHaveBeenCalledTimes(1); // not asked again until the gap is over
    expect(h.gaps).toEqual([FRAME_GAP_MS]);
    await act(async () => h.tickGap());
    expect(uriOf()).toContain('FRAME2');
    await act(async () => h.tickGap());
    expect(uriOf()).toContain('FRAME3');
    expect(h.next).toHaveBeenCalledTimes(3);
  });

  it('says it is live and how old the picture is', async () => {
    const h = harness([frame(1, 1_000_000)]);
    let t = 1_000_500;
    await render(<LiveFrames name="Printer" next={h.next} delay={h.delay} onClose={() => {}} now={() => t} />);
    await flush();
    expect(screen.getByText('Live · updated just now')).toBeTruthy();
    t = 1_007_000;
    await act(async () => h.tickGap());
    expect(screen.getByText('Live · updated 7 s ago')).toBeTruthy();
  });

  it('stops asking when the window closes, and cancels the request in flight', async () => {
    const h = harness([frame(1), frame(2)]);
    const { unmount } = await render(<LiveFrames name="Printer" next={h.next} delay={h.delay} onClose={() => {}} />);
    await flush();
    expect(h.asked).toHaveLength(1);
    await unmount();
    expect(h.asked[0].aborted).toBe(true);
    await act(async () => h.tickGap());
    expect(h.next).toHaveBeenCalledTimes(1); // the loop ended; nothing asks again
  });

  it('a hiccup keeps the last picture on screen and tries again, then carries on', async () => {
    const h = harness([frame(1), new Error('The camera answered HTTP 404.'), frame(2)]);
    await render(<LiveFrames name="Printer" next={h.next} delay={h.delay} onClose={() => {}} now={() => 1_000_000} />);
    await flush();
    await act(async () => h.tickGap()); // the next ask fails
    expect(uriOf()).toContain('FRAME1');
    expect(screen.getByText('The camera answered HTTP 404. Trying again…')).toBeTruthy();
    await act(async () => h.tickGap());
    expect(uriOf()).toContain('FRAME2');
    expect(screen.queryByText(/Trying again/)).toBeNull();
  });

  it('three failures in a row stop it with the reason and a Retry button; Retry starts it again', async () => {
    const boom = new Error('The printer is not reachable.');
    const h = harness([boom, boom, boom, frame(9)]);
    await render(<LiveFrames name="Printer" next={h.next} delay={h.delay} onClose={() => {}} />);
    await flush();
    for (let i = 0; i < MAX_FAILURES - 1; i++) await act(async () => h.tickGap());
    expect(h.next).toHaveBeenCalledTimes(MAX_FAILURES);
    expect(screen.getByText('The printer is not reachable.')).toBeTruthy();
    await act(async () => h.tickGap());
    expect(h.next).toHaveBeenCalledTimes(MAX_FAILURES); // it stopped
    await fireEvent.press(screen.getByLabelText('Retry'));
    await flush();
    expect(h.next).toHaveBeenCalledTimes(MAX_FAILURES + 1);
    expect(uriOf()).toContain('FRAME9');
  });

  it('the Close button closes it', async () => {
    const onClose = jest.fn();
    const h = harness([frame(1)]);
    await render(<LiveFrames name="Printer" next={h.next} delay={h.delay} onClose={onClose} />);
    await flush();
    await fireEvent.press(screen.getByLabelText('Close'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('pauses while the app is in the background and picks up again when it returns', async () => {
    const handlers: ((s: string) => void)[] = [];
    jest.spyOn(AppState, 'addEventListener').mockImplementation(((_: string, handler: (s: string) => void) => {
      handlers.push(handler);
      return { remove: () => {} };
    }) as never);
    const h = harness([frame(1), frame(2)]);
    await render(<LiveFrames name="Printer" next={h.next} delay={h.delay} onClose={() => {}} />);
    await flush();
    expect(h.next).toHaveBeenCalledTimes(1);
    await act(async () => handlers[0]('background'));
    expect(h.asked[0].aborted).toBe(true);
    expect(screen.getByText('Paused while Edith is in the background')).toBeTruthy();
    await act(async () => h.tickGap());
    expect(h.next).toHaveBeenCalledTimes(1);
    await act(async () => handlers[0]('active'));
    await flush();
    expect(h.next).toHaveBeenCalledTimes(2);
    jest.restoreAllMocks();
  });
});
