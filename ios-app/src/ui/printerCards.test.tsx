import { fireEvent, render, screen } from '@testing-library/react-native';
import { ChartBuffer, type CardsUi, type ChartLine } from '../printer/feed/cards';
import { fixture, JPEG } from '../printer/feed/testkit';
import { parsePrinterState, type PrinterState } from '../printer/feed/wire';
import { CameraCard, ConsoleCard, FansCard, FilesCard, HistoryCard, LimitsCard, MeshCard, ObjectsCard, QueueCard, SensorsCard, SlotsCard, SystemCard, TemperatureCard, ToolheadCard, swatchColor, type CardActions } from './printer/Cards';
import { themes } from './theme';

const theme = themes.light;
const NOW = 1_763_400_000_000;
const base = (): PrinterState => parsePrinterState((fixture('state-printing') as { state: unknown }).state);
const cardsUi = (over: Partial<CardsUi> = {}): CardsUi => ({
  chart: [],
  chartHidden: new Set(),
  history: { jobs: [], totals: null, loading: false, more: false, error: null },
  files: { files: null, cachedAt: 0, loading: false, error: null, query: '', printedOnly: true, detailOf: null, detail: null, detailLoading: false, detailError: null },
  terminal: { lines: [], hideValues: true, error: null },
  camera: { cameraId: null, frame: null, number: 0, offline: false },
  ...over,
});
function actions() {
  const calls: Record<string, unknown[][]> = {};
  const a = new Proxy({}, { get: (_t, name: string) => (...args: unknown[]) => void (calls[name] ??= []).push(args) }) as CardActions;
  return { a, calls };
}

