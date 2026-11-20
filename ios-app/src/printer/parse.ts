import type { HeaterReading, PrintJob, PrintState } from '../home/model';

/** Parsing Moonraker's answers (C55), ported from Jarvis's `shared/printer.ts` (`parseStatus`, `timeLeft`, `fileTitle`). Never throws on odd input. */

const PRINT_STATES: PrintState[] = ['standby', 'printing', 'paused', 'complete', 'cancelled', 'error'];
export type Connection = 'ready' | 'klippy-startup' | 'klippy-shutdown' | 'klippy-error';

export interface PrinterReading {
  connection: Connection;
  /** Klipper's own message when it isn't ready ("Printer is shutdown …"): its first line. */
  message?: string;
  print: PrintJob;
  heaters: { nozzle?: HeaterReading; bed?: HeaterReading };
}

type Obj = Record<string, unknown>;
const obj = (v: unknown): Obj => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Obj) : {});
const num = (v: unknown, dflt = 0): number => (typeof v === 'number' && Number.isFinite(v) ? v : dflt);
const str = (v: unknown): string | undefined => (typeof v === 'string' ? v : undefined);

/** Below this progress the estimate swings wildly, so no estimate is given (Jarvis's rule). */
const ESTIMATE_FROM_PROGRESS = 0.01;
/** Nor before this many seconds of printing. */
const ESTIMATE_FROM_SECONDS = 60;

/**
 * Seconds left. With the slicer's total estimate: the part of it still to print when `byTime` (the progress is the slicer's own, from
 * M73, which is what the printer's screen shows), else the total minus the time printed. Without one: time printed ÷ progress minus time
 * printed, and null too early to say.
 */
export function timeLeft(progress: number, printDuration: number, estimatedTime?: number, byTime = false): number | null {
  const p = Math.min(1, Math.max(0, Number.isFinite(progress) ? progress : 0));
  if (estimatedTime && Number.isFinite(estimatedTime) && estimatedTime > 0) {
    return Math.round(Math.max(0, byTime ? estimatedTime * (1 - p) : estimatedTime - printDuration));
  }
  if (!(progress >= ESTIMATE_FROM_PROGRESS) || !(printDuration >= ESTIMATE_FROM_SECONDS)) return null;
  return Math.max(0, printDuration / p - printDuration);
}

/** Undoes the + and %XX escapes FlashForge's file names carry; leaves a broken escape as it is. */
function unescapeName(s: string): string {
  const spaced = s.replace(/\+/g, ' ');
  try {
    return decodeURIComponent(spaced);
  } catch {
    return spaced;
  }
}

/** A file's name for people. ZMOD prints a .3mf by extracting `3mf/model/Name_plate_1/Metadata/plate_1.gcode`: that becomes "Name (plate 1)". */
export function fileTitle(path: string): string {
  const m = /^3mf\/model\/(.+?)\/Metadata\/plate_(\d+)\.gcode$/i.exec(path);
  if (m) {
    const name = m[1].endsWith(`_plate_${m[2]}`) ? m[1].slice(0, -`_plate_${m[2]}`.length) : m[1];
    return `${unescapeName(name).trim()} (plate ${m[2]})`;
  }
  const base = path.split('/').pop() ?? path;
  return unescapeName(base.replace(/\.(gcode|g|gco|3mf|ufp)$/i, '')).trim() || path;
}

/** "1h 14m", "52m", "45s"; "0s" for nothing or nonsense. */
export function formatDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 1) return '0s';
  const s = Math.round(seconds);
  if (s < 60) return `${s}s`;
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return h ? `${h}h ${m}m` : `${m}m`;
}

const heater = (id: string, label: string, raw: unknown): HeaterReading => {
  const v = obj(raw);
  return { id, label, temperature: num(v.temperature), target: num(v.target) };
};

