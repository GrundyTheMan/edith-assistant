/**
 * The wire types of the printer part of Jarvis's feed (C67 E1, ported from Android's `PrinterFeedWire.kt`; the contract is `docs/design/jarvis-printer-feed-spec.md`).
 * The shapes are Jarvis's own TypeScript (`src/shared/printer.ts`) with the same field names. **Every parser is total**: a field a newer or older Jarvis leaves out gets its
 * default and a field of the wrong type is ignored, so one odd value never breaks the page. Values Jarvis keeps as string unions (the print state, the connection, a risk)
 * stay `string` and are compared with the constants below, so a new value is shown rather than thrown on.
 */

export const PrinterStates = { standby: 'standby', printing: 'printing', paused: 'paused', complete: 'complete', cancelled: 'cancelled', error: 'error' } as const;
export const Connections = {
  none: 'none',
  connecting: 'connecting',
  ready: 'ready',
  klippyStartup: 'klippy-startup',
  klippyShutdown: 'klippy-shutdown',
  klippyError: 'klippy-error',
  unreachable: 'unreachable',
} as const;
export const Risks = { none: 'none', confirm: 'confirm', danger: 'danger' } as const;

type Obj = Record<string, unknown>;
const asObj = (v: unknown): Obj => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Obj) : {});
const num = (o: Obj, k: string, d = 0): number => (typeof o[k] === 'number' && Number.isFinite(o[k]) ? (o[k] as number) : d);
const numOrNull = (o: Obj, k: string): number | null => (typeof o[k] === 'number' && Number.isFinite(o[k]) ? (o[k] as number) : null);
const str = (o: Obj, k: string, d = ''): string => (typeof o[k] === 'string' ? (o[k] as string) : d);
const strOrNull = (o: Obj, k: string): string | null => (typeof o[k] === 'string' ? (o[k] as string) : null);
const bool = (o: Obj, k: string, d = false): boolean => (typeof o[k] === 'boolean' ? (o[k] as boolean) : d);
const list = <T>(v: unknown, f: (x: unknown) => T): T[] => (Array.isArray(v) ? v.map(f) : []);
const numbers = (v: unknown, d: number[]): number[] => (Array.isArray(v) && v.every((x) => typeof x === 'number') ? (v as number[]) : d);

// --- the printer's state ------------------------------------------------------------------------------------------------------
export interface Layer { current: number; total: number }
export interface PrintInfo {
  state: string;
  filename: string | null;
  title: string | null;
  progress: number;
  printDuration: number;
  totalDuration: number;
  timeLeft: number | null;
  timeLeftFrom: string | null;
  filamentUsed: number;
  layer: Layer | null;
  message: string | null;
  paused: boolean;
  busy: boolean;
}
export interface Heater { id: string; label: string; temperature: number; target: number; power: number; maxTemp: number }
export interface Sensor { id: string; label: string; value: number; unit: string }
export interface Fan { id: string; label: string; speed: number; settable: boolean }
export interface FilamentSensor { id: string; label: string; detected: boolean; enabled: boolean }
export interface FilamentSlot { id: string; material: string; color: string; hex: string; loaded: boolean }
export interface PrintObject { name: string; center: number[] | null; polygon: number[][] | null; excluded: boolean; current: boolean }
/** A camera Jarvis lists. The picture addresses never leave the PC: the phone asks Jarvis for the bytes by id. */
export interface PrinterCamera { id: string; name: string; aspect: number; rotation: number; flipH: boolean; flipV: boolean }
export interface Toolhead {
  position: number[];
  homed: string;
  min: number[];
  max: number[];
  liveVelocity: number;
  maxVelocity: number;
  maxAccel: number;
  squareCornerVelocity: number;
  minimumCruiseRatio: number;
  speedFactor: number;
  extrudeFactor: number;
  zOffset: number;
  motorsOn: boolean;
}
export interface ExtruderInfo { canExtrude: boolean; pressureAdvance: number | null; minExtrudeTemp: number; nozzleDiameter: number | null }
export interface Retraction { length: number; speed: number; extraLength: number; unretractSpeed: number }
export interface PrinterAlert { text: string; time: number }
export interface HostInfo { load: number; memAvailableKb: number }
export interface Versions { moonraker: string | null; mcu: string | null }
export interface BedMesh { profile: string; active: boolean; min: number[]; max: number[]; points: number[][] }
export interface QueueJob { id: string; filename: string; title: string; added: number }
export interface JobQueue { state: string; jobs: QueueJob[] }

