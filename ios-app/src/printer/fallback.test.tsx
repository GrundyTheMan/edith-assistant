import { render, screen } from '@testing-library/react-native';
import { memorySecrets } from '../core/secrets';
import { printerWords } from '../home/actions';
import { Home } from '../home/home';
import type { DeviceState, HomeDevice } from '../home/model';
import type { HttpFetch, HttpResponse } from '../ollama/client';
import { memoryStore } from '../settings/settings';
import { FakeClock } from '../testing/nodeSqlite';
import { ToolArgs } from '../tools/framework/toolArgs';
import { PrinterJarvisNote, PRINTER_JARVIS_NOTE } from '../ui/PrinterJarvisNote';
import { PrinterCard } from '../ui/PrinterCard';
import { themes } from '../ui/theme';
import { useServices } from '../core/services';
import { PrinterFeed } from './feed/feed';
import { deviceStateFromFeed, FeedPrinterRead, type JarvisPrinterRead } from './feed/toHome';
import { connectedFeed, fixture } from './feed/testkit';
import { parsePrinterState, type PrinterState } from './feed/wire';
import { PrinterService } from './service';
import { extras, printerStatusTool } from './tools';

jest.mock('../core/services', () => ({ useServices: jest.fn(), useStore: jest.fn() }));

const feedState = (name = 'state-printing', over: Record<string, unknown> = {}): PrinterState => {
  const raw = fixture(name) as { state: Record<string, unknown> };
  return parsePrinterState({ ...raw.state, ...over });
};

describe('Jarvis\'s state as a Home state (as Android\'s PrinterToHome)', () => {
  it('a print in progress', () => {
    const d = deviceStateFromFeed(feedState())!;
    expect(d.print).toMatchObject({ state: 'printing', title: 'Chimney and Platforms (plate 1)', progress: 0.4, timeLeftSec: 2700, layer: { current: 12, total: 240 }, busy: true, viaJarvis: true });
    expect(d.heaters!.nozzle).toMatchObject({ temperature: 219.8, target: 220 });
    expect(d.heaters!.bed).toMatchObject({ temperature: 60, target: 60 });
  });
  it('idle has no time left and is not busy', () => {
    const d = deviceStateFromFeed(feedState('state-ready-idle'))!;
    expect(d.print).toMatchObject({ state: 'standby', busy: false, timeLeftSec: null, viaJarvis: true });
  });
  it('complete, cancelled and error are named; an unknown state is idle', () => {
    for (const [s, want] of [['complete', 'complete'], ['cancelled', 'cancelled'], ['error', 'error'], ['homing', 'standby']] as const) {
      const st = feedState();
      st.print = { ...st.print, state: s };
      expect(deviceStateFromFeed(st)!.print!.state).toBe(want);
    }
  });
  it("the printer's alert is the message when there is no other, and the job's own message wins", () => {
    const withAlert = feedState('state-alert');
    withAlert.print = { ...withAlert.print, message: null };
    expect(deviceStateFromFeed(withAlert)!.print!.message).toBe('Filament runout detected');
    const both = feedState('state-alert');
    both.print = { ...both.print, message: 'Filament jam' };
    expect(deviceStateFromFeed(both)!.print!.message).toBe('Filament jam');
  });
  it('a Klipper that is not ready is an error with its own words', () => {
    const d = deviceStateFromFeed(feedState('state-klippy-shutdown'))!;
    expect(d.print).toMatchObject({ state: 'error', klipper: 'shutdown', viaJarvis: true });
    expect(d.print!.message).toBeTruthy();
    const noWords = feedState('state-ready-idle', { connection: 'klippy-error', message: null });
    expect(deviceStateFromFeed(noWords)!.print).toMatchObject({ klipper: 'error', message: "Klipper isn't ready" });
    expect(deviceStateFromFeed(feedState('state-ready-idle', { connection: 'klippy-startup' }))!.print!.klipper).toBe('startup');
  });
  it('when Jarvis cannot see the printer there is nothing to show', () => {
    expect(deviceStateFromFeed(feedState('state-unreachable'))).toBeNull();
    expect(deviceStateFromFeed(feedState('state-ready-idle', { connection: 'none' }))).toBeNull();
    expect(deviceStateFromFeed(feedState('state-ready-idle', { connection: 'connecting' }))).toBeNull();
  });
  it('a stale time left is not shown once the print is over', () => {
    const st = feedState();
    st.print = { ...st.print, state: 'complete', timeLeft: 600 };
    expect(deviceStateFromFeed(st)!.print!.timeLeftSec).toBeNull();
  });
  it('progress is kept between 0 and 1', () => {
    const st = feedState();
    st.print = { ...st.print, progress: 1.7 };
    expect(deviceStateFromFeed(st)!.print!.progress).toBe(1);
  });
});

