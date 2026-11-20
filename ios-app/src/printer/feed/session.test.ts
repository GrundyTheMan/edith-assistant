import { jsonResponse } from '../../testing/fakeHttp';
import { PrinterFeed } from './feed';
import { TOO_MANY_WAIT_MS, WAIT_S, backoff, PrinterSession, type PrinterScreenState } from './session';
import { FEED_HOST, FEED_PORT, connectedFeed, fixture } from './testkit';
import { PrinterWords } from './words';

const flush = async () => {
  for (let i = 0; i < 6; i++) await new Promise<void>((r) => setTimeout(r, 0));
};

/** A session over the pretend feed, with waits the test ends by hand and a clock it controls. */
async function setup(over?: Parameters<typeof connectedFeed>[0]) {
  const c = await connectedFeed(over);
  const waits: { ms: number; end: () => void }[] = [];
  const waited: number[] = [];
  let t = 1_763_400_000_000;
  const session = new PrinterSession({
    feed: new PrinterFeed(c.service),
    now: () => t,
    delay: (ms, signal) =>
      new Promise<void>((resolve) => {
        waited.push(ms);
        const end = () => resolve();
        waits.push({ ms, end });
        signal.addEventListener('abort', end);
      }),
  });
  const stateRequests = () => c.feed.requests.filter((r) => r.path === '/v1/printer/state');
  /** Ends the oldest wait (the back-off passed). */
  const tick = async () => {
    waits.shift()?.end();
    await flush();
  };
  return { ...c, session, waited, stateRequests, tick, setNow: (n: number) => (t = n), pendingWaits: () => waits.length, kind: () => session.state.get().kind };
}

describe('PrinterSession (the same cases as Android\'s PrinterSessionTest)', () => {
  it('the first question has no revision and does not wait; the next waits for a change and publishes it', async () => {
    const { session, feed, stateRequests } = await setup((f) => (f.hold = true));
    session.attach();
    await flush();
    expect(stateRequests()[0].query).toEqual({});
    expect(session.state.get()).toMatchObject({ kind: 'live', stale: false });
    expect(stateRequests()[1].query).toMatchObject({ after: '57', epoch: '1763400000', wait: String(WAIT_S) });
    expect(feed.heldPolls()).toBe(1);
    feed.setState({ ...fixture('state-paused') });
    await flush();
    const s = session.state.get() as Extract<PrinterScreenState, { kind: 'live' }>;
    expect(s.data.state.print.state).toBe('paused');
    session.detach();
  });

  it('an unchanged revision is Jarvis saying nothing new and changes nothing', async () => {
    const { session, feed } = await setup((f) => (f.hold = true));
    session.attach();
    await flush();
    const before = session.state.get();
    feed.release(); // Jarvis's own timeout: the same rev
    await flush();
    expect(session.state.get()).toBe(before); // the very same state object: nothing was published
    session.detach();
  });

  it('a new epoch means Jarvis restarted: take the answer even with a lower revision, and send the new epoch', async () => {
    const { session, feed, stateRequests } = await setup((f) => (f.hold = true));
    session.attach();
    await flush();
    feed.answer = { ...fixture('state-ready-idle'), rev: 3, epoch: 1763500000 }; // lower rev, new epoch
    feed.release();
    await flush();
    expect((session.state.get() as { data: { rev: number } }).data.rev).toBe(3);
    expect(stateRequests().at(-1)!.query).toMatchObject({ after: '3', epoch: '1763500000' });
    session.detach();
  });

  it('the offset is Jarvis\'s clock minus the phone\'s', async () => {
    const { session, setNow } = await setup();
    setNow(1763400000000 - 2500); // the phone is 2.5 s behind
    session.attach();
    await flush();
    const s = session.state.get() as Extract<PrinterScreenState, { kind: 'live' }>;
    expect(s.offsetMs).toBe(s.data.serverTime - s.receivedAt);
    expect(s.receivedAt).toBe(1763400000000 - 2500);
    session.detach();
  });

  it('quiet is sent when the page says so', async () => {
    const { session, stateRequests } = await setup((f) => (f.hold = true));
    session.quiet = true;
    session.attach();
    await flush();
    expect(stateRequests()[0].query.quiet).toBe('1');
    session.detach();
  });
});

