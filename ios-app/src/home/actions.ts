import type { ConfirmationSpec } from '../tools/framework/tool';
import type { Capability, DeviceCommand, DeviceState, HomeDevice } from './model';

/** The `action` values of `home_control` (C25 §4). */
export const ACTIONS = [
  'turn_on',
  'turn_off',
  'set_brightness',
  'set_percentage',
  'set_color',
  'set_white',
  'set_temperature',
  'set_thermostat_mode',
  'lock',
  'unlock',
  'open',
  'close',
] as const;
export type Action = (typeof ACTIONS)[number];

/** Which capability each action needs. */
const NEEDS: Record<Action, Capability> = {
  turn_on: 'on_off',
  turn_off: 'on_off',
  set_brightness: 'brightness',
  set_percentage: 'percentage',
  set_color: 'color',
  set_white: 'white',
  set_temperature: 'thermostat',
  set_thermostat_mode: 'thermostat',
  lock: 'lock_unlock',
  unlock: 'lock_unlock',
  open: 'open_close',
  close: 'open_close',
};

const FAN_WORDS: Record<string, number> = { low: 33, medium: 66, med: 66, high: 100 };
/** Setpoints are limited to 50-90 °F / 10-32 °C (C25 §4). */
export const SETPOINT_LIMITS = { F: [50, 90], C: [10, 32] } as const;

export const canDo = (d: HomeDevice, a: Action) => d.capabilities.includes(NEEDS[a]);
export const actionsFor = (d: HomeDevice): Action[] => ACTIONS.filter((a) => canDo(d, a));

export type Built = { ok: true; command: DeviceCommand } | { ok: false; error: string };

/** Turns the model's action and value into a command, refusing what the device can't do with what it can. */
export function buildCommand(d: HomeDevice, action: string, value: string | null): Built {
  if (!(ACTIONS as readonly string[]).includes(action)) return { ok: false, error: `Unknown action "${action}". Actions: ${ACTIONS.join(', ')}.` };
  const a = action as Action;
  if (!canDo(d, a)) return { ok: false, error: `${d.name} can't ${a.replace(/_/g, ' ')}. It can: ${actionsFor(d).join(', ') || 'nothing from here'}.` };
  const num = value === null ? NaN : Number(value.replace('%', '').trim());
  switch (a) {
    case 'turn_on':
    case 'turn_off':
    case 'lock':
    case 'unlock':
    case 'open':
    case 'close':
      return { ok: true, command: { type: a } };
    case 'set_brightness':
      return Number.isFinite(num) ? { ok: true, command: { type: a, value: Math.round(Math.min(100, Math.max(0, num))) } } : { ok: false, error: 'set_brightness needs a value from 0 to 100.' };
    case 'set_percentage': {
      const word = value ? FAN_WORDS[value.trim().toLowerCase()] : undefined;
      const n = word ?? num;
      return Number.isFinite(n) ? { ok: true, command: { type: a, value: Math.round(Math.min(100, Math.max(0, n))) } } : { ok: false, error: 'set_percentage needs 0 to 100, or low, medium or high.' };
    }
    case 'set_color':
    case 'set_white':
      return value && value.trim() ? { ok: true, command: { type: a, value: value.trim().toLowerCase() } } : { ok: false, error: `${a} needs a value.` };
    case 'set_temperature': {
      const [lo, hi] = SETPOINT_LIMITS[d.unit ?? 'F'];
      if (!Number.isFinite(num)) return { ok: false, error: 'set_temperature needs a number.' };
      if (num < lo || num > hi) return { ok: false, error: `The setpoint has to be between ${lo} and ${hi} °${d.unit ?? 'F'}.` };
      return { ok: true, command: { type: a, value: Math.round(num) } };
    }
    case 'set_thermostat_mode': {
      const mode = value?.trim().toLowerCase() ?? '';
      if (d.modes && !d.modes.includes(mode)) return { ok: false, error: `${d.name} modes: ${d.modes.join(', ')}.` };
      if (!mode) return { ok: false, error: 'set_thermostat_mode needs a mode.' };
      return { ok: true, command: { type: 'set_mode', value: mode } };
    }
  }
}

/** The "are you sure" card for a command, or null (C25 §4): unlock, open, close and mode changes always ask. */
export function confirmationFor(d: HomeDevice, c: DeviceCommand): ConfirmationSpec | null {
  const where: [string, string][] = [['Device', d.room && d.room !== 'No room' ? `${d.name} (${d.room})` : d.name]];
  switch (c.type) {
    case 'unlock':
      return { title: `Unlock ${d.name}?`, details: where, confirmLabel: 'Unlock' };
    case 'open':
      return { title: `Open ${d.name}?`, details: where, confirmLabel: 'Open' };
    case 'close':
      return { title: `Close ${d.name}?`, details: where, confirmLabel: 'Close' };
    case 'set_mode':
      return { title: `Change ${d.name} to ${c.value}?`, details: [...where, ['Now', d.state.mode ?? 'unknown']], confirmLabel: 'Change' };
    case 'set_temperature': {
      const now = d.state.setpoint;
      const big = d.unit === 'C' ? 3 : 5;
      if (now !== undefined && Math.abs(c.value - now) <= big) return null;
      return {
        title: `Set ${d.name} to ${c.value}°${d.unit ?? 'F'}?`,
        details: [...where, ['Now', now === undefined ? 'unknown' : `${now}°${d.unit ?? 'F'}`]],
        confirmLabel: 'Set',
      };
    }
    default:
      return null;
  }
}

/** "On, 60%", "Locked", "Heat to 70°F, now 68°F" ... */
export function stateWords(d: HomeDevice): string {
  if (!d.online) return 'Not responding';
  const s: DeviceState = d.state;
  const parts: string[] = [];
  if (s.lock) parts.push(s.lock[0].toUpperCase() + s.lock.slice(1));
  if (s.door) parts.push(s.door[0].toUpperCase() + s.door.slice(1));
  if (d.type === 'thermostat') {
    const u = `°${d.unit ?? 'F'}`;
    if (s.mode) parts.push(s.mode === 'off' ? 'Off' : `${s.mode[0].toUpperCase()}${s.mode.slice(1)}${s.setpoint !== undefined ? ` to ${s.setpoint}${u}` : ''}`);
    if (s.temperature !== undefined) parts.push(`now ${s.temperature}${u}`);
  } else if (s.on !== undefined) {
    parts.push(s.on ? 'On' : 'Off');
    if (s.on && s.brightness !== undefined) parts.push(`${s.brightness}%`);
    if (s.on && s.percentage !== undefined) parts.push(`${s.percentage}%`);
    if (s.on && s.color) parts.push(s.color);
    if (s.on && s.white) parts.push(s.white);
  } else if (s.percentage !== undefined) {
    parts.push(`${s.percentage}%`);
  }
  if (s.battery !== undefined) parts.push(`battery ${s.battery}%`);
  return parts.length ? parts.join(', ') : 'No state reported';
}

/** "moved 17 h ago" for a door or lock that reports when it last moved (C26's "moved … ago"); empty when it doesn't. */
export function movedWords(changedAt: number | undefined, now: number): string {
  if (changedAt === undefined) return '';
  const age = ageWords(changedAt, now);
  return age ? `moved ${age}` : '';
}

/** "3 min ago" for the tile, from the time the state was reported. */
export function ageWords(stateAt: number | null, now: number): string {
  if (stateAt === null) return '';
  const s = Math.max(0, Math.round((now - stateAt) / 1000));
  if (s < 45) return 'just now';
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  return h < 24 ? `${h} h ago` : `${Math.round(h / 24)} d ago`;
}