/** Everything the Printer page shows (`PrinterState` in Jarvis). */
export interface PrinterState {
  connection: string;
  message: string | null;
  lastSeen: number | null;
  hostname: string | null;
  print: PrintInfo;
  heaters: Heater[];
  sensors: Sensor[];
  fans: Fan[];
  light: number | null;
  toolhead: Toolhead;
  extruder: ExtruderInfo;
  retraction: Retraction | null;
  filamentSensors: FilamentSensor[];
  objects: PrintObject[];
  slots: FilamentSlot[];
  slotsLive: boolean;
  alert: PrinterAlert | null;
  host: HostInfo | null;
  versions: Versions | null;
  cameras: PrinterCamera[];
  mesh: BedMesh | null;
  queue: JobQueue | null;
}

const layer = (v: unknown): Layer | null => (v && typeof v === 'object' ? { current: num(asObj(v), 'current'), total: num(asObj(v), 'total') } : null);
export function parsePrintInfo(v: unknown): PrintInfo {
  const o = asObj(v);
  return {
    state: str(o, 'state', PrinterStates.standby),
    filename: strOrNull(o, 'filename'),
    title: strOrNull(o, 'title'),
    progress: num(o, 'progress'),
    printDuration: num(o, 'printDuration'),
    totalDuration: num(o, 'totalDuration'),
    timeLeft: numOrNull(o, 'timeLeft'),
    timeLeftFrom: strOrNull(o, 'timeLeftFrom'),
    filamentUsed: num(o, 'filamentUsed'),
    layer: layer(o.layer),
    message: strOrNull(o, 'message'),
    paused: bool(o, 'paused'),
    busy: bool(o, 'busy'),
  };
}
const heater = (v: unknown): Heater => {
  const o = asObj(v);
  return { id: str(o, 'id'), label: str(o, 'label'), temperature: num(o, 'temperature'), target: num(o, 'target'), power: num(o, 'power'), maxTemp: num(o, 'maxTemp') };
};
const sensor = (v: unknown): Sensor => {
  const o = asObj(v);
  return { id: str(o, 'id'), label: str(o, 'label'), value: num(o, 'value'), unit: str(o, 'unit') };
};
const fan = (v: unknown): Fan => {
  const o = asObj(v);
  return { id: str(o, 'id'), label: str(o, 'label'), speed: num(o, 'speed'), settable: bool(o, 'settable') };
};
const filamentSensor = (v: unknown): FilamentSensor => {
  const o = asObj(v);
  return { id: str(o, 'id'), label: str(o, 'label'), detected: bool(o, 'detected'), enabled: bool(o, 'enabled') };
};
const slot = (v: unknown): FilamentSlot => {
  const o = asObj(v);
  return { id: str(o, 'id'), material: str(o, 'material'), color: str(o, 'color'), hex: str(o, 'hex'), loaded: bool(o, 'loaded') };
};
const printObject = (v: unknown): PrintObject => {
  const o = asObj(v);
  return {
    name: str(o, 'name'),
    center: Array.isArray(o.center) ? numbers(o.center, []) : null,
    polygon: Array.isArray(o.polygon) ? o.polygon.map((p) => numbers(p, [])) : null,
    excluded: bool(o, 'excluded'),
    current: bool(o, 'current'),
  };
};
const camera = (v: unknown): PrinterCamera => {
  const o = asObj(v);
  return { id: str(o, 'id'), name: str(o, 'name'), aspect: num(o, 'aspect', 4 / 3), rotation: num(o, 'rotation'), flipH: bool(o, 'flipH'), flipV: bool(o, 'flipV') };
};
const toolhead = (v: unknown): Toolhead => {
  const o = asObj(v);
  return {
    position: numbers(o.position, [0, 0, 0]),
    homed: str(o, 'homed'),
    min: numbers(o.min, [0, 0, 0]),
    max: numbers(o.max, [0, 0, 0]),
    liveVelocity: num(o, 'liveVelocity'),
    maxVelocity: num(o, 'maxVelocity'),
    maxAccel: num(o, 'maxAccel'),
    squareCornerVelocity: num(o, 'squareCornerVelocity'),
    minimumCruiseRatio: num(o, 'minimumCruiseRatio'),
    speedFactor: num(o, 'speedFactor', 1),
    extrudeFactor: num(o, 'extrudeFactor', 1),
    zOffset: num(o, 'zOffset'),
    motorsOn: bool(o, 'motorsOn'),
  };
};
const extruder = (v: unknown): ExtruderInfo => {
  const o = asObj(v);
  return { canExtrude: bool(o, 'canExtrude'), pressureAdvance: numOrNull(o, 'pressureAdvance'), minExtrudeTemp: num(o, 'minExtrudeTemp'), nozzleDiameter: numOrNull(o, 'nozzleDiameter') };
};
const retraction = (v: unknown): Retraction | null => {
  if (!v || typeof v !== 'object') return null;
  const o = asObj(v);
  return { length: num(o, 'length'), speed: num(o, 'speed'), extraLength: num(o, 'extraLength'), unretractSpeed: num(o, 'unretractSpeed') };
};
const mesh = (v: unknown): BedMesh | null => {
  if (!v || typeof v !== 'object') return null;
  const o = asObj(v);
  return { profile: str(o, 'profile'), active: bool(o, 'active'), min: numbers(o.min, [0, 0]), max: numbers(o.max, [0, 0]), points: list(o.points, (p) => numbers(p, [])) };
};
const queue = (v: unknown): JobQueue | null => {
  if (!v || typeof v !== 'object') return null;
  const o = asObj(v);
  return {
    state: str(o, 'state', 'ready'),
    jobs: list(o.jobs, (j) => {
      const q = asObj(j);
      return { id: str(q, 'id'), filename: str(q, 'filename'), title: str(q, 'title'), added: num(q, 'added') };
    }),
  };
};