describe('the read-only cards', () => {
  it('Camera: connecting, a picture, offline, none, and a choice between cameras', async () => {
    const st = base();
    const { a, calls } = actions();
    await render(<CameraCard state={st} camera={cardsUi().camera} actions={a} theme={theme} />);
    expect(screen.getByTestId('camera-connecting')).toBeTruthy();
    await render(<CameraCard state={st} camera={{ cameraId: null, frame: JPEG, number: 1, offline: false }} actions={a} theme={theme} />);
    expect(screen.getByLabelText('Camera picture')).toBeTruthy();
    await render(<CameraCard state={st} camera={{ cameraId: null, frame: JPEG, number: 2, offline: true }} actions={a} theme={theme} />);
    expect(screen.getByTestId('camera-offline')).toBeTruthy();
    await render(<CameraCard state={{ ...st, cameras: [] }} camera={cardsUi().camera} actions={a} theme={theme} />);
    expect(screen.getByText('This printer has no camera.')).toBeTruthy();
    await render(<CameraCard state={{ ...st, cameras: [...st.cameras, { ...st.cameras[0], id: 'b', name: 'Side' }] }} camera={cardsUi().camera} actions={a} theme={theme} />);
    await fireEvent.press(screen.getByLabelText('Side'));
    expect(calls.chooseCamera).toEqual([['b']]);
  });

  it('Temperatures: a row for each heater, the chart once there are two points, and legend toggles', async () => {
    const st = base();
    const { a, calls } = actions();
    await render(<TemperatureCard state={st} cards={cardsUi()} actions={a} theme={theme} />);
    expect(screen.getByText('The chart fills in as the printer reports.')).toBeTruthy();
    expect(screen.getByLabelText('Nozzle: 219.8 °C, target 220 °C')).toBeTruthy();
    const b = new ChartBuffer();
    b.append(st);
    b.append(st);
    const chart: ChartLine[] = b.snapshot();
    await render(<TemperatureCard state={st} cards={cardsUi({ chart })} actions={a} theme={theme} />);
    expect(screen.getByTestId('temp-chart')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Nozzle line'));
    expect(calls.toggleSeries).toEqual([[chart[0].id]]);
  });

  it('Toolhead, Fans, Limits, Sensors, System say what Jarvis said', async () => {
    const st = base();
    const { a } = actions();
    await render(<ToolheadCard state={st} theme={theme} />);
    expect(screen.getByText('Homed: X Y Z')).toBeTruthy();
    expect(screen.getByLabelText('X 100.50')).toBeTruthy();
    await render(<ToolheadCard state={{ ...st, toolhead: { ...st.toolhead, homed: '', motorsOn: false } }} theme={theme} />);
    expect(screen.getByText('Not homed')).toBeTruthy();
    expect(screen.getByText('Motors are off')).toBeTruthy();
    await render(<FansCard state={{ ...st, fans: [...st.fans, { id: 'f2', label: 'Chamber', speed: 0.5, settable: false }] }} theme={theme} />);
    expect(screen.getByLabelText('Part cooling: 85 %')).toBeTruthy();
    expect(screen.getByLabelText('Chamber: 50 % · automatic')).toBeTruthy();
    expect(screen.getByLabelText('Light: 100 %')).toBeTruthy();
    await render(<FansCard state={{ ...st, fans: [], light: null }} theme={theme} />);
    expect(screen.getByText('This printer reports no fans.')).toBeTruthy();
    await render(<LimitsCard state={{ ...st, retraction: { length: 0.8, speed: 35, extraLength: 0, unretractSpeed: 30 } }} theme={theme} />);
    expect(screen.getByLabelText('Acceleration: 3000 mm/s²')).toBeTruthy();
    expect(screen.getByLabelText('Length: 0.80 mm')).toBeTruthy();
    await render(<SensorsCard state={{ ...st, sensors: [{ id: 's', label: 'Head', value: 25.04, unit: '°C' }, { id: 'p', label: 'Pos', value: 1, unit: '' }], filamentSensors: [{ id: 'fs', label: 'Runout', detected: false, enabled: true }] }} theme={theme} />);
    expect(screen.getByLabelText('Head: 25.0 °C')).toBeTruthy();
    expect(screen.queryByLabelText(/Pos:/)).toBeNull();
    expect(screen.getByLabelText('Runout: No filament · enabled')).toBeTruthy();
    await render(<SensorsCard state={{ ...st, sensors: [], filamentSensors: [] }} theme={theme} />);
    expect(screen.getByText('This printer reports no sensors.')).toBeTruthy();
    await render(<SystemCard state={{ ...st, hostname: 'voron', versions: { moonraker: 'v0.9', mcu: 'mcu1' }, host: { load: 0.456, memAvailableKb: 2_097_152 } }} theme={theme} />);
    expect(screen.getByLabelText('Host name: voron')).toBeTruthy();
    expect(screen.getByLabelText('Load: 0.46')).toBeTruthy();
    expect(screen.getByLabelText('Memory free: 2048 MB')).toBeTruthy();
    void a;
  });

  it('Slots: no slot is called empty when the printer is not reporting them', async () => {
    const st = base();
    const slot = { id: '1', material: 'PLA', color: 'Red', hex: 'ff0000', loaded: false };
    await render(<SlotsCard state={{ ...st, slotsLive: false, slots: [slot] }} theme={theme} />);
    expect(screen.queryByText('Empty')).toBeNull();
    expect(screen.getByText(/isn't reporting which slots/)).toBeTruthy();
    await render(<SlotsCard state={{ ...st, slotsLive: true, slots: [slot, { ...slot, id: '2', color: 'Blue', loaded: true }] }} theme={theme} />);
    expect(screen.getByText('Empty')).toBeTruthy();
    expect(screen.getByText('Loaded')).toBeTruthy();
    expect(screen.getByLabelText('PLA · Blue, loaded')).toBeTruthy();
    expect(swatchColor(slot)).toBe('#ff0000');
    expect(swatchColor({ ...slot, hex: '#00ff00' })).toBe('#00ff00');
    expect(swatchColor({ ...slot, hex: 'nope' })).toBe('#808080');
  });

  it('Queue, Objects and Bed mesh', async () => {
    const st = base();
    await render(<QueueCard state={{ ...st, queue: { state: 'ready', jobs: [{ id: '1', filename: 'a.gcode', title: '', added: NOW - 3_600_000 * 3 }] } }} now={NOW} theme={theme} />);
    expect(screen.getByText('The next job starts as soon as the printer is free')).toBeTruthy();
    expect(screen.getByLabelText('a.gcode: 3 h ago')).toBeTruthy();
    await render(<QueueCard state={{ ...st, queue: { state: 'paused', jobs: [] } }} now={NOW} theme={theme} />);
    expect(screen.getByText('The queue is paused')).toBeTruthy();
    expect(screen.getByText('No jobs are waiting.')).toBeTruthy();
    await render(<ObjectsCard state={{ ...st, objects: [...st.objects, { name: 'Old', center: null, polygon: null, excluded: true, current: false }] }} theme={theme} />);
    expect(screen.getByLabelText('Chimney: printing now')).toBeTruthy();
    expect(screen.getByLabelText('Old: excluded')).toBeTruthy();
    await render(<MeshCard mesh={{ profile: 'default', active: false, min: [0, 0], max: [220, 220], points: [[0.1, 0.2], [-0.1, 0.05]] }} theme={theme} />);
    expect(screen.getByText('Saved profile (not in use now)')).toBeTruthy();
    expect(screen.getByLabelText('Spread: 0.300 mm')).toBeTruthy();
    expect(screen.getByLabelText('Lowest: -0.100 mm')).toBeTruthy();
  });

  it('History: totals, jobs, More, errors and the empty words', async () => {
    const { a, calls } = actions();
    const job = { id: '1', filename: 'a.gcode', title: 'Benchy', status: 'client_stop', start: NOW - 7_200_000, end: NOW, printDuration: 3600, filamentUsed: 5200 };
    await render(<HistoryCard cards={cardsUi({ history: { jobs: [job], totals: { jobs: 7, printTime: 7200, filamentUsed: 10_000, longestPrint: 3600 }, loading: false, more: true, error: null } })} now={NOW} actions={a} theme={theme} />);
    expect(screen.getByLabelText('Jobs: 7')).toBeTruthy();
    expect(screen.getByText('client stop · 2 h ago · 1h · 5.2 m')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('More jobs'));
    expect(calls.moreHistory).toHaveLength(1);
    await render(<HistoryCard cards={cardsUi()} now={NOW} actions={a} theme={theme} />);
    expect(screen.getByText('No jobs yet.')).toBeTruthy();
    await render(<HistoryCard cards={cardsUi({ history: { jobs: [], totals: null, loading: false, more: false, error: 'Jarvis is not answering.' } })} now={NOW} actions={a} theme={theme} />);
    expect(screen.getByText('Jarvis is not answering.')).toBeTruthy();
    expect(screen.queryByText('No jobs yet.')).toBeNull();
  });

  it('Console: hides value lines by default, colours errors, Clear and the switch call out', async () => {
    const { a, calls } = actions();
    const lines = [
      { seq: 1, time: 1, text: '// value: 3', kind: 'response' },
      { seq: 2, time: 2, text: 'G28', kind: 'command' },
      { seq: 3, time: 3, text: '!! Error', kind: 'error' },
    ];
    await render(<ConsoleCard cards={cardsUi({ terminal: { lines, hideValues: true, error: null } })} actions={a} theme={theme} />);
    expect(screen.queryByText('// value: 3')).toBeNull();
    expect(screen.getByText('G28')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Clear console'));
    expect(calls.clearConsole).toHaveLength(1);
    await fireEvent(screen.getByLabelText('Hide value lines'), 'valueChange', false);
    expect(calls.setHideValues).toEqual([[false]]);
    await render(<ConsoleCard cards={cardsUi({ terminal: { lines, hideValues: false, error: null } })} actions={a} theme={theme} />);
    expect(screen.getByText('// value: 3')).toBeTruthy();
    await render(<ConsoleCard cards={cardsUi()} actions={a} theme={theme} />);
    expect(screen.getByText('Nothing yet.')).toBeTruthy();
  });

  it('Files: only Load files before the list, and not while printing; then search, details', async () => {
    const st = base(); // printing
    const { a, calls } = actions();
    await render(<FilesCard state={st} cards={cardsUi()} now={NOW} actions={a} theme={theme} />);
    expect(screen.getByText(/keeps the printer busy/)).toBeTruthy();
    expect(screen.getByText(/Not while printing/)).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Load files'));
    expect(calls.loadFiles).toBeUndefined(); // disabled while printing
    const idle = { ...st, print: { ...st.print, state: 'standby' } };
    await render(<FilesCard state={idle} cards={cardsUi()} now={NOW} actions={a} theme={theme} />);
    await fireEvent.press(screen.getByLabelText('Load files'));
    expect(calls.loadFiles).toEqual([[false]]);
    const files = [
      { path: 'a.gcode', size: 2_400_000, modified: NOW - 3_600_000 * 5, title: 'Alpha', kind: 'gcode', printedBefore: true },
      { path: 'b.gcode', size: 900_000, modified: NOW, title: 'Beta', kind: 'gcode', printedBefore: false },
    ];
    const listed = cardsUi({ files: { files, cachedAt: NOW - 60_000, loading: false, error: null, query: '', printedOnly: false, detailOf: null, detail: null, detailLoading: false, detailError: null } });
    await render(<FilesCard state={idle} cards={listed} now={NOW} actions={a} theme={theme} />);
    expect(screen.getByText('Listed 1 min ago')).toBeTruthy();
    expect(screen.getByText('2.4 MB · 5 h ago · printed before')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Alpha, details'));
    expect(calls.openFile).toEqual([['a.gcode']]);
    await fireEvent.press(screen.getByLabelText('Refresh files'));
    expect(calls.loadFiles?.at(-1)).toEqual([true]);
    const detail = cardsUi({ files: { ...listed.files, detailOf: 'a.gcode', detail: { path: 'a.gcode', estimatedTime: 3600, filamentTotal: 5200, layerCount: 120, thumbnail: null } } });
    await render(<FilesCard state={idle} cards={detail} now={NOW} actions={a} theme={theme} />);
    expect(screen.getByLabelText('Estimated time: 1h')).toBeTruthy();
    expect(screen.getByLabelText('Layers: 120')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Close details'));
    expect(calls.closeFile).toHaveLength(1);
    const none = cardsUi({ files: { ...listed.files, detailOf: 'a.gcode', detail: { path: 'a.gcode', estimatedTime: null, filamentTotal: null, layerCount: null, thumbnail: null } } });
    await render(<FilesCard state={idle} cards={none} now={NOW} actions={a} theme={theme} />);
    expect(screen.getByText('No details on the printer for this file')).toBeTruthy();
  });
});
