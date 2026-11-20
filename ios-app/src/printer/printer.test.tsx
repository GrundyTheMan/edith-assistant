import { fireEvent, render, screen } from '@testing-library/react-native';
import { bytesToBase64, readBytes } from '../core/bytes';
import { memorySecrets } from '../core/secrets';
import { buildCommand, confirmationFor, printerWords } from '../home/actions';
import { Home } from '../home/home';
import type { HomeDevice } from '../home/model';
import type { HttpFetch, HttpResponse } from '../ollama/client';
import { memoryStore } from '../settings/settings';
import { FakeClock } from '../testing/nodeSqlite';
import { ToolArgs } from '../tools/framework/toolArgs';
import { PrinterCard } from '../ui/PrinterCard';
import { themes } from '../ui/theme';
import { PrinterError } from './api';
import { PrinterService } from './service';
import { fileTitle, formatDuration, normalizePrinterAddress, parseStatus, parseWebcams, resolveWebcamUrl, timeLeft } from './parse';
import { printerStatusTool } from './tools';

const BASE = 'http://192.168.4.86:7125';

describe('parsing Moonraker', () => {
  const printing = {
    webhooks: { state: 'ready' },
    print_stats: { state: 'printing', filename: 'Benchy.gcode', print_duration: 1800, info: { current_layer: 40, total_layer: 200 } },
    virtual_sdcard: { progress: 0.2 },
    display_status: { progress: 0.2 },
    idle_timeout: { state: 'Printing' },
    extruder: { temperature: 214.6, target: 215 },
    heater_bed: { temperature: 59.8, target: 60 },
  };
  it('reads a print: title, layers, heaters, and the time left from the slicer estimate minus time printed', () => {
    const r = parseStatus(printing, 7200);
    expect(r.connection).toBe('ready');
    expect(r.print).toMatchObject({ state: 'printing', title: 'Benchy', progress: 0.2, busy: true, layer: { current: 40, total: 200 }, timeLeftSec: 5400 });
    expect(r.heaters.nozzle).toMatchObject({ temperature: 214.6, target: 215 });
    expect(r.heaters.bed).toMatchObject({ label: 'Bed' });
  });
  it("uses the slicer's own progress (M73) when it differs from the file position, and its estimate by time", () => {
    const r = parseStatus({ ...printing, virtual_sdcard: { progress: 0.2 }, display_status: { progress: 0.5 } }, 7200);
    expect(r.print.progress).toBe(0.5);
    expect(r.print.timeLeftSec).toBe(3600);
  });
  it('without an estimate it uses the pace, and not before 1 % and a minute', () => {
    expect(parseStatus(printing).print.timeLeftSec).toBe(7200); // 1800 s for 20 %
    expect(timeLeft(0.005, 3000)).toBeNull();
    expect(timeLeft(0.5, 30)).toBeNull();
    expect(timeLeft(0.5, 600)).toBe(600);
    expect(timeLeft(0.5, 600, 1000, false)).toBe(400);
    expect(timeLeft(2, 600, 1000, true)).toBe(0);
  });
  it('knows idle, complete, paused and odd answers without throwing', () => {
    expect(parseStatus({ print_stats: { state: 'complete', filename: 'a.gcode' } }).print).toMatchObject({ state: 'complete', busy: false, timeLeftSec: null });
    expect(parseStatus({ print_stats: { state: 'paused' }, pause_resume: { is_paused: true } }).print.busy).toBe(true);
    expect(parseStatus({ print_stats: { state: 'weird' } }).print.state).toBe('standby');
    expect(parseStatus(null).print.state).toBe('standby');
    expect(parseStatus('nonsense').heaters).toEqual({});
  });
  it("reports Klipper when it isn't ready, with the first line only", () => {
    const r = parseStatus({ webhooks: { state: 'shutdown', state_message: 'Lost communication with MCU\nrestart' } });
    expect(r).toMatchObject({ connection: 'klippy-shutdown', message: 'Lost communication with MCU' });
    expect(parseStatus({ webhooks: { state: 'startup' } }).connection).toBe('klippy-startup');
  });
  it('titles files for people, including a ZMOD 3mf plate', () => {
    expect(fileTitle('Benchy.gcode')).toBe('Benchy');
    expect(fileTitle('folder/My+Part%20v2.gcode')).toBe('My Part v2');
    expect(fileTitle('3mf/model/Chimney+and+Platforms_plate_1/Metadata/plate_1.gcode')).toBe('Chimney and Platforms (plate 1)');
    expect(fileTitle('weird%ZZ.gcode')).toBe('weird%ZZ');
  });
  it('formats durations', () => {
    expect([formatDuration(0), formatDuration(45), formatDuration(52 * 60), formatDuration(4440)]).toEqual(['0s', '45s', '52m', '1h 14m']);
  });
  it("finds the webcam's snapshot address and points a localhost one at the printer", () => {
    const cams = parseWebcams({ webcams: [{ name: 'off', enabled: false, snapshot_url: '/x' }, { name: 'zmod', snapshot_url: 'http://localhost:8080/?action=snapshot' }, { name: 'none' }] });
    expect(cams).toEqual([{ name: 'zmod', snapshotUrl: 'http://localhost:8080/?action=snapshot' }]);
    expect(resolveWebcamUrl(BASE, 'http://localhost:8080/?action=snapshot')).toBe('http://192.168.4.86:8080/?action=snapshot');
    expect(resolveWebcamUrl(BASE, '/webcam/?action=snapshot')).toBe(`${BASE}/webcam/?action=snapshot`);
    expect(resolveWebcamUrl(BASE, 'webcam/snap')).toBe(`${BASE}/webcam/snap`);
    expect(resolveWebcamUrl(BASE, 'http://192.168.4.90/snap')).toBe('http://192.168.4.90/snap');
    expect(parseWebcams({})).toEqual([]);
  });
  it('normalises the address the owner types', () => {
    expect(normalizePrinterAddress('192.168.4.86')).toEqual({ ok: true, base: BASE });
    expect(normalizePrinterAddress(' http://192.168.4.86:7130/ ')).toEqual({ ok: true, base: 'http://192.168.4.86:7130' });
    expect(normalizePrinterAddress('printer.local')).toEqual({ ok: true, base: 'http://printer.local:7125' });
    expect(normalizePrinterAddress('')).toMatchObject({ ok: false });
    expect(normalizePrinterAddress('ftp://x')).toMatchObject({ ok: false });
    expect(normalizePrinterAddress('1.2.3.4:99999')).toMatchObject({ ok: false });
  });
  it('encodes Base64 and reads a body in pieces', async () => {
    expect(bytesToBase64(new Uint8Array([]))).toBe('');
    expect(bytesToBase64(new Uint8Array([77]))).toBe('TQ==');
    expect(bytesToBase64(new Uint8Array([77, 97]))).toBe('TWE=');
    expect(bytesToBase64(new Uint8Array([77, 97, 110]))).toBe('TWFu');
    const chunks = [new Uint8Array([1, 2]), new Uint8Array([3])];
    const body = { getReader: () => ({ read: async () => (chunks.length ? { done: false, value: chunks.shift() } : { done: true }) }) };
    expect(Array.from(await readBytes(body, 10))).toEqual([1, 2, 3]);
    await expect(readBytes({ getReader: () => ({ read: async () => ({ done: false, value: new Uint8Array(20) }) }) }, 10)).rejects.toThrow('too large');
  });
});

