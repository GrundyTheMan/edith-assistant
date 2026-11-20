/**
 * The typed commands Edith sends to Jarvis's `POST /v1/printer/command` (C67 E1, ported from Android's `PrinterCommand.kt`). They are Jarvis's own `PrinterCommand`:
 * **all 33 kinds, with exactly these names and fields.** Edith never builds G-code except through `gcode` (the console), and Jarvis checks every command itself.
 * Axes and modes come from the constants below so a typo cannot become another kind. `serializeCommand` writes the literal JSON the server's validator accepts:
 * the kind first, fields in the order below, and optional numbers left out when not set (an empty macro `params` is left out too, as the Android build does).
 */

export const Axis = { x: 'x', y: 'y', z: 'z' } as const;
export const HomeAxes = { all: 'xyz', xy: 'xy', z: 'z' } as const;
export const AirMode = { internal: 'internal', external: 'external', stop: 'stop' } as const;

export type PrinterCommand =
  | { kind: 'pause' }
  | { kind: 'resume' }
  | { kind: 'cancel' }
  | { kind: 'start'; path: string }
  | { kind: 'estop' }
  | { kind: 'restart' }
  | { kind: 'firmwareRestart' }
  | { kind: 'setTemp'; heater: string; target: number }
  | { kind: 'preset'; name: string }
  | { kind: 'heatersOff' }
  | { kind: 'home'; axes: string }
  | { kind: 'jog'; axis: string; distance: number }
  | { kind: 'zOffset'; delta: number }
  | { kind: 'extrude'; length: number; speed: number }
  | { kind: 'motorsOff' }
  | { kind: 'speedFactor'; percent: number }
  | { kind: 'flowFactor'; percent: number }
  | { kind: 'limits'; velocity?: number; accel?: number; scv?: number; cruiseRatio?: number }
  | { kind: 'retraction'; length?: number; speed?: number; extraLength?: number; unretractSpeed?: number }
  | { kind: 'fan'; fan: string; speed: number }
  | { kind: 'light'; level: number }
  | { kind: 'airCirculation'; mode: string }
  | { kind: 'filamentSensor'; sensor: string; enabled: boolean }
  | { kind: 'camera'; on: boolean }
  | { kind: 'macro'; name: string; params?: Record<string, string> }
  | { kind: 'excludeObject'; name: string }
  | { kind: 'gcode'; script: string }
  | { kind: 'deleteFile'; path: string }
  | { kind: 'screenTap'; x: number; y: number }
  | { kind: 'queueAdd'; path: string }
  | { kind: 'queueRemove'; jobId: string }
  | { kind: 'queueStart' }
  | { kind: 'queuePause' };

export type CommandKind = PrinterCommand['kind'];

/** Each kind's fields, in the order they are written. */
const FIELDS: Record<CommandKind, readonly string[]> = {
  pause: [],
  resume: [],
  cancel: [],
  start: ['path'],
  estop: [],
  restart: [],
  firmwareRestart: [],
  setTemp: ['heater', 'target'],
  preset: ['name'],
  heatersOff: [],
  home: ['axes'],
  jog: ['axis', 'distance'],
  zOffset: ['delta'],
  extrude: ['length', 'speed'],
  motorsOff: [],
  speedFactor: ['percent'],
  flowFactor: ['percent'],
  limits: ['velocity', 'accel', 'scv', 'cruiseRatio'],
  retraction: ['length', 'speed', 'extraLength', 'unretractSpeed'],
  fan: ['fan', 'speed'],
  light: ['level'],
  airCirculation: ['mode'],
  filamentSensor: ['sensor', 'enabled'],
  camera: ['on'],
  macro: ['name', 'params'],
  excludeObject: ['name'],
  gcode: ['script'],
  deleteFile: ['path'],
  screenTap: ['x', 'y'],
  queueAdd: ['path'],
  queueRemove: ['jobId'],
  queueStart: [],
  queuePause: [],
};

/** All 33 kinds, for tests and the `quick` map's keys. */
export const COMMAND_KINDS = Object.keys(FIELDS) as CommandKind[];

/** The command as the object Jarvis's validator reads (`kind` first, left-out optionals not present). */
export function commandObject(command: PrinterCommand): Record<string, unknown> {
  const source = command as unknown as Record<string, unknown>;
  const out: Record<string, unknown> = { kind: command.kind };
  for (const f of FIELDS[command.kind]) {
    const v = source[f];
    if (v === undefined || v === null) continue;
    if (f === 'params' && typeof v === 'object' && Object.keys(v as object).length === 0) continue;
    out[f] = v;
  }
  return out;
}

/** The literal JSON text, e.g. `{"kind":"jog","axis":"x","distance":10}`. */
export const serializeCommand = (command: PrinterCommand): string => JSON.stringify(commandObject(command));