// --- the printer service with a direct printer that comes and goes --------------------------------------------------------------

const BASE = 'http://192.168.4.86:7125';
function direct() {
  const p = { down: false, calls: [] as { method: string; path: string }[] };
  const fetch: HttpFetch = async (url, init) => {
    const path = url.replace(BASE, '');
    p.calls.push({ method: init.method, path });
    if (p.down) throw new TypeError('Network request failed');
    const res = (body: unknown): HttpResponse => ({ ok: true, status: 200, text: async () => JSON.stringify(body), body: null });
    if (path.startsWith('/printer/objects/query')) return res({ result: { status: { webhooks: { state: 'ready' }, print_stats: { state: 'standby' }, extruder: { temperature: 21, target: 0 }, heater_bed: { temperature: 22, target: 0 } } } });
    if (path.startsWith('/server/webcams/list')) return res({ result: { webcams: [{ name: 'cam', snapshot_url: '/webcam/?action=snapshot' }] } });
    return res({ result: 'ok' });
  };
  return { p, fetch };
}
async function service(jarvis?: JarvisPrinterRead) {
  const d = direct();
  const s = new PrinterService({ secrets: memorySecrets(), fetch: d.fetch, now: () => 1_000_000, jarvis });
  const home = new Home({ store: memoryStore() as never, setTimer: () => 0 });
  home.registerService(s);
  await s.signIn({ address: '192.168.4.86' });
  await home.refresh();
  return { ...d, s, home };
}
const printerOf = (home: Home): HomeDevice => home.cached().find((x) => x.type === 'printer')!;
const viaState: DeviceState = deviceStateFromFeed(feedState())!;

describe('the Home tile falls back to Jarvis only when the printer does not answer', () => {
  it('never asks Jarvis while the direct printer answers', async () => {
    const jarvis = { read: jest.fn(async () => viaState) };
    const c = await service(jarvis);
    await c.home.refresh();
    expect(jarvis.read).not.toHaveBeenCalled();
    expect(printerOf(c.home).state.print!.viaJarvis).toBeUndefined();
    expect(printerOf(c.home).capabilities).toContain('snapshot');
  });

  it('when the printer does not answer, shows Jarvis\'s view as online and "via Jarvis", without a direct camera', async () => {
    const jarvis = { read: jest.fn(async () => viaState) };
    const c = await service(jarvis);
    c.p.down = true;
    await c.home.refresh();
    expect(jarvis.read).toHaveBeenCalledTimes(1);
    const d = printerOf(c.home);
    expect(d.online).toBe(true);
    expect(d.state.print).toMatchObject({ state: 'printing', viaJarvis: true });
    expect(d.capabilities).toEqual(['print_control']);
    expect(printerWords(d)).toContain('Printing Chimney');
  });

  it('with no answer from Jarvis (not paired, not shared, away, or an error) it stays "not reachable" as before', async () => {
    for (const read of [async () => null, async () => { throw new Error('boom'); }]) {
      const c = await service({ read });
      c.p.down = true;
      await c.home.refresh();
      expect(printerOf(c.home).online).toBe(false);
    }
    const plain = await service();
    plain.p.down = true;
    await plain.home.refresh();
    expect(printerOf(plain.home).online).toBe(false);
  });

  it('commands stay direct only: away they say so, point to the Printer page, and never ask Jarvis', async () => {
    const jarvis = { read: jest.fn(async () => viaState) };
    const c = await service(jarvis);
    c.p.down = true;
    await c.home.refresh();
    jarvis.read.mockClear();
    const r = await c.home.command(printerOf(c.home), { type: 'pause' }, 'home');
    expect(r.ok).toBe(false);
    expect(r.text).toContain('Open the Printer page');
    expect(jarvis.read).not.toHaveBeenCalled();
    expect(c.p.calls.filter((x) => x.method === 'POST')).toEqual([]);
    const plain = await service();
    plain.p.down = true;
    await plain.home.refresh();
    const r2 = await plain.home.command(printerOf(plain.home), { type: 'pause' }, 'home');
    expect(r2.text).not.toContain('Printer page');
  });

  it('printer_status says "via Jarvis" and mentions the printer\'s latest alert', async () => {
    const st = feedState();
    st.alert = { text: 'Filament runout detected', time: 1 };
    const withAlert = deviceStateFromFeed(st)!;
    const c = await service({ read: async () => withAlert });
    c.p.down = true;
    await c.home.refresh();
    const tool = printerStatusTool(c.home);
    const r = await tool.execute(new ToolArgs({}, tool.spec), { clock: new FakeClock() });
    expect(r).toMatchObject({ ok: true });
    expect((r as { text: string }).text).toContain('(via Jarvis)');
    expect((r as { text: string }).text).toContain("The printer's latest error line: Filament runout detected");
  });

  it('the latest error line is left out when the state already says it', () => {
    for (const state of ['error', 'cancelled'] as const) expect(extras({ state, progress: 0, timeLeftSec: null, busy: false, message: 'Jam', viaJarvis: true })).toBe(' (via Jarvis)');
    expect(extras({ state: 'printing', progress: 0, timeLeftSec: null, busy: true, message: 'Jam' })).toBe(". The printer's latest error line: Jam");
    expect(extras(undefined)).toBe('');
  });

  it('the tile says "via Jarvis" in small text', async () => {
    const d: HomeDevice = { id: 'printer', name: '3D printer', type: 'printer', room: 'Workshop', capabilities: ['print_control'], backend: 'printer', online: true, state: viaState, stateAt: 1 };
    await render(<PrinterCard d={d} theme={themes.light} now={2} onCommand={() => {}} onCamera={() => {}} />);
    expect(screen.getByTestId('printer-via-jarvis')).toBeTruthy();
  });
});

