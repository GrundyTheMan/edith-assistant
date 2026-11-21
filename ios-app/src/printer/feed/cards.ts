import { Store } from '../../core/store';
import type { PrinterCommand } from './commands';
import { Connections, type ConsoleLine, type HistoryJob, type HistoryTotals, type PrinterFile, type PrinterFileDetail, type PrinterState, type TempSeries } from './wire';
import type { PrinterSession } from './session';
import { wordsForFailure } from './words';

// ---- The pure parts (the same rules as Android's PrinterData.kt) -------------------------------------------------------

/** One line of the temperature chart: a value for each second, oldest first, with the target where the line is a heater. */
export interface ChartLine {
  id: string;
  label: string;
  values: number[];
  targets: number[] | null;
}

/** The chart's data: at most `cap` points a line (20 minutes at a point a second), seeded from Jarvis when the card opens and then given one point per live answer. */
export class ChartBuffer {
  private lines = new Map<string, { label: string; values: number[]; targets: number[] | null }>();
  constructor(private readonly cap = 1200) {}

  /** Replaces everything with what Jarvis has kept (about the last 20 minutes). */
  seed(series: TempSeries[]): void {
    this.lines.clear();
    for (const s of series) this.lines.set(s.id, { label: s.label, values: s.temperatures.slice(-this.cap), targets: s.targets ? s.targets.slice(-this.cap) : null });
  }

  /** One more point for each heater and each temperature sensor (a reading with no unit is not a temperature: not charted). */
  append(state: PrinterState): void {
    for (const h of state.heaters) this.add(h.id, h.label, h.temperature, h.target);
    for (const s of state.sensors) if (s.unit) this.add(s.id, s.label, s.value, null);
  }

  private add(id: string, label: string, value: number, target: number | null) {
    let m = this.lines.get(id);
    if (!m) {
      m = { label, values: [], targets: null };
      this.lines.set(id, m);
    }
    m.values.push(value);
    if (m.values.length > this.cap) m.values.shift();
    if (target !== null) {
      m.targets ??= [];
      m.targets.push(target);
      if (m.targets.length > this.cap) m.targets.shift();
    }
  }

  snapshot(): ChartLine[] {
    return [...this.lines].map(([id, m]) => ({ id, label: m.label, values: [...m.values], targets: m.targets ? [...m.targets] : null }));
  }
}

export const ChartMath = {
  GRID_STEP: 50,
  /** The top of the y axis: the highest value or target seen plus 10 %, never below 50 so an idle printer still has a scale. */
  yMax(lines: ChartLine[], hidden: ReadonlySet<string> = new Set()): number {
    let top = 0;
    for (const l of lines) {
      if (hidden.has(l.id)) continue;
      for (const v of l.values) top = Math.max(top, v);
      for (const v of l.targets ?? []) top = Math.max(top, v);
    }
    return Math.max(50, top * 1.1);
  },
  /** The horizontal lines: every 50 °C from 0 up to `yMax`. */
  gridlines(yMax: number): number[] {
    const out: number[] = [];
    for (let g = 0; g <= yMax; g += 50) out.push(g);
    return out;
  },
  /** "20 min ago" style label for point `index` of `count` points a second apart (the last point is "now"). */
  minutesAgo(index: number, count: number): string {
    const seconds = count - 1 - index;
    return seconds < 30 ? 'now' : `${Math.ceil(seconds / 60)} min ago`;
  },
  /** Where a value sits from the bottom (0) to the top (1) of the plot. */
  fraction(value: number, yMax: number): number {
    return yMax <= 0 ? 0 : Math.min(1, Math.max(0, value / yMax));
  },
  /** At most `max` points of a line for drawing (every n-th, always keeping the newest): the phone does not draw 1200 segments. */
  thin(values: number[], max: number): number[] {
    if (values.length <= max) return values;
    const step = values.length / max;
    const out: number[] = [];
    for (let i = 0; i < max - 1; i++) out.push(values[Math.floor(i * step)]);
    out.push(values[values.length - 1]);
    return out;
  },
};