export function parsePrinterState(v: unknown): PrinterState {
  const o = asObj(v);
  const alert = o.alert && typeof o.alert === 'object' ? { text: str(asObj(o.alert), 'text'), time: num(asObj(o.alert), 'time') } : null;
  const host = o.host && typeof o.host === 'object' ? { load: num(asObj(o.host), 'load'), memAvailableKb: num(asObj(o.host), 'memAvailableKb') } : null;
  const versions = o.versions && typeof o.versions === 'object' ? { moonraker: strOrNull(asObj(o.versions), 'moonraker'), mcu: strOrNull(asObj(o.versions), 'mcu') } : null;
  return {
    connection: str(o, 'connection', Connections.none),
    message: strOrNull(o, 'message'),
    lastSeen: numOrNull(o, 'lastSeen'),
    hostname: strOrNull(o, 'hostname'),
    print: parsePrintInfo(o.print),
    heaters: list(o.heaters, heater),
    sensors: list(o.sensors, sensor),
    fans: list(o.fans, fan),
    light: numOrNull(o, 'light'),
    toolhead: toolhead(o.toolhead),
    extruder: extruder(o.extruder),
    retraction: retraction(o.retraction),
    filamentSensors: list(o.filamentSensors, filamentSensor),
    objects: list(o.objects, printObject),
    slots: list(o.slots, slot),
    slotsLive: bool(o, 'slotsLive'),
    alert,
    host,
    versions,
    cameras: list(o.cameras, camera),
    mesh: mesh(o.mesh),
    queue: queue(o.queue),
  };
}
export const isReady = (s: PrinterState) => s.connection === Connections.ready;
export const isActive = (p: PrintInfo) => p.state === PrinterStates.printing || p.state === PrinterStates.paused;