// --- a pretend Moonraker -------------------------------------------------------------------------------------------------------

interface Pretend {
  fetch: HttpFetch;
  calls: { method: string; path: string; key?: string }[];
  status: Record<string, unknown>;
  down: boolean;
  key: string | null;
  webcams: unknown;
  /** What the webcam sends. */
  cam: Uint8Array;
}
const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3]);
function pretendMoonraker(): Pretend {
  const p: Pretend = {
    calls: [],
    down: false,
    key: null,
    cam: jpeg,
    webcams: { webcams: [{ name: 'cam', snapshot_url: '/webcam/?action=snapshot' }] },
    status: {
      webhooks: { state: 'ready' },
      print_stats: { state: 'printing', filename: 'Benchy.gcode', print_duration: 1800 },
      virtual_sdcard: { progress: 0.25 },
      display_status: { progress: 0.25 },
      idle_timeout: { state: 'Printing' },
      extruder: { temperature: 215, target: 215 },
      heater_bed: { temperature: 60, target: 60 },
    },
    fetch: async (url, init) => {
      const path = url.replace(BASE, '');
      p.calls.push({ method: init.method, path, key: init.headers?.['X-Api-Key'] });
      if (p.down) throw new TypeError('Network request failed');
      const res = (body: unknown, status = 200): HttpResponse => ({ ok: status < 300, status, text: async () => JSON.stringify(body), body: null });
      if (p.key && init.headers?.['X-Api-Key'] !== p.key) return res({ error: 'Unauthorized' }, 401);
      if (path.startsWith('/printer/objects/query')) return res({ result: { status: p.status } });
      if (path.startsWith('/server/files/metadata')) return res({ result: { estimated_time: 7200 } });
      if (path.startsWith('/server/webcams/list')) return res({ result: p.webcams });
      if (path.startsWith('/printer/print/')) return res({ result: 'ok' });
      if (path.startsWith('/webcam/')) {
        let sent = false;
        return { ok: true, status: 200, text: async () => '', body: { getReader: () => ({ read: async () => (sent ? { done: true } : ((sent = true), { done: false, value: p.cam })) }) } };
      }
      return res({}, 404);
    },
  };
  return p;
}
async function connected(mutate?: (p: Pretend) => void) {
  const pretend = pretendMoonraker();
  mutate?.(pretend);
  const secrets = memorySecrets();
  const service = new PrinterService({ secrets, fetch: pretend.fetch, now: () => 1_000_000 });
  const home = new Home({ store: memoryStore() as never, setTimer: () => 0 });
  home.registerService(service);
  const result = await service.signIn({ address: '192.168.4.86', key: pretend.key ?? '' });
  return { pretend, secrets, service, home, result };
}
const printerOf = (home: Home): HomeDevice => home.cached().find((d) => d.type === 'printer')!;

