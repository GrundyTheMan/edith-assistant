import type { PrinterCommand } from './commands';
import { Axis } from './commands';
import type { Preset } from './wire';

/**
 * What the owner types into the page's boxes, and the commands made from it (C39 §8.4, ported from Android's `PrinterInput`). Every function returns null when the text is not
 * usable, and the button that needs it stays disabled: an empty box never sends anything. Jarvis checks every value again; these only keep obvious nonsense from being sent.
 */

const NUMBER = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/;

/** A number as a person types it: "1.5" or "1,5", blanks around it ignored. Null for nothing, text, hex, NaN or infinity. */
export function number(text: string): number | null {
  const t = text.trim().replace(',', '.');
  if (!t || !NUMBER.test(t)) return null;
  const n = Number(t);
  return Number.isFinite(n) ? n : null;
}

/** A number in a range (both ends allowed), or null. */
export function between(text: string, min: number, max: number): number | null {
  const n = number(text);
  return n !== null && n >= min && n <= max ? n : null;
}

type Cmd<K extends PrinterCommand['kind']> = Extract<PrinterCommand, { kind: K }>;

/** Sets a heater's target: 0 up to the highest target Klipper allows for it. */
export function setTemp(heater: string, text: string, maxTemp: number): Cmd<'setTemp'> | null {
  const n = between(text, 0, maxTemp > 0 ? maxTemp : 300);
  return n === null ? null : { kind: 'setTemp', heater, target: n };
}

/** Extrude or retract: a length above zero (at most 100 mm at a time) and a speed above zero (at most 50 mm/s). */
export function extrude(lengthText: string, speedText: string, retract: boolean): Cmd<'extrude'> | null {
  const length = between(lengthText, 0.01, 100);
  const speed = between(speedText, 0.1, 50);
  if (length === null || speed === null) return null;
  return { kind: 'extrude', length: retract ? -length : length, speed };
}

/** A box that may be left empty: undefined when empty, null when what is typed is no good, else the number. */
function optional(text: string, ok: (n: number) => boolean): number | null | undefined {
  if (!text.trim()) return undefined;
  const n = number(text);
  return n !== null && ok(n) ? n : null;
}

/**
 * The speed limits: only the boxes that hold a number are sent; a box left empty is left alone. Nothing typed at all, or a number that cannot be right (zero or negative), is
 * null. The cruise ratio is a fraction from 0 to 1.
 */
export function limits(velocity: string, accel: string, scv: string, cruiseRatio: string): Cmd<'limits'> | null {
  const v = optional(velocity, (n) => n > 0);
  const a = optional(accel, (n) => n > 0);
  const s = optional(scv, (n) => n > 0);
  const c = optional(cruiseRatio, (n) => n >= 0 && n <= 1);
  if (v === null || a === null || s === null || c === null) return null;
  if (v === undefined && a === undefined && s === undefined && c === undefined) return null;
  return { kind: 'limits', ...(v !== undefined && { velocity: v }), ...(a !== undefined && { accel: a }), ...(s !== undefined && { scv: s }), ...(c !== undefined && { cruiseRatio: c }) };
}

/** Firmware retraction: the same rule (empty boxes are left alone; negative is wrong; at least one number). */
export function retraction(length: string, speed: string, extraLength: string, unretractSpeed: string): Cmd<'retraction'> | null {
  const l = optional(length, (n) => n >= 0);
  const sp = optional(speed, (n) => n > 0);
  const ex = optional(extraLength, (n) => n >= 0);
  const un = optional(unretractSpeed, (n) => n > 0);
  if (l === null || sp === null || ex === null || un === null) return null;
  if (l === undefined && sp === undefined && ex === undefined && un === undefined) return null;
  return { kind: 'retraction', ...(l !== undefined && { length: l }), ...(sp !== undefined && { speed: sp }), ...(ex !== undefined && { extraLength: ex }), ...(un !== undefined && { unretractSpeed: un }) };
}

/** The console takes one line of at most 200 characters; an empty line, a longer one or one with a line break is not sent. */
export const MAX_LINE = 200;
export function consoleLine(text: string): Cmd<'gcode'> | null {
  const t = text.trim();
  if (!t || t.length > MAX_LINE || /[\r\n]/.test(t)) return null;
  return { kind: 'gcode', script: t };
}

/** One preset as typed: its name and the two temperatures. */
export interface PresetRow {
  name: string;
  nozzle: string;
  bed: string;
}
export const MAX_PRESETS = 10;
const MAX_PRESET_NAME = 30;
const MAX_NOZZLE = 300;
const MAX_BED = 120;

/** Why these presets cannot be saved, in plain words (Jarvis's own rules: at most 10, a name of up to 30 characters, no two alike, 0 to 300 and 0 to 120 °C), or null. */
export function presetsProblem(rows: PresetRow[]): string | null {
  if (rows.length > MAX_PRESETS) return `At most ${MAX_PRESETS} presets.`;
  const seen = new Set<string>();
  for (const r of rows) {
    const name = r.name.trim();
    if (!name) return 'Every preset needs a name.';
    if (name.length > MAX_PRESET_NAME) return `A preset's name is at most ${MAX_PRESET_NAME} characters.`;
    if (seen.has(name.toLowerCase())) return `There are two presets called ${name}.`;
    seen.add(name.toLowerCase());
    if (between(r.nozzle, 0, MAX_NOZZLE) === null) return `The nozzle temperature of ${name} must be from 0 to ${MAX_NOZZLE} °C.`;
    if (between(r.bed, 0, MAX_BED) === null) return `The bed temperature of ${name} must be from 0 to ${MAX_BED} °C.`;
  }
  return null;
}

/** The presets to send (whole degrees), or null when `presetsProblem` says no. An empty list is allowed: Jarvis then uses its defaults. */
export function presets(rows: PresetRow[]): Preset[] | null {
  if (presetsProblem(rows) !== null) return null;
  return rows.map((r) => ({ name: r.name.trim(), extruder: Math.round(number(r.nozzle)!), bed: Math.round(number(r.bed)!) }));
}

/** A percentage the slider gives (whole numbers only; the server's range is enforced there). */
export const percent = (value: number): number => Math.round(value);

/** A 0 to 1 level from a slider that goes from 0 to 100. */
export const level = (value: number): number => Math.round(value) / 100;

/** The jog steps in mm. 100 mm is only offered for X and Y. */
export const STEPS = [0.1, 1, 10, 50, 100];
export const stepsFor = (axis: string): number[] => (axis === Axis.z ? STEPS.filter((s) => s < 100) : STEPS);

/** The Z offset buttons: what each adds, in mm. */
export const Z_OFFSET_STEPS = [0.01, 0.05, 0.1];

/** A step as it is written on its chip: 0.1, 1, 10. */
export const stepLabel = (step: number): string => String(step);
