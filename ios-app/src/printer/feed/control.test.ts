import { jsonResponse } from '../../testing/fakeHttp';
import { PrinterControl, SHEET_MS, controlKey } from './control';
import { PrinterFeed } from './feed';
import { PrinterSession } from './session';
import { settled, settleTimeoutMs } from './settle';
import { connectedFeed, fixture, type FakeFeed } from './testkit';
import { COMMAND_KINDS, type PrinterCommand } from './commands';
import { parsePrinterState, type PrinterState } from './wire';

const flush = async () => {
  for (let i = 0; i < 60; i++) await Promise.resolve();
};

/** A page with a pretend Jarvis, a clock and timers the test moves by hand. */
async function setup(over: (f: FakeFeed) => void = () => {}) {
  const { feed, service } = await connectedFeed(over);
  const session = new PrinterSession({ feed: new PrinterFeed(service) });
  let nowMs = 1_763_400_100_000;
  const timers: { at: number; fn: () => void; live: boolean }[] = [];
  let n = 0;
  const control = new PrinterControl({
    session,
    now: () => nowMs,
    uuid: () => `req-${++n}`,
    schedule: (fn, ms) => {
      const t = { at: nowMs + ms, fn, live: true };
      timers.push(t);
      return () => void (t.live = false);
    },
  });
  session.attach();
  for (let i = 0; i < 80 && session.state.get().kind !== 'live'; i++) await flush();
  const advance = (ms: number) => {
    nowMs += ms;
    for (const t of timers) {
      if (t.live && t.at <= nowMs) {
        t.live = false;
        t.fn();
      }
    }
  };
  const commands = () => feed.requests.filter((r) => r.path === '/v1/printer/command');
  const publish = async (name: string, arm: { armed: boolean; until: number | null } = { armed: true, until: 1_763_400_160_000 }, state: Record<string, unknown> = {}) => {
    const raw = fixture(name) as { state: Record<string, unknown> };
    feed.setState({ ...raw, arm, state: { ...raw.state, ...state } });
    for (let i = 0; i < 20; i++) await flush();
  };
  return { feed, session, control, advance, commands, publish };
}
const CANCEL: PrinterCommand = { kind: 'cancel' };

describe('Controls (C39 §7.1)', () => {
  it('on arms this phone and follows what Jarvis then says, with its clock offset for the countdown', async () => {
    const { feed, control, publish, session } = await setup();
    await publish('state-printing', { armed: false, until: null });
    expect(control.ui.get().armed).toBe(false);
    await control.setControls(true);
    expect(feed.requests.filter((r) => r.path === '/v1/printer/controls').map((r) => r.body)).toEqual([{ on: true }]);
    expect(control.ui.get().armed).toBe(true);
    await publish('state-printing', { armed: true, until: 1_763_400_160_000 });
    expect(control.ui.get()).toMatchObject({ armed: true, armUntilMs: 1_763_400_160_000 });
    expect(typeof control.ui.get().offsetMs).toBe('number');
    await publish('state-printing', { armed: false, until: null });
    expect(control.ui.get().armed).toBe(false);
    session.detach();
  });

  it('mayControl follows what Jarvis shares', async () => {
    const { feed, control, session } = await setup();
    expect(control.ui.get().mayControl).toBe(feed.shared.printer ? (feed.shared.printer as { control: boolean }).control : false);
    session.shared.set({ ...session.shared.get()!, control: false });
    expect(control.ui.get().mayControl).toBe(false);
    session.shared.set({ ...session.shared.get()!, control: true });
    expect(control.ui.get().mayControl).toBe(true);
    session.detach();
  });

  it('when the server says Controls are off the page turns them off at once, without waiting for the next answer', async () => {
    const { feed, control, publish, session } = await setup();
    await publish('state-printing');
    expect(control.ui.get().armed).toBe(true);
    feed.armed = false; // Jarvis's timer ended
    await control.send({ kind: 'pause' });
    expect(control.ui.get()).toMatchObject({ armed: false, armUntilMs: null, message: 'Controls turned off. Turn them on to continue.' });
    session.detach();
  });

  it('a printer that is not ready ends Controls at once', async () => {
    const { feed, control, publish, session } = await setup();
    await publish('state-printing', { armed: true, until: 1_763_400_160_000 }, { connection: 'klippy-shutdown' });
    expect(control.ui.get().armed).toBe(false);
    expect(feed.requests.filter((r) => r.path === '/v1/printer/controls').map((r) => r.body)).toEqual([{ on: false }]);
    session.detach();
  });

  it('Jarvis going away turns Controls off on the page and drops what was pending', async () => {
    const { feed, control, publish, session } = await setup();
    await publish('state-printing');
    expect(control.ui.get().armed).toBe(true);
    await control.setControls(true);
    await control.send({ kind: 'pause' });
    expect(control.ui.get().pending.has('pause')).toBe(true);
    feed.down = true;
    feed.release();
    session.state.set({ kind: 'away', last: null, why: 'x' });
    expect(control.ui.get().armed).toBe(false);
    expect(control.ui.get().pending.size).toBe(0);
    session.detach();
  });
});

