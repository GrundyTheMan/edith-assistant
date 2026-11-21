import { CAMERA_GAP_MS, ChartBuffer, ChartMath, ConsoleView, FileList, MeshColors, PrinterCardData, type ChartLine } from './cards';
import { PrinterSession } from './session';
import { PrinterFeed } from './feed';
import { connectedFeed, fixture } from './testkit';
import { parseConsole, parsePrinterState, parseTemperatures, type PrinterFile } from './wire';

const state = () => parsePrinterState((fixture('state-printing') as { state: unknown }).state);
const line = (id: string, values: number[], targets: number[] | null = null): ChartLine => ({ id, label: id, values, targets });

describe('the chart data (as Android ChartBuffer)', () => {
  it('seeds from Jarvis and then gets a point per answer for heaters and temperature sensors only', () => {
    const b = new ChartBuffer();
    b.seed(parseTemperatures(fixture('temperatures')));
    expect(b.snapshot().map((l) => l.id)).toEqual(['extruder', 'heater_bed', 'temperature_sensor head']);
    expect(b.snapshot()[0].values).toHaveLength(4);
    const s = state();
    s.sensors = [
      { id: 'temperature_sensor head', label: 'Head', value: 26, unit: '°C' },
      { id: 'filament', label: 'Pos', value: 1, unit: '' },
    ];
    b.append(s);
    const lines = b.snapshot();
    expect(lines.find((l) => l.id === 'extruder')!.values).toHaveLength(5);
    expect(lines.find((l) => l.id === 'extruder')!.targets).toHaveLength(5);
    expect(lines.find((l) => l.id === 'temperature_sensor head')!.values.at(-1)).toBe(26);
    expect(lines.find((l) => l.id === 'temperature_sensor head')!.targets).toBeNull();
    expect(lines.some((l) => l.id === 'filament')).toBe(false);
  });

  it('never grows past its cap, dropping the oldest', () => {
    const b = new ChartBuffer(3);
    const s = state();
    for (let i = 0; i < 10; i++) {
      s.heaters[0].temperature = i;
      b.append(s);
    }
    expect(b.snapshot()[0].values).toEqual([7, 8, 9]);
    expect(b.snapshot()[0].targets).toHaveLength(3);
  });
});

describe('the chart axes', () => {
  it('yMax is the highest value or target plus 10 %, never under 50, ignoring hidden lines', () => {
    expect(ChartMath.yMax([])).toBe(50);
    expect(ChartMath.yMax([line('a', [20, 100], [0, 200])])).toBeCloseTo(220);
    expect(ChartMath.yMax([line('a', [20]), line('b', [300])], new Set(['b']))).toBe(50);
  });
  it('gridlines every 50 up to yMax', () => {
    expect(ChartMath.gridlines(50)).toEqual([0, 50]);
    expect(ChartMath.gridlines(220)).toEqual([0, 50, 100, 150, 200]);
  });
  it('time labels: now under 30 s, else minutes rounded up', () => {
    expect(ChartMath.minutesAgo(1199, 1200)).toBe('now');
    expect(ChartMath.minutesAgo(0, 1200)).toBe('20 min ago');
    expect(ChartMath.minutesAgo(0, 31)).toBe('1 min ago');
    expect(ChartMath.minutesAgo(0, 30)).toBe('now');
  });
  it('fraction clamps to 0..1', () => {
    expect(ChartMath.fraction(50, 100)).toBe(0.5);
    expect(ChartMath.fraction(-5, 100)).toBe(0);
    expect(ChartMath.fraction(500, 100)).toBe(1);
    expect(ChartMath.fraction(5, 0)).toBe(0);
  });
  it('thinning keeps the first and the newest point and the count', () => {
    const v = Array.from({ length: 1200 }, (_, i) => i);
    const t = ChartMath.thin(v, 100);
    expect(t).toHaveLength(100);
    expect(t[0]).toBe(0);
    expect(t.at(-1)).toBe(1199);
    expect(ChartMath.thin([1, 2, 3], 100)).toEqual([1, 2, 3]);
  });
});

