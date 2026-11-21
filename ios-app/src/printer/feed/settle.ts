import type { PrinterCommand } from './commands';
import { PrinterStates, type Preset, type PrinterState } from './wire';

/** What the settle rules need besides the two states: time since the send, whether the toolhead has moved since, and the phone's presets. */
export interface SettleInfo {
  elapsedMs: number;
  sawMotion: boolean;
  presets: Preset[];
}

/** mm/s: below this the toolhead counts as standing still. */
const STILL_SPEED = 1;
/** ZMOD reports the axes homed at once, before the toolhead has moved: with no movement seen, homing counts as done only after this long. */
const HOME_NO_MOTION_MS = 5_000;

/** How long a button waits for its change to show before giving up; 0 means it does not wait at all. A port of Jarvis's `settleTimeout` (C39 §7.3). */
export function settleTimeoutMs(c: PrinterCommand): number {
  switch (c.kind) {
    case 'home':
    case 'pause':
    case 'resume':
    case 'cancel':
      return 120_000;
    case 'start':
      return 60_000;
    case 'jog':
      return 30_000;
    case 'setTemp':
    case 'preset':
    case 'heatersOff':
      return 15_000;
    case 'light':
    case 'fan':
    case 'speedFactor':
    case 'flowFactor':
    case 'zOffset':
    case 'queueAdd':
    case 'queueRemove':
    case 'queueStart':
    case 'queuePause':
      return 10_000;
    default:
      return 0;
  }
}

const near = (a: number, b: number, by: number) => Math.abs(a - b) <= by;

/** "Has the printer shown the change this command asked for?" (a port of Jarvis's `commandSettled`, with its constants). It only reads the state: the server decides what is allowed. */
export function settled(c: PrinterCommand, before: PrinterState, now: PrinterState, info: SettleInfo): boolean {
  const still = now.toolhead.liveVelocity < STILL_SPEED;
  const target = (id: string): number | undefined => now.heaters.find((h) => h.id === id)?.target;
  const pos = (s: PrinterState, i: number) => s.toolhead.position[i] ?? 0;
  switch (c.kind) {
    case 'home':
      return [...c.axes].every((a) => now.toolhead.homed.includes(a)) && still && (info.sawMotion || info.elapsedMs >= HOME_NO_MOTION_MS);
    case 'jog': {
      const i = c.axis === 'x' ? 0 : c.axis === 'y' ? 1 : 2;
      return still && near(pos(now, i), pos(before, i) + c.distance, 0.05);
    }
    case 'setTemp':
      return target(c.heater) === Math.round(c.target);
    case 'preset': {
      const p = info.presets.find((x) => x.name === c.name);
      return !p || (target('extruder') === p.extruder && target('heater_bed') === p.bed);
    }
    case 'heatersOff':
      return now.heaters.every((h) => h.target === 0);
    case 'pause':
      return now.print.state === PrinterStates.paused;
    case 'resume':
      return now.print.state === PrinterStates.printing && !now.print.paused;
    case 'cancel':
      return now.print.state !== PrinterStates.printing && now.print.state !== PrinterStates.paused;
    case 'start':
      return now.print.state === PrinterStates.printing || now.print.state === PrinterStates.paused;
    case 'light':
      return now.light !== null && near(now.light, c.level, 0.011);
    case 'fan': {
      const f = now.fans.find((x) => x.id === c.fan);
      return !f || near(f.speed, c.speed, 0.011);
    }
    case 'speedFactor':
      return near(now.toolhead.speedFactor * 100, c.percent, 0.5);
    case 'flowFactor':
      return near(now.toolhead.extrudeFactor * 100, c.percent, 0.5);
    case 'zOffset':
      return near(now.toolhead.zOffset, before.toolhead.zOffset + c.delta, 0.0005);
    case 'queueAdd':
      return (now.queue?.jobs.length ?? 0) > (before.queue?.jobs.length ?? 0) || now.queue?.state !== before.queue?.state;
    case 'queueRemove':
      return now.queue ? !now.queue.jobs.some((j) => j.id === c.jobId) : true;
    case 'queueStart':
      return now.queue?.state !== 'paused';
    case 'queuePause':
      return now.queue?.state === 'paused';
    default:
      return true;
  }
}