describe('sending and the owner\'s yes (C39 §7.2)', () => {
  it('a plain command is sent once with the phone as its source and a request id, and its button waits for the change', async () => {
    const { control, commands, session, publish } = await setup();
    await publish('state-printing');
    await control.setControls(true);
    await control.send({ kind: 'pause' });
    expect(commands()).toHaveLength(1);
    expect(commands()[0].body).toMatchObject({ command: { kind: 'pause' }, via: 'phone', requestId: 'req-1' });
    expect(commands()[0].body).not.toHaveProperty('confirmed');
    expect(control.ui.get().pending.has('pause')).toBe(true);
    expect(control.ui.get().sheet).toBeNull();
    session.detach();
  });

  it('a command that needs a yes opens the sheet with the server\'s words and sends nothing yet; the yes sends it confirmed, with the same request id', async () => {
    const { control, commands, session, publish } = await setup();
    await publish('state-printing');
    await control.setControls(true);
    await control.send(CANCEL);
    const sheet = control.ui.get().sheet!;
    expect(sheet).toMatchObject({ question: 'Really cancel?', confirm: 'cancel', danger: true, keepLabel: 'Keep printing' });
    expect(commands().filter((r) => r.body?.confirmed).length).toBe(0);
    expect(control.ui.get().pending.size).toBe(0);
    await control.confirm();
    const sent = commands().filter((r) => r.body?.confirmed);
    expect(sent).toHaveLength(1);
    expect(sent[0].body).toMatchObject({ command: { kind: 'cancel' }, confirmed: true, requestId: sheet.requestId });
    expect(control.ui.get().sheet).toBeNull();
    expect(control.ui.get().pending.has('cancel')).toBe(true);
    session.detach();
  });

  it('keeping going closes the sheet and sends nothing; an unanswered sheet closes itself after 10 seconds', async () => {
    const { control, commands, session, publish, advance } = await setup();
    await publish('state-printing');
    await control.setControls(true);
    await control.send(CANCEL);
    control.dismissSheet();
    expect(control.ui.get().sheet).toBeNull();
    await control.confirm(); // nothing to confirm any more
    expect(commands().filter((r) => r.body?.confirmed)).toHaveLength(0);
    await control.send(CANCEL);
    expect(control.ui.get().sheet).not.toBeNull();
    expect(SHEET_MS).toBe(10_000);
    advance(9_999);
    expect(control.ui.get().sheet).not.toBeNull();
    advance(1);
    expect(control.ui.get().sheet).toBeNull();
    session.detach();
  });

  it('the other button is "Keep going" unless the confirm says Cancel; a home asks too', async () => {
    const { control, session, publish } = await setup();
    await publish('state-printing');
    await control.setControls(true);
    await control.send({ kind: 'home', axes: 'xyz' });
    expect(control.ui.get().sheet).toMatchObject({ confirm: 'home', keepLabel: 'Keep going', danger: false });
    session.detach();
  });

  it('the emergency stop works with Controls off, asks first, and its button says "Stop now"', async () => {
    const { control, commands, session, publish } = await setup();
    await publish('state-printing', { armed: false, until: null });
    await control.send({ kind: 'estop' });
    expect(control.ui.get().sheet).toMatchObject({ confirm: 'Stop now', danger: true });
    await control.confirm();
    expect(commands().filter((r) => r.body?.confirmed)).toHaveLength(1);
    expect(control.ui.get().message).toBeNull();
    session.detach();
  });

  it('with Controls off a command says so, and the page switches Controls off', async () => {
    const { control, session, publish } = await setup();
    await publish('state-printing', { armed: false, until: null });
    await control.send({ kind: 'pause' });
    expect(control.ui.get()).toMatchObject({ armed: false, message: 'Controls turned off. Turn them on to continue.' });
    expect(control.ui.get().pending.size).toBe(0);
    session.detach();
  });

  it("the server's refusal is kept under the control that asked, in its words, and cleared when it is asked again", async () => {
    const { feed, control, session, publish } = await setup();
    await publish('state-printing');
    await control.setControls(true);
    feed.onCommand = () => jsonResponse(fixture('error-refused'), 409);
    await control.send({ kind: 'jog', axis: 'x', distance: 10 });
    expect(control.ui.get().refused).toEqual({ jog: 'Home the printer first.' });
    expect(control.ui.get().message).toBe('Home the printer first.');
    feed.onCommand = null;
    await control.send({ kind: 'jog', axis: 'x', distance: 10 });
    expect(control.ui.get().refused).toEqual({});
    control.messageShown();
    expect(control.ui.get().message).toBeNull();
    session.detach();
  });

  it('a second press while one is being sent does nothing', async () => {
    const { control, commands, session, publish } = await setup();
    await publish('state-printing');
    await control.setControls(true);
    const a = control.send({ kind: 'pause' });
    const b = control.send({ kind: 'pause' });
    await Promise.all([a, b]);
    expect(commands()).toHaveLength(1);
    expect(control.ui.get().busy).toBe(false);
    session.detach();
  });

  it('a server that asks again after the yes is not answered with another yes', async () => {
    const { feed, control, commands, session, publish } = await setup();
    await publish('state-printing');
    await control.setControls(true);
    await control.send(CANCEL);
    feed.onCommand = () => jsonResponse({ error: { code: 'CONFIRM_REQUIRED', message: 'again' }, check: { allowed: true, risk: 'danger', question: 'Really?', confirm: 'cancel' } }, 409);
    await control.confirm();
    expect(control.ui.get().message).toBe('Jarvis asked again. Try again.');
    expect(control.ui.get().sheet).toBeNull();
    expect(commands().filter((r) => r.body?.confirmed)).toHaveLength(1);
    session.detach();
  });

  it('leaving the page closes the sheet, clears what was pending and turns Controls off here', async () => {
    const { control, session, publish } = await setup();
    await publish('state-printing');
    await control.setControls(true);
    await control.send({ kind: 'pause' });
    await control.send(CANCEL);
    control.detached();
    expect(control.ui.get()).toMatchObject({ armed: false, sheet: null, message: null, busy: false });
    expect(control.ui.get().pending.size).toBe(0);
    session.detach();
  });

  it('a command accepted by Jarvis is announced to whoever asked to know (E8)', async () => {
    const { control, session, publish } = await setup();
    await publish('state-printing');
    await control.setControls(true);
    const seen: string[] = [];
    control.onSent = (c) => seen.push(c.kind);
    await control.send({ kind: 'pause' });
    expect(seen).toEqual(['pause']);
    session.detach();
  });
});

