import * as fs from 'fs';
import * as path from 'path';
import {
  Connections,
  PrinterStates,
  Risks,
  isActive,
  isReady,
  parseCheckErrorBody,
  parseConsole,
  parseFiles,
  parseHistory,
  parseMacros,
  parsePrinterState,
  parseSharedPrinter,
  parseStateAnswer,
  parseTemperatures,
} from './wire';

const fixture = (name: string): unknown => JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures', `${name}.json`), 'utf8'));
const state = (name: string) => parseStateAnswer(fixture(name));

describe("the printer feed wire types (the same fixtures and checks as Android's PrinterFeedWireTest)", () => {
  it("an idle printer parses with every card's data", () => {
    const a = state('state-ready-idle');
    expect(a.rev).toBe(41);
    expect(a.epoch).toBe(1763400000);
    expect(a.consoleSeq).toBe(120);
    expect(isReady(a.state)).toBe(true);
    expect(a.state.print.state).toBe(PrinterStates.standby);
    expect(a.state.print.timeLeft).toBeNull();
    expect(a.state.heaters.map((h) => h.label)).toEqual(['Nozzle', 'Bed']);
    expect(a.state.sensors[1].unit).toBe('');
    expect(a.state.fans[0].settable).toBe(true);
    expect(a.state.fans[1].settable).toBe(false);
    expect(a.state.light).toBe(0);
    expect(a.state.toolhead.homed).toBe('xyz');
    expect(a.state.extruder.nozzleDiameter).toBe(0.4);
    expect(a.state.retraction?.length).toBe(0.8);
    expect(a.state.slots[0].hex).toBe('F72224');
    expect(a.state.cameras.map((c) => c.id)).toEqual(['video', 'screen']);
    expect(a.state.queue?.state).toBe('ready');
    expect(a.quick.pause.allowed).toBe(false);
    expect(a.quick.pause.reason).toBe('Nothing is printing.');
  });

  it('a print in progress parses', () => {
    const a = state('state-printing');
    const p = a.state.print;
    expect(isActive(p)).toBe(true);
    expect(p.progress).toBe(0.4);
    expect(p.layer).toEqual({ current: 12, total: 240 });
    expect(p.timeLeftFrom).toBe('slicer');
    expect(p.timeLeft).toBe(2700);
    expect(a.state.objects[0].polygon?.[0]).toEqual([90, 90]);
    expect(a.state.objects[0].center).toEqual([100, 100]);
    expect(a.arm.armed).toBe(true);
    expect(a.arm.until).toBe(1763400160000);
  });

  it('the other states parse', () => {
    expect(state('state-paused').state.print.state).toBe(PrinterStates.paused);
    const gone = state('state-unreachable').state;
    expect(gone.connection).toBe(Connections.unreachable);
    expect(isReady(gone)).toBe(false);
    expect(gone.lastSeen).toBe(1763399000000);
    expect(state('state-klippy-shutdown').state.connection).toBe(Connections.klippyShutdown);
    expect(state('state-alert').state.alert?.text).toBe('Filament runout detected');
  });

  it('the shared entry says what the owner allowed, and no entry means not shared', () => {
    const p = parseSharedPrinter((fixture('shared-with-printer') as { printer: unknown }).printer)!;
    expect(p.name).toBe('FlashForge AD5X');
    expect(p.control).toBe(true);
    expect(p.armMinutes).toBe(2);
    expect(p.presets[0]).toEqual({ name: 'PLA', extruder: 220, bed: 60 });
    expect(p.cameras[1].id).toBe('screen');
    expect(p.features).toContain('recordings');
    expect(p.schema).toBe(1);
    expect(parseSharedPrinter(undefined)).toBeNull();
    expect(parseSharedPrinter(null)).toBeNull();
    expect(parseSharedPrinter('x')).toBeNull();
  });

  it('the list answers parse', () => {
    const files = parseFiles(fixture('files'));
    expect(files.files).toHaveLength(3);
    expect(files.files[0].printedBefore).toBe(true);
    expect(files.files[2].kind).toBe('3mf');
    expect(files.cachedAt).toBe(1763399000000);

    const history = parseHistory(fixture('history'));
    expect(history.jobs[0].status).toBe('completed');
    expect(history.jobs[1].end).toBeNull();
    expect(history.totals.jobs).toBe(966);

    const temps = parseTemperatures(fixture('temperatures'));
    expect(temps).toHaveLength(3);
    expect(temps[0].targets).toEqual([0, 0, 220, 220]);
    expect(temps[2].targets).toBeNull();

    const macros = parseMacros(fixture('macros'));
    expect(macros.macros[1].group).toBe('calibration');
    expect(macros.macros[1].params[0].default).toBe('default');
    expect(macros.macros[1].risk).toBe(Risks.danger);
    expect(macros.hidden).toEqual(['SECRET_MACRO']);
    expect(macros.groups[0].title).toBe('Filament and nozzle');

    const consoleAnswer = parseConsole(fixture('console'));
    expect(consoleAnswer.seq).toBe(121);
    expect(consoleAnswer.lines[2].kind).toBe('error');
  });

  it('a refused command carries the check or the reason', () => {
    const confirm = parseCheckErrorBody(fixture('check-confirm'))!;
    expect(confirm.allowed).toBe(true);
    expect(confirm.risk).toBe(Risks.confirm);
    expect(confirm.question).toBe('Home all axes?');
    expect(confirm.confirm).toBe('Home');
    expect(parseCheckErrorBody(fixture('error-refused'))).toBeNull(); // a reason, not a check
  });

  it('missing fields take defaults and unknown ones are ignored', () => {
    const a = parseStateAnswer({ rev: 3, brandNew: { x: 1 }, state: { connection: 'ready', somethingNew: true } });
    expect(a.rev).toBe(3);
    expect(a.state.heaters).toEqual([]);
    expect(a.state.print.state).toBe(PrinterStates.standby);
    expect(a.arm.armed).toBe(false);
    expect(a.quick).toEqual({});
    expect(a.state.toolhead.speedFactor).toBe(1);
    expect(a.state.cameras).toEqual([]);
  });

  it('a state value from a newer Jarvis does not throw', () => {
    const a = parseStateAnswer({ rev: 1, state: { connection: 'hibernating', print: { state: 'levelling' } } });
    expect(a.state.connection).toBe('hibernating');
    expect(a.state.print.state).toBe('levelling');
    expect(isActive(a.state.print)).toBe(false);
  });

  it('is total: odd values of the wrong type are ignored, never thrown', () => {
    for (const bad of [null, undefined, 5, 'x', [], [1, 2], true]) {
      expect(() => parseStateAnswer(bad)).not.toThrow();
      expect(() => parsePrinterState(bad)).not.toThrow();
      expect(() => parseFiles(bad)).not.toThrow();
      expect(() => parseHistory(bad)).not.toThrow();
      expect(() => parseMacros(bad)).not.toThrow();
      expect(() => parseConsole(bad)).not.toThrow();
      expect(() => parseTemperatures(bad)).not.toThrow();
    }
    const odd = parsePrinterState({ heaters: [null, 5, { temperature: 'hot', target: 220 }], print: { progress: '40%', layer: 'x' }, toolhead: { position: [1, 'a'] }, objects: [{ polygon: [[1, 2], 'x'] }] });
    expect(odd.heaters.map((h) => h.temperature)).toEqual([0, 0, 0]);
    expect(odd.heaters[2].target).toBe(220);
    expect(odd.print.progress).toBe(0);
    expect(odd.print.layer).toBeNull();
    expect(odd.toolhead.position).toEqual([0, 0, 0]);
    expect(odd.objects[0].polygon).toEqual([[1, 2], []]);
  });

  it('every fixture is here and is valid JSON (none is left out)', () => {
    const names = fs.readdirSync(path.join(__dirname, 'fixtures')).filter((f) => f.endsWith('.json'));
    expect(names).toHaveLength(15);
    for (const f of names) expect(() => JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures', f), 'utf8'))).not.toThrow();
  });
});