describe('files, console, mesh', () => {
  const f = (path: string, modified: number, printedBefore: boolean, title = path): PrinterFile => ({ path, size: 1, modified, title, kind: 'gcode', printedBefore });
  it('files: newest first, search by title or path in any case, printed only', () => {
    const files = [f('a.gcode', 1, true), f('Benchy.gcode', 3, false), f('sub/Cube.gcode', 2, true, 'A cube')];
    expect(FileList.shown(files, '', false).map((x) => x.path)).toEqual(['Benchy.gcode', 'sub/Cube.gcode', 'a.gcode']);
    expect(FileList.shown(files, '', true).map((x) => x.path)).toEqual(['sub/Cube.gcode', 'a.gcode']);
    expect(FileList.shown(files, ' CUBE ', false).map((x) => x.path)).toEqual(['sub/Cube.gcode']);
    expect(FileList.shown(files, 'a cube', false)).toHaveLength(1);
  });
  it('file sizes', () => {
    expect(FileList.size(2_400_000)).toBe('2.4 MB');
    expect(FileList.size(900_500)).toBe('900 KB');
    expect(FileList.size(12)).toBe('12 B');
  });
  it('console hides "// value:" lines by default and appends without repeating or overflowing', () => {
    const lines = parseConsole(fixture('console')).lines;
    const hidden = ConsoleView.visible(lines, true);
    expect(hidden.every((l) => !l.text.startsWith('// value:'))).toBe(true);
    expect(ConsoleView.visible(lines, false)).toHaveLength(lines.length);
    const mk = (seq: number) => ({ seq, time: seq, text: `l${seq}`, kind: 'response' });
    expect(ConsoleView.append([mk(1), mk(2)], [mk(2), mk(3)]).map((l) => l.seq)).toEqual([1, 2, 3]);
    const big = Array.from({ length: 600 }, (_, i) => mk(i + 1));
    expect(ConsoleView.append([], big)).toHaveLength(500);
    expect(ConsoleView.append([], big)[0].seq).toBe(101);
  });
  it('mesh colours: blue low, near white middle, red high, flat is grey', () => {
    expect(MeshColors.rgb(0, 0, 1)).toEqual([60, 110, 220]);
    expect(MeshColors.rgb(0.5, 0, 1)).toEqual([235, 235, 235]);
    expect(MeshColors.rgb(1, 0, 1)).toEqual([220, 85, 75]);
    expect(MeshColors.rgb(9, 0, 1)).toEqual([220, 85, 75]);
    expect(MeshColors.rgb(1, 1, 1)).toEqual([235, 235, 235]);
  });
  it('mesh extremes with their place, none for an empty mesh', () => {
    const e = MeshColors.extremes([[0.1, 0.3], [-0.2, 0.0]])!;
    expect(e.spread).toBeCloseTo(0.5);
    expect(e.lowest).toEqual({ value: -0.2, row: 1, col: 0 });
    expect(e.highest).toEqual({ value: 0.3, row: 0, col: 1 });
    expect(MeshColors.extremes([])).toBeNull();
  });
});