export const FileList = {
  /** Newest first; a search matches the title or the path in any case; `printedOnly` hides files never printed. */
  shown(files: PrinterFile[], query: string, printedOnly: boolean): PrinterFile[] {
    const q = query.trim().toLowerCase();
    return files
      .filter((f) => !printedOnly || f.printedBefore)
      .filter((f) => !q || f.title.toLowerCase().includes(q) || f.path.toLowerCase().includes(q))
      .sort((a, b) => b.modified - a.modified);
  },
  /** "2.4 MB", "900 KB", "12 B". */
  size(bytes: number): string {
    if (bytes >= 1_000_000) return `${(bytes / 1_000_000).toFixed(1)} MB`;
    if (bytes >= 1_000) return `${Math.floor(bytes / 1_000)} KB`;
    return `${bytes} B`;
  },
};

const KEEP_LINES = 500;
export const ConsoleView = {
  /** The lines to draw: the printer's "// value:" chatter is hidden unless the owner switched that off. */
  visible: (lines: ConsoleLine[], hideValues: boolean): ConsoleLine[] => (hideValues ? lines.filter((l) => !/^\/\/ value:/.test(l.text)) : lines),
  /** Adds the lines after the last one we have; never keeps more than 500 and never repeats a line (by its number). */
  append(old: ConsoleLine[], added: ConsoleLine[]): ConsoleLine[] {
    const last = old.length ? old[old.length - 1].seq : Number.NEGATIVE_INFINITY;
    return [...old, ...added.filter((l) => l.seq > last)].slice(-KEEP_LINES);
  },
};

export interface MeshExtremes {
  spread: number;
  lowest: { value: number; row: number; col: number };
  highest: { value: number; row: number; col: number };
}

export const MeshColors = {
  /** [red, green, blue] 0-255: blue below the middle of the range, red above, near-white in the middle. A flat mesh is the middle colour. */
  rgb(value: number, min: number, max: number): [number, number, number] {
    if (max <= min) return [235, 235, 235];
    const t = Math.min(1, Math.max(0, (value - min) / (max - min)));
    if (t < 0.5) {
      const k = t / 0.5;
      return [Math.trunc(60 + 175 * k), Math.trunc(110 + 125 * k), Math.trunc(220 + 15 * k)];
    }
    const k = (t - 0.5) / 0.5;
    return [Math.trunc(235 - 15 * k), Math.trunc(235 - 150 * k), Math.trunc(235 - 160 * k)];
  },
  extremes(points: number[][]): MeshExtremes | null {
    let lo: MeshExtremes['lowest'] | null = null;
    let hi: MeshExtremes['highest'] | null = null;
    points.forEach((row, r) =>
      row.forEach((v, c) => {
        if (!lo || v < lo.value) lo = { value: v, row: r, col: c };
        if (!hi || v > hi.value) hi = { value: v, row: r, col: c };
      }),
    );
    return lo && hi ? { spread: (hi as MeshExtremes['highest']).value - (lo as MeshExtremes['lowest']).value, lowest: lo, highest: hi } : null;
  },
};

// ---- What the cards read beyond the live state --------------------------------------------------------------------------

export interface HistoryUi {
  jobs: HistoryJob[];
  totals: HistoryTotals | null;
  loading: boolean;
  /** More jobs may be asked for (the last page was full). */
  more: boolean;
  error: string | null;
}
export interface FilesUi {
  /** Null until the owner presses Load files: nothing is requested before that (it keeps the printer busy for seconds). */
  files: PrinterFile[] | null;
  cachedAt: number;
  loading: boolean;
  error: string | null;
  query: string;
  printedOnly: boolean;
  detailOf: string | null;
  detail: PrinterFileDetail | null;
  detailLoading: boolean;
  detailError: string | null;
}
export interface ConsoleUi {
  lines: ConsoleLine[];
  hideValues: boolean;
  error: string | null;
}
/** The newest camera picture; `number` changes with each one so the page redraws even for equal bytes. */
export interface CameraUi {
  cameraId: string | null;
  frame: Uint8Array | null;
  number: number;
  offline: boolean;
}
export interface CardsUi {
  chart: ChartLine[];
  chartHidden: ReadonlySet<string>;
  history: HistoryUi;
  files: FilesUi;
  terminal: ConsoleUi;
  camera: CameraUi;
}