// --- the state route and the commands -----------------------------------------------------------------------------------------
export interface ArmState { armed: boolean; until: number | null }
export interface QuickCheck { allowed: boolean; reason: string | null }
/** The `state` route's answer (a long poll). `rev` unchanged means "nothing new"; a changed `epoch` means Jarvis restarted. */
export interface StateAnswer { rev: number; epoch: number; state: PrinterState; arm: ArmState; quick: Record<string, QuickCheck>; consoleSeq: number; serverTime: number }
/** The check of a command: the dry run's answer, and the body of `CONFIRM_REQUIRED`. */
export interface Check { allowed: boolean; risk: string; reason: string | null; question: string | null; confirm: string | null }

export const parseArm = (v: unknown): ArmState => ({ armed: bool(asObj(v), 'armed'), until: numOrNull(asObj(v), 'until') });
export function parseStateAnswer(v: unknown): StateAnswer {
  const o = asObj(v);
  const quick: Record<string, QuickCheck> = {};
  for (const [k, q] of Object.entries(asObj(o.quick))) quick[k] = { allowed: bool(asObj(q), 'allowed'), reason: strOrNull(asObj(q), 'reason') };
  return { rev: num(o, 'rev'), epoch: num(o, 'epoch'), state: parsePrinterState(o.state), arm: parseArm(o.arm), quick, consoleSeq: num(o, 'consoleSeq'), serverTime: num(o, 'serverTime') };
}
export const parseCheck = (v: unknown): Check => {
  const o = asObj(v);
  return { allowed: bool(o, 'allowed'), risk: str(o, 'risk', Risks.none), reason: strOrNull(o, 'reason'), question: strOrNull(o, 'question'), confirm: strOrNull(o, 'confirm') };
};
/** `{ "sent": "G28", "risk": "confirm" }` */
export const parseCommandSent = (v: unknown): { sent: string; risk: string } => ({ sent: str(asObj(v), 'sent'), risk: str(asObj(v), 'risk', Risks.none) });
export const parseControlsAnswer = (v: unknown): ArmState => parseArm(asObj(v).arm);

// --- what is shared, and the read routes --------------------------------------------------------------------------------------
export interface Preset { name: string; extruder: number; bed: number }
export interface CameraRef { id: string; name: string }
/** `GET /v1/shared` → `printer`: absent means the printer is not shared. */
export interface SharedPrinter { name: string; control: boolean; alerts: boolean; armMinutes: number; presets: Preset[]; cameras: CameraRef[]; features: string[]; schema: number }
export function parseSharedPrinter(v: unknown): SharedPrinter | null {
  if (!v || typeof v !== 'object') return null;
  const o = asObj(v);
  return {
    name: str(o, 'name'),
    control: bool(o, 'control'),
    alerts: bool(o, 'alerts'),
    armMinutes: num(o, 'armMinutes', 2),
    presets: list(o.presets, (p) => ({ name: str(asObj(p), 'name'), extruder: num(asObj(p), 'extruder'), bed: num(asObj(p), 'bed') })),
    cameras: list(o.cameras, (c) => ({ id: str(asObj(c), 'id'), name: str(asObj(c), 'name') })),
    features: list(o.features, (f) => (typeof f === 'string' ? f : '')).filter(Boolean),
    schema: num(o, 'schema', 1),
  };
}