describe('what the session shows when it cannot show the printer', () => {
  it('no Jarvis and not shared are shown, and nothing more is asked', async () => {
    const a = await setup();
    await a.service.disconnect();
    a.session.attach();
    await flush();
    expect(a.session.state.get()).toEqual({ kind: 'noJarvis' });
    expect(a.stateRequests()).toHaveLength(0);

    const b = await setup((f) => (f.shared = { pcName: 'BEN-PC', mail: [], calendars: [], tasks: [] }));
    b.session.attach();
    await flush();
    expect(b.session.state.get()).toEqual({ kind: 'notShared' });
    expect(b.stateRequests()).toHaveLength(0);
  });

  it('Jarvis stopping the share ends the loop', async () => {
    const { session, feed, stateRequests } = await setup((f) => (f.hold = true));
    session.attach();
    await flush();
    feed.stateReply = () => jsonResponse({ error: { code: 'PRINTER_NOT_SHARED', message: 'x' } }, 404);
    feed.setState({ ...fixture('state-ready-idle') }); // wakes the held poll, then the next ask is refused
    await flush();
    expect(session.state.get()).toEqual({ kind: 'notShared' });
    const n = stateRequests().length;
    await flush();
    expect(stateRequests()).toHaveLength(n);
  });

  it('a removed phone is told to pair again and the loop ends', async () => {
    const { session, feed, service, stateRequests, pendingWaits, tick } = await setup((f) => (f.hold = true));
    session.attach();
    await flush();
    feed.tokens.clear();
    feed.release();
    await flush();
    expect(session.state.get()).toMatchObject({ kind: 'away', why: PrinterWords.UNAUTHORIZED });
    expect(service.state.get().kind).toBe('needs_sign_in');
    const n = stateRequests().length;
    expect(pendingWaits()).toBe(0); // nothing is waiting to try again
    await tick();
    await flush();
    expect(stateRequests()).toHaveLength(n); // no loop
  });

  it('when Jarvis cannot be reached the last page stays, and the wait grows 2, 4, 8 up to 30 seconds', async () => {
    const { session, feed, waited, tick } = await setup((f) => (f.hold = true));
    session.attach();
    await flush();
    const live = session.state.get();
    feed.down = true;
    feed.release();
    await flush();
    const away = session.state.get() as Extract<PrinterScreenState, { kind: 'away' }>;
    expect(away).toMatchObject({ kind: 'away', why: PrinterWords.UNREACHABLE });
    expect(away.last).toBe((live as Extract<PrinterScreenState, { kind: 'live' }>).data); // the last page is kept
    expect(waited).toEqual([2000]);
    for (let i = 0; i < 6; i++) await tick();
    expect(waited).toEqual([2000, 4000, 8000, 16000, 30000, 30000, 30000]);
    session.detach();
  });

  it('recovery shows the printer again and starts the waits over', async () => {
    const { session, feed, waited, tick } = await setup((f) => (f.hold = true));
    session.attach();
    await flush();
    feed.down = true;
    feed.release();
    await flush();
    await tick();
    await tick();
    expect(waited).toEqual([2000, 4000, 8000]);
    feed.down = false;
    await tick();
    expect(session.state.get()).toMatchObject({ kind: 'live' });
    feed.down = true;
    feed.release();
    await flush();
    expect(waited.at(-1)).toBe(2000); // started over
    session.detach();
  });

  it('too many requests waits 3 seconds and goes on', async () => {
    const { session, feed, waited, tick } = await setup((f) => (f.hold = true));
    session.attach();
    await flush();
    feed.stateReply = () => jsonResponse({ error: { code: 'TOO_MANY', message: 'Slow.' } }, 429);
    feed.release();
    await flush();
    expect(waited).toEqual([TOO_MANY_WAIT_MS]);
    expect(session.state.get().kind).toBe('live'); // no banner for this
    feed.stateReply = null;
    await tick();
    expect(session.state.get().kind).toBe('live');
    session.detach();
  });

  it('an unreachable Jarvis at the start is shown, then tried again', async () => {
    const { session, feed, tick, waited } = await setup((f) => (f.down = true));
    session.attach();
    await flush();
    expect(session.state.get()).toEqual({ kind: 'away', last: null, why: PrinterWords.UNREACHABLE });
    expect(waited).toEqual([2000]);
    feed.down = false;
    await tick();
    expect(session.state.get().kind).toBe('live');
    session.detach();
  });

  it('a phone Jarvis has removed at the start is not retried', async () => {
    const { session, feed, waited, service } = await setup((f) => f.tokens.clear());
    session.attach();
    await flush();
    expect(session.state.get()).toMatchObject({ kind: 'away', why: PrinterWords.UNAUTHORIZED });
    expect(waited).toEqual([]);
    expect(service.state.get().kind).toBe('needs_sign_in');
    expect(feed.requests.filter((r) => r.path === '/v1/printer/state')).toHaveLength(0);
  });
});