describe('the printer service', () => {
  it('connects, saves the address, and shows one printer with its state', async () => {
    const { home, secrets, result, service } = await connected();
    expect(result).toEqual({ kind: 'ok' });
    expect(service.state.get()).toEqual({ kind: 'connected', detail: '192.168.4.86:7125' });
    expect(JSON.parse((await secrets.get('home.printer'))!)).toEqual({ address: BASE });
    await home.refresh();
    const d = printerOf(home);
    expect(d).toMatchObject({ name: '3D printer', type: 'printer', online: true, capabilities: ['print_control', 'snapshot'] });
    expect(d.state.print).toMatchObject({ state: 'printing', title: 'Benchy', progress: 0.25, timeLeftSec: 5400 });
    expect(printerWords(d)).toBe('Printing Benchy, 25%, about 1h 30m left. nozzle 215°C, bed 60°C');
  });

  it('sends the key only when there is one, and wrong key gives words and a sign-in-again state', async () => {
    const withKey = await connected((p) => (p.key = 'sekret'));
    expect(withKey.result).toEqual({ kind: 'ok' });
    expect(withKey.pretend.calls.every((c) => c.key === 'sekret')).toBe(true);
    const none = await connected();
    expect(none.pretend.calls.every((c) => c.key === undefined)).toBe(true);
    const bad = pretendMoonraker();
    bad.key = 'right';
    const service = new PrinterService({ secrets: memorySecrets(), fetch: bad.fetch });
    const r = await service.signIn({ address: '192.168.4.86', key: 'wrong' });
    expect(r).toEqual({ kind: 'failed', message: 'The printer wants an API key, or the key is wrong.' });
    expect(service.state.get().kind).toBe('signed_out');
  });

  it("says plainly when the address isn't a printer or can't be reached, and saves nothing", async () => {
    const secrets = memorySecrets();
    const down = pretendMoonraker();
    down.down = true;
    const a = await new PrinterService({ secrets, fetch: down.fetch }).signIn({ address: '192.168.4.86' });
    expect(a).toMatchObject({ kind: 'failed', message: expect.stringContaining("Couldn't reach a printer at 192.168.4.86:7125") });
    const weird = new PrinterService({ secrets, fetch: async () => ({ ok: true, status: 200, text: async () => '<html>', body: null }) });
    expect(await weird.signIn({ address: '192.168.4.86' })).toMatchObject({ kind: 'failed', message: expect.stringContaining("doesn't look like a Moonraker") });
    expect(await new PrinterService({ secrets, fetch: down.fetch }).signIn({ address: '' })).toMatchObject({ kind: 'failed' });
    expect(secrets.dump()).toEqual({});
  });

  it('comes back after a restart from the saved address', async () => {
    const { secrets, pretend } = await connected();
    const again = new PrinterService({ secrets, fetch: pretend.fetch });
    await again.restore();
    expect(again.state.get().kind).toBe('connected');
    const home = new Home({ store: memoryStore() as never });
    home.registerService(again);
    await home.refresh();
    expect(printerOf(home).online).toBe(true);
  });

  it('asks for the slicer estimate once per file', async () => {
    const { home, pretend } = await connected();
    await home.refresh();
    await home.refresh();
    expect(pretend.calls.filter((c) => c.path.startsWith('/server/files/metadata'))).toHaveLength(1);
    expect(pretend.calls.filter((c) => c.path.startsWith('/server/webcams/list'))).toHaveLength(1);
  });

  it('away from home: not online, the last report kept, and no problem line', async () => {
    const { home, pretend } = await connected();
    await home.refresh();
    pretend.down = true;
    const r = await home.refresh();
    expect(r.problems).toEqual([]);
    const d = printerOf(home);
    expect(d.online).toBe(false);
    expect(d.state.print?.title).toBe('Benchy');
    expect(printerWords(d)).toContain('Not reachable. It works on your home Wi-Fi.');
  });

  it('a wrong key later is a problem line and a sign-in-again state', async () => {
    const { home, pretend, service } = await connected((p) => (p.key = 'k'));
    pretend.key = 'changed';
    const r = await home.refresh();
    expect(r.problems[0]).toContain('API key');
    expect(service.state.get()).toMatchObject({ kind: 'needs_sign_in' });
  });

  it('shows Klipper not ready, in its own words', async () => {
    const { home } = await connected((p) => (p.status = { webhooks: { state: 'shutdown', state_message: 'MCU shutdown' } }));
    await home.refresh();
    expect(printerWords(printerOf(home))).toBe("Klipper isn't ready: MCU shutdown");
  });

  it('disconnect forgets everything', async () => {
    const { home, secrets, service } = await connected();
    await home.refresh();
    await service.disconnect();
    expect(secrets.dump()).toEqual({});
    expect(home.cached()).toEqual([]);
  });
});