describe('through the real feed', () => {
  it('asks Jarvis for one state, without a wait, only when the printer is shared', async () => {
    const { feed, service: jarvis } = await connectedFeed();
    const read = new FeedPrinterRead(new PrinterFeed(jarvis));
    const d = await read.read();
    expect(d!.print).toMatchObject({ state: 'printing', viaJarvis: true });
    const asked = feed.requests.filter((r) => r.path === '/v1/printer/state');
    expect(asked).toHaveLength(1);
    expect(asked[0].query.after).toBeUndefined();
  });
  it('not shared, no Jarvis, an unreachable Jarvis and an unreachable printer are all "no answer"', async () => {
    const shared = await connectedFeed((f) => (f.shared = { ...f.shared, printer: undefined }));
    expect(await new FeedPrinterRead(new PrinterFeed(shared.service)).read()).toBeNull();
    expect(shared.feed.count('/v1/printer/state')).toBe(0);
    const down = await connectedFeed((f) => (f.down = true));
    expect(await new FeedPrinterRead(new PrinterFeed(down.service)).read()).toBeNull();
    const off = await connectedFeed((f) => (f.answer = fixture('state-unreachable')));
    expect(await new FeedPrinterRead(new PrinterFeed(off.service)).read()).toBeNull();
    expect(await new FeedPrinterRead({ access: async () => ({ kind: 'noJarvis' }) }).read()).toBeNull();
  });
});

describe('Settings → Home → 3D printer text (C39 §9.1)', () => {
  const show = async (access: unknown) => {
    (useServices as jest.Mock).mockReturnValue({ printerFeed: { access: async () => access } });
    await render(<PrinterJarvisNote theme={themes.light} />);
    await screen.findByTestId('printer-jarvis-status');
    return screen.getByTestId('printer-jarvis-status').props.children as string;
  };
  it('says how to use the printer away from home', async () => {
    await show({ kind: 'noJarvis' });
    expect(screen.getByText(PRINTER_JARVIS_NOTE)).toBeTruthy();
    expect(PRINTER_JARVIS_NOTE).toContain('Let paired phones see the printer');
  });
  it('and what the paired Jarvis says right now', async () => {
    expect(await show({ kind: 'ready', shared: { name: 'FlashForge AD5X' } })).toBe('Jarvis: sharing the printer (FlashForge AD5X)');
    expect(await show({ kind: 'notShared' })).toBe('Jarvis: not sharing the printer');
    expect(await show({ kind: 'noJarvis' })).toBe('Jarvis: not paired');
    expect(await show({ kind: 'away', why: 'x', removed: false })).toBe("Jarvis: can't be reached right now");
    expect(await show({ kind: 'away', why: 'x', removed: true })).toBe('Jarvis: Jarvis removed this phone: pair it again.');
  });
});