/** Klipper's raw objects (`objects/query`'s `status`) into what the card shows. */
export function parseStatus(raw: unknown, estimatedTime?: number): PrinterReading {
  const r = obj(raw);
  let connection: Connection = 'ready';
  let message: string | undefined;
  const webhooks = obj(r.webhooks);
  if (typeof webhooks.state === 'string' && webhooks.state !== 'ready') {
    connection = webhooks.state === 'shutdown' ? 'klippy-shutdown' : webhooks.state === 'startup' ? 'klippy-startup' : 'klippy-error';
    message = str(webhooks.state_message)?.split('\n')[0];
  }

  const ps = obj(r.print_stats);
  const stateText = str(ps.state) as PrintState | undefined;
  const state: PrintState = stateText && PRINT_STATES.includes(stateText) ? stateText : 'standby';
  const filename = str(ps.filename) || undefined;
  const printDuration = num(ps.print_duration);
  const info = obj(ps.info);
  const layer = typeof info.total_layer === 'number' && info.total_layer > 0 ? { current: num(info.current_layer), total: info.total_layer } : undefined;
  // display_status.progress is the file's M73 (the slicer's progress by time) while it prints, else the file position again.
  const filePos = num(obj(r.virtual_sdcard).progress, num(obj(r.display_status).progress));
  const shown = num(obj(r.display_status).progress, filePos);
  const byTime = shown > 0 && Math.abs(shown - filePos) > 1e-6;
  const progress = Math.min(1, Math.max(0, byTime ? shown : filePos));
  const busy = state === 'printing' || state === 'paused' || obj(r.idle_timeout).state === 'Printing';

  const print: PrintJob = {
    state,
    filename,
    title: filename ? fileTitle(filename) : undefined,
    progress,
    timeLeftSec: busy ? timeLeft(progress, printDuration, estimatedTime, byTime) : null,
    layer,
    message: str(ps.message) || undefined,
    busy,
  };
  const heaters: PrinterReading['heaters'] = {};
  if (r.extruder !== undefined) heaters.nozzle = heater('extruder', 'Nozzle', r.extruder);
  if (r.heater_bed !== undefined) heaters.bed = heater('heater_bed', 'Bed', r.heater_bed);
  return { connection, message, print, heaters };
}

export interface Webcam {
  name: string;
  snapshotUrl: string;
}
/** The first enabled webcam with a snapshot address from `/server/webcams/list`'s `result`. */
export function parseWebcams(result: unknown): Webcam[] {
  const list = obj(result).webcams;
  if (!Array.isArray(list)) return [];
  const out: Webcam[] = [];
  for (const w of list) {
    const o = obj(w);
    const url = str(o.snapshot_url);
    if (o.enabled !== false && url) out.push({ name: str(o.name) ?? 'camera', snapshotUrl: url });
  }
  return out;
}

/** Moonraker's own port: the API, never the web page that the camera's relative addresses belong to. */
export const MOONRAKER_PORT = 7125;

/**
 * Where a webcam's snapshot is. A relative address ("/webcam/?action=snapshot") is on the printer's **web server**, not on Moonraker's API port: with the
 * usual setup (Moonraker on :7125, the web page on :80) the web server is the host without the port; only when the owner typed some other port (a proxy
 * serving both) is it that port. Asking :7125 for /webcam/ answers HTTP 404 (found on the owner's printer, 2026-11-20; Android does the same as this).
 * An address that says `localhost` (ZMOD does) means the printer, not this phone: swap in the printer's host and keep the webcam's port.
 */
export function resolveWebcamUrl(base: string, url: string): string {
  const parts = /^(https?):\/\/([^/:?#]+)(?::(\d+))?/i.exec(base);
  const printerHost = parts?.[2] ?? '';
  const origin = parts ? `${parts[1]}://${parts[2]}${parts[3] && Number(parts[3]) !== MOONRAKER_PORT ? `:${parts[3]}` : ''}` : base;
  if (/^https?:\/\//i.test(url)) {
    return url.replace(/^(https?:\/\/)(localhost|127\.0\.0\.1|0\.0\.0\.0)(?=[:/?#]|$)/i, `$1${printerHost}`);
  }
  if (url.startsWith('//')) return `http:${url}`.replace(/^(http:\/\/)(localhost|127\.0\.0\.1)/i, `$1${printerHost}`);
  return url.startsWith('/') ? origin + url : `${origin}/${url}`;
}

/**
 * Turns what the owner types into Moonraker's address: `192.168.4.86` → `http://192.168.4.86:7125`. A hostname is accepted but the form says to
 * use the IP address (iOS only allows plain HTTP to raw IPs). Returns a message when it can't be an address.
 */
export function normalizePrinterAddress(input: string): { ok: true; base: string } | { ok: false; message: string } {
  const text = input.trim();
  if (!text) return { ok: false, message: 'Type the printer\'s address, like 192.168.4.86.' };
  const withScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(text) ? text : `http://${text}`;
  const m = /^(https?):\/\/([^/:?#@\s]+)(?::(\d{1,5}))?\/?$/i.exec(withScheme);
  if (!m) return { ok: false, message: "That doesn't look like an address. Try 192.168.4.86 or http://192.168.4.86:7125." };
  const port = m[3] ? Number(m[3]) : MOONRAKER_PORT;
  if (port < 1 || port > 65535) return { ok: false, message: 'The port has to be between 1 and 65535.' };
  return { ok: true, base: `${m[1].toLowerCase()}://${m[2]}:${port}` };
}