describe('print commands', () => {
  const run = async (type: 'pause' | 'resume' | 'cancel_print', mutate?: (p: Pretend) => void) => {
    const c = await connected(mutate);
    await c.home.refresh();
    const result = await c.home.command(printerOf(c.home), { type }, 'home');
    return { ...c, result, posts: c.pretend.calls.filter((x) => x.method === 'POST').map((x) => x.path) };
  };
  it('pauses a print that is printing', async () => {
    expect((await run('pause')).posts).toEqual(['/printer/print/pause']);
  });
  it('asking for what is already so sends nothing and is a success', async () => {
    const paused = (p: Pretend) => (p.status = { ...p.status, print_stats: { state: 'paused', filename: 'a.gcode' }, pause_resume: { is_paused: true } });
    const a = await run('pause', paused);
    expect(a.result.ok).toBe(true);
    expect(a.posts).toEqual([]);
    expect((await run('resume')).posts).toEqual([]); // already printing
  });
  it('resumes only a paused print, and cancel needs a print going', async () => {
    const paused = (p: Pretend) => (p.status = { ...p.status, print_stats: { state: 'paused', filename: 'a.gcode' }, pause_resume: { is_paused: true } });
    expect((await run('resume', paused)).posts).toEqual(['/printer/print/resume']);
    expect((await run('cancel_print')).posts).toEqual(['/printer/print/cancel']);
    const idle = (p: Pretend) => (p.status = { webhooks: { state: 'ready' }, print_stats: { state: 'standby' } });
    const noPause = await run('pause', idle);
    expect(noPause.result).toMatchObject({ ok: false, text: expect.stringContaining('nothing to pause') });
    expect((await run('cancel_print', idle)).result.text).toContain('no print to cancel');
    expect((await run('resume', idle)).result.text).toContain('nothing to resume');
    expect((await run('pause', idle)).posts).toEqual([]);
  });
  it('away from home says so and sends nothing', async () => {
    const c = await connected();
    await c.home.refresh();
    c.pretend.down = true;
    const r = await c.home.command(printerOf(c.home), { type: 'pause' }, 'home');
    expect(r.ok).toBe(false);
    expect(r.text).toContain('Not reachable');
    expect(c.pretend.calls.filter((x) => x.method === 'POST')).toEqual([]);
  });
  it('logs the commands with the service', async () => {
    const r = await run('pause');
    expect(r.home.commandLog()[0]).toMatchObject({ action: 'pause', ok: true, service: 'printer' });
  });
});