describe('leaving and coming back', () => {
  it('leaving stops the asking, and coming back shows the old page marked stale until a new answer', async () => {
    const { session, feed, stateRequests } = await setup((f) => (f.hold = true));
    session.attach();
    await flush();
    session.detach();
    expect(session.state.get()).toMatchObject({ kind: 'live', stale: true });
    expect(feed.heldPolls()).toBe(0); // the held poll was cancelled at once
    const n = stateRequests().length;
    feed.setState({ ...fixture('state-paused') }); // changes while away: nothing is asking
    await flush();
    expect(stateRequests()).toHaveLength(n);

    session.attach();
    expect(session.state.get()).toMatchObject({ kind: 'live', stale: true }); // the old page, marked stale, at once
    await flush();
    const s = session.state.get() as Extract<PrinterScreenState, { kind: 'live' }>;
    expect(s.stale).toBe(false);
    expect(s.data.state.print.state).toBe('paused'); // the new answer
    session.detach();
  });

  it('leaving turns Controls off only when they are on', async () => {
    const on = await setup((f) => (f.hold = true)); // the printing fixture has Controls armed
    on.session.attach();
    await flush();
    on.session.detach();
    await flush();
    expect(on.feed.requests.some((r) => r.path === '/v1/printer/controls' && r.body?.on === false)).toBe(true);

    const off = await setup((f) => {
      f.hold = true;
      f.answer = fixture('state-ready-idle');
    });
    off.session.attach();
    await flush();
    off.session.detach();
    await flush();
    expect(off.feed.requests.some((r) => r.path === '/v1/printer/controls')).toBe(false);
  });

  it('refreshNow asks again at once without a revision', async () => {
    const { session, feed, stateRequests } = await setup((f) => (f.hold = true));
    session.attach();
    await flush();
    expect(feed.heldPolls()).toBe(1);
    session.refreshNow();
    await flush();
    const qs = stateRequests().map((r) => r.query);
    expect(qs.at(-2)).toEqual({}); // the new loop's first question
    expect(feed.heldPolls()).toBe(1); // the old held poll is gone, a new one is held
    session.detach();
  });

  it('refreshNow does nothing while the page is away', async () => {
    const { session, stateRequests } = await setup();
    session.refreshNow();
    await flush();
    expect(stateRequests()).toHaveLength(0);
  });

  it('updates the presets at once', async () => {
    const { session } = await setup();
    session.attach();
    await flush();
    session.updatePresets([{ name: 'ABS', extruder: 250, bed: 100 }]);
    expect(session.shared.get()?.presets).toEqual([{ name: 'ABS', extruder: 250, bed: 100 }]);
    session.detach();
  });

  it('the waits are 2 seconds doubling to 30', () => {
    expect([1, 2, 3, 4, 5, 6, 7, 50].map(backoff)).toEqual([2000, 4000, 8000, 16000, 30000, 30000, 30000, 30000]);
    expect(FEED_HOST + FEED_PORT).toBeTruthy();
  });
});