const initial = (): CardsUi => ({
  chart: [],
  chartHidden: new Set(),
  history: { jobs: [], totals: null, loading: false, more: false, error: null },
  files: { files: null, cachedAt: 0, loading: false, error: null, query: '', printedOnly: true, detailOf: null, detail: null, detailLoading: false, detailError: null },
  terminal: { lines: [], hideValues: true, error: null },
  camera: { cameraId: null, frame: null, number: 0, offline: false },
});

export const HISTORY_PAGE = 50;
/** The wait between camera pictures; the next is asked for after the last arrived. Jarvis keeps a picture for 500 ms and allows 120 requests a minute. */
export const CAMERA_GAP_MS = 700;

const realDelay = (ms: number, signal: AbortSignal) =>
  new Promise<void>((resolve) => {
    const t = setTimeout(resolve, ms);
    signal.addEventListener('abort', () => {
      clearTimeout(t);
      resolve();
    });
  });

export interface CardDataDeps {
  session: Pick<PrinterSession, 'state' | 'routes'>;
  delay?: (ms: number, signal: AbortSignal) => Promise<void>;
}

/**
 * The data the Printer page's cards read from Jarvis beyond the live state (C39 §8.4, C67 E5). Everything here is lazy and tied to what is on screen:
 * the chart is read when Temperatures opens, history and console when they open, the camera is asked for about once a second only while its card is
 * open, and **the file list is never asked for until Load files is pressed**. `visible` is how the page says which cards are open.
 */
export class PrinterCardData {
  readonly ui = new Store<CardsUi>(initial());
  private readonly chart = new ChartBuffer();
  private readonly open = new Set<string>();
  private readonly delay: (ms: number, signal: AbortSignal) => Promise<void>;
  private lastRev = -1;
  private lastEpoch = -1;
  private consoleSeq = 0;
  private chartSeeded = false;
  private historyLoaded = false;
  private consoleLoaded = false;
  private cameraCtl: AbortController | null = null;
  private consoleBusy = false;
  private generation = 0;

  constructor(private readonly deps: CardDataDeps) {
    this.delay = deps.delay ?? realDelay;
    deps.session.state.subscribe(() => {
      const s = deps.session.state.get();
      if (s.kind === 'live' && !s.stale) this.onLive(s.data);
    });
  }

  private update(f: (u: CardsUi) => CardsUi) {
    this.ui.set(f(this.ui.get()));
  }

  /** The page left the screen: nothing is polled, and the next visit reads the chart, history and console again (they have a gap now). */
  detached(): void {
    this.generation++;
    this.stopCamera();
    this.chartSeeded = false;
    this.historyLoaded = false;
    this.consoleLoaded = false;
  }

  /** The page came back: the cards that are open load again. */
  attached(): void {
    for (const id of [...this.open]) if (this.needsLoad(id)) this.opened(id);
  }

  /** A card was opened or folded. */
  visible(card: string, isOpen: boolean): void {
    if (isOpen) {
      this.open.add(card);
      if (this.needsLoad(card)) this.opened(card);
    } else {
      this.open.delete(card);
      if (card === 'camera') this.stopCamera();
    }
  }

  private needsLoad(card: string): boolean {
    switch (card) {
      case 'temperatures':
        return !this.chartSeeded;
      case 'history':
        return !this.historyLoaded;
      case 'console':
        return !this.consoleLoaded;
      case 'camera':
        return !this.cameraCtl;
      default:
        return false;
    }
  }

  private opened(card: string) {
    if (card === 'temperatures') void this.seedChart();
    else if (card === 'history') void this.loadHistory(0);
    else if (card === 'console') void this.loadConsole();
    else if (card === 'camera') this.startCamera();
  }