describe('the card data against the pretend feed', () => {
  const flush = async () => {
    for (let i = 0; i < 40; i++) await Promise.resolve();
  };
  async function setup() {
    const { feed, service } = await connectedFeed();
    const session = new PrinterSession({ feed: new PrinterFeed(service) });
    const waits: (() => void)[] = [];
    const gaps: number[] = [];
    const delay = (ms: number, signal: AbortSignal) =>
      new Promise<void>((resolve) => {
        gaps.push(ms);
        waits.push(resolve);
        signal.addEventListener('abort', () => resolve());
      });
    const data = new PrinterCardData({ session, delay });
    session.attach();
    for (let i = 0; i < 60 && session.state.get().kind !== 'live'; i++) await flush();
    const tick = async () => {
      waits.shift()?.();
      await flush();
    };
    return { feed, session, data, tick, gaps };
  }

  it('asks nothing for history, console, files or the chart until the card opens', async () => {
    const { feed, session, data } = await setup();
    await flush();
    const paths = feed.requests.map((r) => r.path);
    for (const p of ['/v1/printer/history', '/v1/printer/console', '/v1/printer/files', '/v1/printer/temperatures']) expect(paths).not.toContain(p);
    expect(data.ui.get().chart.length).toBeGreaterThan(0); // the live answer already gave the chart a point
    session.detach();
  });

  it('opening a card reads it once, and reading again only after leaving and coming back', async () => {
    const { feed, session, data } = await setup();
    data.visible('history', true);
    data.visible('console', true);
    data.visible('temperatures', true);
    await flush();
    expect(feed.count('/v1/printer/history')).toBe(1);
    expect(feed.count('/v1/printer/console')).toBe(1);
    expect(feed.count('/v1/printer/temperatures')).toBe(1);
    expect(data.ui.get().history.jobs.length).toBeGreaterThan(0);
    expect(data.ui.get().history.totals).not.toBeNull();
    expect(data.ui.get().terminal.lines.length).toBeGreaterThan(0);
    data.visible('history', false);
    data.visible('history', true);
    await flush();
    expect(feed.count('/v1/printer/history')).toBe(1);
    data.detached();
    data.attached();
    await flush();
    expect(feed.count('/v1/printer/history')).toBe(2);
    expect(feed.count('/v1/printer/console')).toBe(2);
    session.detach();
  });

  it('the file list is asked for only when Load files is pressed', async () => {
    const { feed, session, data } = await setup();
    data.visible('files', true);
    await flush();
    expect(feed.count('/v1/printer/files')).toBe(0);
    expect(data.ui.get().files.files).toBeNull();
    await data.loadFiles(false);
    expect(feed.count('/v1/printer/files')).toBe(1);
    expect(data.ui.get().files.files!.length).toBeGreaterThan(0);
    await data.loadFiles(true);
    expect(feed.requests.filter((r) => r.path === '/v1/printer/files').map((r) => r.query.fresh)).toEqual(['0', '1']);
    await data.openFile('x.gcode');
    expect(data.ui.get().files.detail!.layerCount).toBe(120);
    data.closeFile();
    expect(data.ui.get().files.detailOf).toBeNull();
    session.detach();
  });

  it('a file detail that arrives after the sheet closed is dropped', async () => {
    const { session, data } = await setup();
    const p = data.openFile('x.gcode');
    data.closeFile();
    await p;
    expect(data.ui.get().files.detail).toBeNull();
    session.detach();
  });

  it('new console lines are fetched only after the last one, when the live answer says there are more', async () => {
    const { feed, session, data } = await setup();
    data.visible('console', true);
    await flush();
    const before = feed.count('/v1/printer/console');
    feed.setState({ ...(feed.answer as object), consoleSeq: 9999 } as Record<string, unknown>);
    for (let i = 0; i < 20; i++) await flush();
    const asked = feed.requests.filter((r) => r.path === '/v1/printer/console');
    expect(asked.length).toBe(before + 1);
    expect(asked.at(-1)!.query.after).toBeDefined();
    session.detach();
  });

  it('the camera asks about every 700 ms only while its card is open and stops when it folds or the page leaves', async () => {
    const { feed, session, data, tick, gaps } = await setup();
    const shots = () => feed.requests.filter((r) => r.path.endsWith('/snapshot')).length;
    expect(shots()).toBe(0);
    data.visible('camera', true);
    await flush();
    expect(shots()).toBe(1);
    expect(data.ui.get().camera.frame).not.toBeNull();
    expect(data.ui.get().camera.number).toBe(1);
    expect(gaps.at(-1)).toBe(CAMERA_GAP_MS);
    await tick();
    expect(shots()).toBe(2);
    data.visible('camera', false);
    await tick();
    expect(shots()).toBe(2);
    data.visible('camera', true);
    await flush();
    expect(shots()).toBe(3);
    data.detached();
    await tick();
    expect(shots()).toBe(3);
    session.detach();
  });

  it('two camera failures in a row say offline, and a picture clears it', async () => {
    const { feed, session, data, tick } = await setup();
    feed.snapshotStatus = 502;
    data.visible('camera', true);
    await flush();
    expect(data.ui.get().camera.offline).toBe(false);
    await tick();
    expect(data.ui.get().camera.offline).toBe(true);
    feed.snapshotStatus = 200;
    await tick();
    expect(data.ui.get().camera.offline).toBe(false);
    session.detach();
  });

  it('while the page is away nothing is fetched for open cards, and a reply that arrives after leaving is dropped', async () => {
    const { feed, session, data } = await setup();
    data.visible('console', true);
    data.visible('temperatures', true);
    data.detached(); // left before either answer arrived
    await flush();
    expect(data.ui.get().chart.some((l) => l.id === 'temperature_sensor head')).toBe(false); // the late chart seed was dropped
    const before = feed.count('/v1/printer/console');
    feed.setState({ ...(feed.answer as object), consoleSeq: 9999 } as Record<string, unknown>);
    for (let i = 0; i < 20; i++) await flush();
    expect(feed.count('/v1/printer/console')).toBe(before); // console is not marked loaded: no fetch of new lines
    data.attached();
    await flush();
    expect(data.ui.get().chart.some((l) => l.id === 'temperature_sensor head')).toBe(true);
    session.detach();
  });

  it('pressing Load files twice at once asks once', async () => {
    const { feed, session, data } = await setup();
    const a = data.loadFiles(false);
    const b = data.loadFiles(false);
    await Promise.all([a, b]);
    expect(feed.count('/v1/printer/files')).toBe(1);
    session.detach();
  });

  it('a file command Jarvis accepted closes the details, and a deleted file leaves the list without asking for it again', async () => {
    const { feed, session, data } = await setup();
    await data.loadFiles(false);
    const first = data.ui.get().files.files![0].path;
    await data.openFile(first);
    expect(data.ui.get().files.detailOf).toBe(first);
    data.commandSent({ kind: 'pause' });
    expect(data.ui.get().files.detailOf).toBe(first); // not a file command
    data.commandSent({ kind: 'queueAdd', path: first });
    expect(data.ui.get().files.detailOf).toBeNull();
    expect(data.ui.get().files.files!.some((f) => f.path === first)).toBe(true);
    await data.openFile(first);
    const count = data.ui.get().files.files!.length;
    data.commandSent({ kind: 'deleteFile', path: first });
    expect(data.ui.get().files.detailOf).toBeNull();
    expect(data.ui.get().files.files!.length).toBe(count - 1);
    expect(feed.count('/v1/printer/files')).toBe(1);
    data.commandSent({ kind: 'start', path: 'x' });
    expect(data.ui.get().files.files!.length).toBe(count - 1);
    session.detach();
  });

  it('hiding a chart line is toggled and remembered', async () => {
    const { session, data } = await setup();
    data.toggleSeries('extruder');
    expect(data.ui.get().chartHidden.has('extruder')).toBe(true);
    data.toggleSeries('extruder');
    expect(data.ui.get().chartHidden.has('extruder')).toBe(false);
    session.detach();
  });
});