describe('cards and actions', () => {
  const dev: HomeDevice = {
    id: 'printer', name: '3D printer', room: 'Workshop', type: 'printer', capabilities: ['print_control'], backend: 'printer', online: true, stateAt: 0,
    state: { print: { state: 'printing', title: 'Benchy', progress: 0.5, timeLeftSec: 600, busy: true } },
  };
  it('cancel always asks, with two clearly different buttons; pause and resume do not', () => {
    const spec = confirmationFor(dev, { type: 'cancel_print' })!;
    expect(spec.title).toBe('Cancel the print: 3D printer?');
    expect([spec.confirmLabel, spec.denyLabel]).toEqual(['Cancel print', 'Keep printing']);
    expect(spec.details).toContainEqual(['Printing', 'Benchy']);
    expect(confirmationFor(dev, { type: 'pause' })).toBeNull();
    expect(confirmationFor(dev, { type: 'resume' })).toBeNull();
  });
  it('home_control actions pause, resume, cancel need the print capability', () => {
    expect(buildCommand(dev, 'pause', null)).toEqual({ ok: true, command: { type: 'pause' } });
    expect(buildCommand(dev, 'cancel', null)).toEqual({ ok: true, command: { type: 'cancel_print' } });
    expect(buildCommand({ ...dev, capabilities: ['on_off'] }, 'pause', null)).toMatchObject({ ok: false });
  });
  it('the card shows the print, and its buttons follow the state', async () => {
    const onCommand = jest.fn();
    const onCamera = jest.fn();
    const withCam = { ...dev, capabilities: ['print_control', 'snapshot'] as HomeDevice['capabilities'] };
    await render(<PrinterCard d={withCam} theme={themes.light} now={0} onCommand={onCommand} onCamera={onCamera} />);
    expect(screen.getByText(/Printing Benchy, 50%/)).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Pause'));
    await fireEvent.press(screen.getByLabelText('Cancel print'));
    await fireEvent.press(screen.getByLabelText('Camera'));
    expect(onCommand.mock.calls).toEqual([['pause'], ['cancel_print']]);
    expect(onCamera).toHaveBeenCalled();
    expect(screen.queryByLabelText('Resume')).toBeNull();
  });
  it('a paused card offers Resume; an unreachable one says so and offers no buttons', async () => {
    const onCommand = jest.fn();
    const paused = { ...dev, state: { print: { ...dev.state.print!, state: 'paused' as const } } };
    const { rerender } = await render(<PrinterCard d={paused} theme={themes.light} now={0} onCommand={onCommand} onCamera={() => {}} />);
    expect(screen.getByLabelText('Resume')).toBeTruthy();
    await rerender(<PrinterCard d={{ ...dev, online: false }} theme={themes.light} now={60_000} onCommand={onCommand} onCamera={() => {}} />);
    expect(screen.getAllByText(/Not reachable. It works on your home Wi-Fi./).length).toBeGreaterThan(0);
    expect(screen.queryByLabelText('Pause')).toBeNull();
    expect(screen.queryByLabelText('Cancel print')).toBeNull();
  });
});

describe('printer_status and the camera', () => {
  const ctx = { clock: new FakeClock() };
  it('answers "how is the print going", and is offered only while a printer is connected', async () => {
    const { home } = await connected();
    const tool = printerStatusTool(home);
    expect(tool.enabled!()).toBe(false); // not read yet
    await home.refresh();
    expect(tool.enabled!()).toBe(true);
    const r = await tool.execute(new ToolArgs({}, tool.spec), ctx);
    expect(r).toEqual({ ok: true, text: '3D printer: Printing Benchy, 25%, about 1h 30m left. nozzle 215°C, bed 60°C' });
  });
  it('takes a camera picture through the ordinary snapshot path, with the webcam on the printer\'s host', async () => {
    const { home, pretend } = await connected();
    await home.refresh();
    const shot = await home.snapshot(printerOf(home));
    expect(shot.jpegBase64).toBe(bytesToBase64(jpeg));
    expect(pretend.calls.at(-1)?.path).toBe('/webcam/?action=snapshot');
  });
  it('a camera that sends something else is refused', async () => {
    const { home, pretend } = await connected();
    await home.refresh();
    pretend.cam = new Uint8Array([60, 104, 116, 109, 108]); // "<html"
    await expect(home.snapshot(printerOf(home))).rejects.toBeInstanceOf(PrinterError);
  });
});