  // --- The live answers ---
  private onLive(a: { rev: number; epoch: number; state: PrinterState; consoleSeq: number }) {
    const fresh = a.epoch !== this.lastEpoch || a.rev !== this.lastRev;
    this.lastRev = a.rev;
    if (a.epoch !== this.lastEpoch) {
      this.lastEpoch = a.epoch;
      this.consoleSeq = 0;
    }
    if (fresh) {
      this.chart.append(a.state);
      this.update((u) => ({ ...u, chart: this.chart.snapshot() }));
    }
    if (this.open.has('console') && this.consoleLoaded && a.consoleSeq > this.consoleSeq) void this.fetchNewConsole();
  }

  // --- Temperatures ---
  private async seedChart() {
    const routes = this.deps.session.routes();
    if (!routes) return;
    const gen = this.generation;
    const r = await routes.temperatures();
    if (r.kind !== 'ok' || gen !== this.generation) return; // otherwise the chart builds up from the live answers
    this.chart.seed(r.value);
    this.chartSeeded = true;
    this.update((u) => ({ ...u, chart: this.chart.snapshot() }));
  }

  toggleSeries(id: string): void {
    this.update((u) => {
      const hidden = new Set(u.chartHidden);
      if (!hidden.delete(id)) hidden.add(id);
      return { ...u, chartHidden: hidden };
    });
  }

  // --- History ---
  private async loadHistory(start: number) {
    const routes = this.deps.session.routes();
    if (!routes || this.ui.get().history.loading) return;
    const gen = this.generation;
    this.update((u) => ({ ...u, history: { ...u.history, loading: true, error: null } }));
    const r = await routes.history(start);
    if (gen !== this.generation) {
      this.update((u) => ({ ...u, history: { ...u.history, loading: false } }));
      return;
    }
    if (r.kind === 'ok') {
      this.historyLoaded = true;
      this.update((u) => ({ ...u, history: { jobs: start === 0 ? r.value.jobs : [...u.history.jobs, ...r.value.jobs], totals: r.value.totals, loading: false, more: r.value.jobs.length >= HISTORY_PAGE, error: null } }));
    } else {
      this.update((u) => ({ ...u, history: { ...u.history, loading: false, error: wordsForFailure(r) } }));
    }
  }

  /** "More": the next 50 jobs. */
  moreHistory(): void {
    void this.loadHistory(this.ui.get().history.jobs.length);
  }

  // --- Console ---
  private async loadConsole() {
    const routes = this.deps.session.routes();
    if (!routes) return;
    const gen = this.generation;
    const r = await routes.console(null, 200);
    if (gen !== this.generation) return;
    if (r.kind === 'ok') {
      this.consoleLoaded = true;
      this.consoleSeq = r.value.seq;
      this.update((u) => ({ ...u, terminal: { ...u.terminal, lines: r.value.lines, error: null } }));
    } else {
      this.update((u) => ({ ...u, terminal: { ...u.terminal, error: wordsForFailure(r) } }));
    }
  }

  /** Only the lines after the last one we have: no polling, no re-reading 200 lines. */
  private async fetchNewConsole() {
    const routes = this.deps.session.routes();
    if (!routes || this.consoleBusy) return;
    this.consoleBusy = true;
    try {
      const lines = this.ui.get().terminal.lines;
      const after = lines.length ? lines[lines.length - 1].seq : null;
      const r = await routes.console(after, 200);
      if (r.kind === 'ok') {
        this.consoleSeq = r.value.seq;
        this.update((u) => ({ ...u, terminal: { ...u.terminal, lines: ConsoleView.append(u.terminal.lines, r.value.lines) } }));
      }
    } finally {
      this.consoleBusy = false;
    }
  }

  setHideValues(on: boolean): void {
    this.update((u) => ({ ...u, terminal: { ...u.terminal, hideValues: on } }));
  }
  /** Clear empties the view only; the printer keeps its lines. */
  clearConsole(): void {
    this.update((u) => ({ ...u, terminal: { ...u.terminal, lines: [] } }));
  }