describe('the pending button (C39 §7.3)', () => {
  it('shows until the printer shows the change', async () => {
    const { control, session, publish } = await setup();
    await publish('state-printing');
    await control.setControls(true);
    await control.send({ kind: 'pause' });
    expect(control.ui.get().pending.has('pause')).toBe(true);
    await publish('state-printing'); // not paused yet
    expect(control.ui.get().pending.has('pause')).toBe(true);
    await publish('state-paused');
    expect(control.ui.get().pending.has('pause')).toBe(false);
    session.detach();
  });

  it('gives up after the command\'s own time and never waits for a command with no time', async () => {
    const { control, session, publish, advance } = await setup();
    await publish('state-printing');
    await control.setControls(true);
    await control.send({ kind: 'pause' });
    advance(119_999);
    expect(control.ui.get().pending.has('pause')).toBe(true);
    advance(1);
    expect(control.ui.get().pending.has('pause')).toBe(false);
    await control.send({ kind: 'extrude', length: 5, speed: 2 });
    expect(control.ui.get().pending.size).toBe(0);
    session.detach();
  });

  it('control keys tell apart the controls of one kind', () => {
    expect(controlKey({ kind: 'home', axes: 'xy' })).toBe('home:xy');
    expect(controlKey({ kind: 'setTemp', heater: 'extruder', target: 200 })).toBe('setTemp:extruder');
    expect(controlKey({ kind: 'preset', name: 'PLA' })).toBe('preset:PLA');
    expect(controlKey({ kind: 'fan', fan: 'f', speed: 1 })).toBe('fan:f');
    expect(controlKey({ kind: 'macro', name: 'M' })).toBe('macro:M');
    expect(controlKey({ kind: 'jog', axis: 'x', distance: 1 })).toBe('jog');
  });
});