export interface PrinterFile { path: string; size: number; modified: number; title: string; kind: string; printedBefore: boolean }
export interface FilesAnswer { files: PrinterFile[]; cachedAt: number }
export const parseFiles = (v: unknown): FilesAnswer => ({
  files: list(asObj(v).files, (f) => {
    const o = asObj(f);
    return { path: str(o, 'path'), size: num(o, 'size'), modified: num(o, 'modified'), title: str(o, 'title'), kind: str(o, 'kind', 'other'), printedBefore: bool(o, 'printedBefore') };
  }),
  cachedAt: num(asObj(v), 'cachedAt'),
});
export interface PrinterFileDetail { path: string; estimatedTime: number | null; filamentTotal: number | null; layerCount: number | null; thumbnail: string | null }
export const parseFileDetail = (v: unknown): PrinterFileDetail => {
  const o = asObj(v);
  return { path: str(o, 'path'), estimatedTime: numOrNull(o, 'estimatedTime'), filamentTotal: numOrNull(o, 'filamentTotal'), layerCount: numOrNull(o, 'layerCount'), thumbnail: strOrNull(o, 'thumbnail') };
};
export interface HistoryJob { id: string; filename: string; title: string; status: string; start: number; end: number | null; printDuration: number; filamentUsed: number }
export interface HistoryTotals { jobs: number; printTime: number; filamentUsed: number; longestPrint: number }
export interface HistoryAnswer { jobs: HistoryJob[]; totals: HistoryTotals }
export const parseHistory = (v: unknown): HistoryAnswer => {
  const t = asObj(asObj(v).totals);
  return {
    jobs: list(asObj(v).jobs, (j) => {
      const o = asObj(j);
      return { id: str(o, 'id'), filename: str(o, 'filename'), title: str(o, 'title'), status: str(o, 'status'), start: num(o, 'start'), end: numOrNull(o, 'end'), printDuration: num(o, 'printDuration'), filamentUsed: num(o, 'filamentUsed') };
    }),
    totals: { jobs: num(t, 'jobs'), printTime: num(t, 'printTime'), filamentUsed: num(t, 'filamentUsed'), longestPrint: num(t, 'longestPrint') },
  };
};
/** A line of the printer's console; `seq` numbers the lines so the phone asks only for the new ones. */
export interface ConsoleLine { seq: number; time: number; text: string; kind: string }
export interface ConsoleAnswer { lines: ConsoleLine[]; seq: number }
export const parseConsole = (v: unknown): ConsoleAnswer => ({
  lines: list(asObj(v).lines, (l) => {
    const o = asObj(l);
    return { seq: num(o, 'seq'), time: num(o, 'time'), text: str(o, 'text'), kind: str(o, 'kind', 'response') };
  }),
  seq: num(asObj(v), 'seq'),
});
export interface TempSeries { id: string; label: string; temperatures: number[]; targets: number[] | null }
export const parseTemperatures = (v: unknown): TempSeries[] =>
  list(asObj(v).series, (s) => {
    const o = asObj(s);
    return { id: str(o, 'id'), label: str(o, 'label'), temperatures: numbers(o.temperatures, []), targets: Array.isArray(o.targets) ? numbers(o.targets, []) : null };
  });
export interface MacroParam { name: string; default: string | null }
export interface MacroInfo { name: string; description: string | null; params: MacroParam[]; risk: string; group: string }
export interface MacrosAnswer { macros: MacroInfo[]; groups: { id: string; title: string }[]; hidden: string[] }
export const parseMacros = (v: unknown): MacrosAnswer => ({
  macros: list(asObj(v).macros, (m) => {
    const o = asObj(m);
    return {
      name: str(o, 'name'),
      description: strOrNull(o, 'description'),
      params: list(o.params, (p) => ({ name: str(asObj(p), 'name'), default: strOrNull(asObj(p), 'default') })),
      risk: str(o, 'risk', Risks.confirm),
      group: str(o, 'group', 'other'),
    };
  }),
  groups: list(asObj(v).groups, (g) => ({ id: str(asObj(g), 'id'), title: str(asObj(g), 'title') })),
  hidden: list(asObj(v).hidden, (h) => (typeof h === 'string' ? h : '')).filter(Boolean),
});
/** `POST settings` answers what is now saved. */
export interface SettingsAnswer { presets: Preset[]; hiddenMacros: string[] }
export const parseSettings = (v: unknown): SettingsAnswer => ({
  presets: list(asObj(v).presets, (p) => ({ name: str(asObj(p), 'name'), extruder: num(asObj(p), 'extruder'), bed: num(asObj(p), 'bed') })),
  hiddenMacros: list(asObj(v).hiddenMacros, (h) => (typeof h === 'string' ? h : '')).filter(Boolean),
});
/** The error body of a refused command that needs the owner's yes: `{ "error": {…}, "check": {…} }`. */
export const parseCheckErrorBody = (v: unknown): Check | null => (asObj(v).check ? parseCheck(asObj(v).check) : null);