  // --- Files ---
  /** The only thing that asks Jarvis for the list. `fresh` re-lists; the page does not offer it while printing. */
  async loadFiles(fresh: boolean): Promise<void> {
    const routes = this.deps.session.routes();
    if (!routes || this.ui.get().files.loading) return;
    this.update((u) => ({ ...u, files: { ...u.files, loading: true, error: null } }));
    const r = await routes.files(fresh);
    if (r.kind === 'ok') this.update((u) => ({ ...u, files: { ...u.files, files: r.value.files, cachedAt: r.value.cachedAt, loading: false } }));
    else this.update((u) => ({ ...u, files: { ...u.files, loading: false, error: wordsForFailure(r) } }));
  }
  setFileQuery(q: string): void {
    this.update((u) => ({ ...u, files: { ...u.files, query: q } }));
  }
  setPrintedOnly(on: boolean): void {
    this.update((u) => ({ ...u, files: { ...u.files, printedOnly: on } }));
  }
  async openFile(path: string): Promise<void> {
    const routes = this.deps.session.routes();
    if (!routes) return;
    this.update((u) => ({ ...u, files: { ...u.files, detailOf: path, detail: null, detailLoading: true, detailError: null } }));
    const r = await routes.fileDetail(path);
    this.update((u) => {
      if (u.files.detailOf !== path) return u; // the sheet was closed or another file opened meanwhile
      return r.kind === 'ok' ? { ...u, files: { ...u.files, detail: r.value, detailLoading: false } } : { ...u, files: { ...u.files, detailLoading: false, detailError: wordsForFailure(r) } };
    });
  }
  /**
   * A command of the Files details was sent (Print, Add to queue or Delete): the details have done their job and close, and a deleted file leaves the list at once (the list
   * is not asked for again: it keeps the printer busy for seconds). Called only when Jarvis accepted the command, so a refusal or a "No" leaves the details as they are.
   */
  commandSent(command: PrinterCommand): void {
    if (command.kind !== 'start' && command.kind !== 'queueAdd' && command.kind !== 'deleteFile') return;
    this.update((u) => {
      const list = command.kind === 'deleteFile' ? (u.files.files?.filter((f) => f.path !== command.path) ?? null) : u.files.files;
      return { ...u, files: { ...u.files, files: list, detailOf: null, detail: null, detailLoading: false, detailError: null } };
    });
  }

  closeFile(): void {
    this.update((u) => ({ ...u, files: { ...u.files, detailOf: null, detail: null, detailLoading: false, detailError: null } }));
  }

  // --- Camera ---
  chooseCamera(id: string): void {
    this.update((u) => ({ ...u, camera: { cameraId: id, frame: null, number: 0, offline: false } }));
    if (this.open.has('camera')) {
      this.stopCamera();
      this.startCamera();
    }
  }

  private stopCamera() {
    this.cameraCtl?.abort();
    this.cameraCtl = null;
  }

  /** About one picture a second while the Camera card is open and the page is on screen; two failures in a row say "Camera offline" (and it goes on trying). */
  private startCamera() {
    this.cameraCtl?.abort();
    const ctl = new AbortController();
    this.cameraCtl = ctl;
    void (async () => {
      let failures = 0;
      while (!ctl.signal.aborted) {
        const s = this.deps.session.state.get();
        const live = s.kind === 'live' ? s.data : null;
        const routes = this.deps.session.routes();
        const cameras = live?.state.cameras ?? [];
        const wanted = this.ui.get().camera.cameraId;
        const id = (wanted && cameras.some((c) => c.id === wanted) ? wanted : cameras[0]?.id) ?? null;
        if (live && routes && id && live.state.connection === Connections.ready) {
          const r = await routes.snapshot(id, ctl.signal);
          if (ctl.signal.aborted) return;
          if (r.kind === 'ok') {
            failures = 0;
            this.update((u) => ({ ...u, camera: { cameraId: id, frame: r.value, number: u.camera.number + 1, offline: false } }));
          } else if (++failures >= 2) {
            this.update((u) => ({ ...u, camera: { ...u.camera, cameraId: id, offline: true } }));
          }
        }
        await this.delay(CAMERA_GAP_MS, ctl.signal);
      }
    })();
  }
}