describe('the settle rules (a port of Jarvis\'s commandSettled)', () => {
  const st = (over: Record<string, unknown> = {}): PrinterState => {
    const raw = fixture('state-printing') as { state: Record<string, unknown> };
    return parsePrinterState({ ...raw.state, ...over });
  };
  const tool = (s: PrinterState, over: Partial<PrinterState['toolhead']>): PrinterState => ({ ...s, toolhead: { ...s.toolhead, ...over } });
  const info = (over = {}) => ({ elapsedMs: 0, sawMotion: false, presets: [], ...over });

  it('every kind has a time (0 means it does not wait), and the ones that wait are as Jarvis has them', () => {
    const t = (c: PrinterCommand) => settleTimeoutMs(c);
    expect(COMMAND_KINDS.length).toBe(33);
    expect([t({ kind: 'home', axes: 'xyz' }), t({ kind: 'pause' }), t({ kind: 'resume' }), t(CANCEL)]).toEqual([120_000, 120_000, 120_000, 120_000]);
    expect(t({ kind: 'start', path: 'a' })).toBe(60_000);
    expect(t({ kind: 'jog', axis: 'x', distance: 1 })).toBe(30_000);
    expect([t({ kind: 'setTemp', heater: 'e', target: 1 }), t({ kind: 'preset', name: 'p' }), t({ kind: 'heatersOff' })]).toEqual([15_000, 15_000, 15_000]);
    for (const c of [{ kind: 'light', level: 1 }, { kind: 'fan', fan: 'f', speed: 1 }, { kind: 'speedFactor', percent: 100 }, { kind: 'flowFactor', percent: 100 }, { kind: 'zOffset', delta: 0.1 }, { kind: 'queueAdd', path: 'a' }, { kind: 'queueRemove', jobId: '1' }, { kind: 'queueStart' }, { kind: 'queuePause' }] as PrinterCommand[]) expect(t(c)).toBe(10_000);
    for (const c of [{ kind: 'estop' }, { kind: 'restart' }, { kind: 'gcode', script: 'M115' }, { kind: 'macro', name: 'X' }, { kind: 'deleteFile', path: 'a' }, { kind: 'motorsOff' }] as PrinterCommand[]) expect(t(c)).toBe(0);
  });

  it('print state changes', () => {
    const printing = st();
    const paused = st({ print: { ...(fixture('state-printing') as { state: { print: object } }).state.print, state: 'paused', paused: true } });
    expect(settled({ kind: 'pause' }, printing, paused, info())).toBe(true);
    expect(settled({ kind: 'pause' }, printing, printing, info())).toBe(false);
    expect(settled({ kind: 'resume' }, paused, printing, info())).toBe(true);
    expect(settled({ kind: 'resume' }, paused, paused, info())).toBe(false);
    const done = st({ print: { ...(fixture('state-printing') as { state: { print: object } }).state.print, state: 'cancelled' } });
    expect(settled(CANCEL, printing, done, info())).toBe(true);
    expect(settled(CANCEL, printing, paused, info())).toBe(false);
    expect(settled({ kind: 'start', path: 'a' }, done, printing, info())).toBe(true);
    expect(settled({ kind: 'start', path: 'a' }, printing, done, info())).toBe(false);
  });

  it('homing needs the axes homed, the toolhead still, and either motion seen or 5 seconds', () => {
    const s = st();
    const homed = tool(s, { homed: 'xyz', liveVelocity: 0 });
    const c: PrinterCommand = { kind: 'home', axes: 'xyz' };
    expect(settled(c, s, homed, info({ sawMotion: true }))).toBe(true);
    expect(settled(c, s, homed, info({ elapsedMs: 4_999 }))).toBe(false);
    expect(settled(c, s, homed, info({ elapsedMs: 5_000 }))).toBe(true);
    expect(settled(c, s, tool(s, { homed: 'xy', liveVelocity: 0 }), info({ sawMotion: true }))).toBe(false);
    expect(settled(c, s, tool(s, { homed: 'xyz', liveVelocity: 5 }), info({ sawMotion: true }))).toBe(false);
    expect(settled({ kind: 'home', axes: 'z' }, s, tool(s, { homed: 'z', liveVelocity: 0.5 }), info({ sawMotion: true }))).toBe(true);
  });

  it('a jog is done when the toolhead is still at the new place', () => {
    const before = tool(st(), { position: [100, 90, 2], liveVelocity: 0 });
    const after = tool(st(), { position: [110, 90, 2], liveVelocity: 0 });
    expect(settled({ kind: 'jog', axis: 'x', distance: 10 }, before, after, info())).toBe(true);
    expect(settled({ kind: 'jog', axis: 'x', distance: 10 }, before, tool(after, { liveVelocity: 3 }), info())).toBe(false);
    expect(settled({ kind: 'jog', axis: 'x', distance: 10 }, before, tool(after, { position: [109.9, 90, 2] }), info())).toBe(false);
    expect(settled({ kind: 'jog', axis: 'y', distance: 5 }, before, tool(before, { position: [100, 95.04, 2] }), info())).toBe(true);
    expect(settled({ kind: 'jog', axis: 'z', distance: -1 }, before, tool(before, { position: [100, 90, 1] }), info())).toBe(true);
  });

  it('temperatures: a target reached, a preset\'s two targets, heaters off', () => {
    const s = st();
    const at = (e: number, b: number): PrinterState => ({ ...s, heaters: s.heaters.map((h) => ({ ...h, target: h.id === 'extruder' ? e : h.id === 'heater_bed' ? b : h.target })) });
    expect(settled({ kind: 'setTemp', heater: 'extruder', target: 200.4 }, s, at(200, 60), info())).toBe(true);
    expect(settled({ kind: 'setTemp', heater: 'extruder', target: 210 }, s, at(200, 60), info())).toBe(false);
    const presets = [{ name: 'PLA', extruder: 220, bed: 60 }];
    expect(settled({ kind: 'preset', name: 'PLA' }, s, at(220, 60), info({ presets }))).toBe(true);
    expect(settled({ kind: 'preset', name: 'PLA' }, s, at(220, 70), info({ presets }))).toBe(false);
    expect(settled({ kind: 'preset', name: 'Other' }, s, at(0, 0), info({ presets }))).toBe(true);
    expect(settled({ kind: 'heatersOff' }, s, at(0, 0), info())).toBe(true);
    expect(settled({ kind: 'heatersOff' }, s, at(0, 60), info())).toBe(false);
  });

  it('light, fans, factors and the z offset', () => {
    const s = st();
    expect(settled({ kind: 'light', level: 1 }, s, { ...s, light: 1 }, info())).toBe(true);
    expect(settled({ kind: 'light', level: 0.5 }, s, { ...s, light: 1 }, info())).toBe(false);
    expect(settled({ kind: 'light', level: 0.5 }, s, { ...s, light: null }, info())).toBe(false);
    const fan = s.fans[0];
    expect(settled({ kind: 'fan', fan: fan.id, speed: 0.85 }, s, s, info())).toBe(true);
    expect(settled({ kind: 'fan', fan: fan.id, speed: 0.2 }, s, s, info())).toBe(false);
    expect(settled({ kind: 'fan', fan: 'gone', speed: 0.2 }, s, s, info())).toBe(true);
    expect(settled({ kind: 'speedFactor', percent: 120 }, s, tool(s, { speedFactor: 1.2 }), info())).toBe(true);
    expect(settled({ kind: 'speedFactor', percent: 120 }, s, tool(s, { speedFactor: 1 }), info())).toBe(false);
    expect(settled({ kind: 'flowFactor', percent: 95 }, s, tool(s, { extrudeFactor: 0.95 }), info())).toBe(true);
    expect(settled({ kind: 'flowFactor', percent: 95 }, s, s, info())).toBe(false);
    expect(settled({ kind: 'zOffset', delta: 0.05 }, s, tool(s, { zOffset: s.toolhead.zOffset + 0.05 }), info())).toBe(true);
    expect(settled({ kind: 'zOffset', delta: 0.05 }, s, s, info())).toBe(false);
  });

  it('the queue', () => {
    const s = st();
    const q = (state: string, ids: string[]): PrinterState => ({ ...s, queue: { state, jobs: ids.map((id) => ({ id, filename: id, title: id, added: 1 })) } });
    expect(settled({ kind: 'queueAdd', path: 'a' }, q('ready', ['1']), q('ready', ['1', '2']), info())).toBe(true);
    expect(settled({ kind: 'queueAdd', path: 'a' }, q('ready', ['1']), q('ready', ['1']), info())).toBe(false);
    expect(settled({ kind: 'queueRemove', jobId: '1' }, q('ready', ['1']), q('ready', []), info())).toBe(true);
    expect(settled({ kind: 'queueRemove', jobId: '1' }, q('ready', ['1']), q('ready', ['1']), info())).toBe(false);
    expect(settled({ kind: 'queueRemove', jobId: '1' }, q('ready', ['1']), s, info())).toBe(true);
    expect(settled({ kind: 'queuePause' }, q('ready', []), q('paused', []), info())).toBe(true);
    expect(settled({ kind: 'queuePause' }, q('ready', []), q('ready', []), info())).toBe(false);
    expect(settled({ kind: 'queueStart' }, q('paused', []), q('ready', []), info())).toBe(true);
    expect(settled({ kind: 'queueStart' }, q('paused', []), q('paused', []), info())).toBe(false);
  });
});
