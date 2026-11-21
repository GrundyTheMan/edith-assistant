import type { DeviceState, PrintState } from '../../home/model';
import type { PrinterFeedSource } from './feed';
import { Connections, PrinterStates, type PrinterState } from './wire';

/** What the printer service asks when the printer does not answer directly: Jarvis's view as a Home state, or null when there is none to give. */
export interface JarvisPrinterRead {
  read(): Promise<DeviceState | null>;
}

const PRINT_STATES: Record<string, PrintState> = {
  [PrinterStates.printing]: 'printing',
  [PrinterStates.paused]: 'paused',
  [PrinterStates.complete]: 'complete',
  [PrinterStates.cancelled]: 'cancelled',
  [PrinterStates.error]: 'error',
};

/**
 * The printer's state as a Home device state, or null when Jarvis itself cannot see the printer (it is off, or none is set up): the tile then stays
 * "not reachable". A Klipper that is starting, shut down or in error is a Klipper state with Jarvis's own words. The printer's latest alert is the job's message
 * when Klipper sent no other (C39 §9.3; the same as Android's `PrinterToHome`).
 */
export function deviceStateFromFeed(s: PrinterState): DeviceState | null {
  if (s.connection === Connections.unreachable || s.connection === Connections.none || s.connection === Connections.connecting) return null;
  const heaters: DeviceState['heaters'] = {};
  const nozzle = s.heaters.find((h) => h.id.startsWith('extruder')) ?? null;
  const bed = s.heaters.find((h) => h.id === 'heater_bed') ?? null;
  if (nozzle) heaters.nozzle = { id: nozzle.id, label: nozzle.label, temperature: nozzle.temperature, target: nozzle.target };
  if (bed) heaters.bed = { id: bed.id, label: bed.label, temperature: bed.temperature, target: bed.target };
  if (s.connection !== Connections.ready) {
    const klipper = s.connection === Connections.klippyStartup ? 'startup' : s.connection === Connections.klippyShutdown ? 'shutdown' : 'error';
    return { print: { state: 'error', progress: 0, timeLeftSec: null, busy: false, klipper, message: s.message && s.message.trim() ? s.message : "Klipper isn't ready", viaJarvis: true }, heaters };
  }
  const state = PRINT_STATES[s.print.state] ?? 'standby';
  const active = state === 'printing' || state === 'paused';
  const alert = s.alert?.text && s.alert.text.trim() ? s.alert.text : undefined;
  return {
    print: {
      state,
      title: s.print.title && s.print.title.trim() ? s.print.title : undefined,
      progress: Math.min(1, Math.max(0, s.print.progress)),
      timeLeftSec: active && s.print.timeLeft !== null ? Math.round(s.print.timeLeft) : null,
      layer: s.print.layer ?? undefined,
      message: s.print.message && s.print.message.trim() ? s.print.message : alert,
      busy: active,
      viaJarvis: true,
    },
    heaters,
  };
}

/**
 * Asks Jarvis how the printer is, once, when the printer did not answer directly. Nothing here runs when the direct read works, or when no Jarvis is paired, the
 * printer is not shared, or Jarvis cannot be reached: all of those are "no answer" (null).
 */
export class FeedPrinterRead implements JarvisPrinterRead {
  constructor(private readonly feed: PrinterFeedSource) {}

  async read(): Promise<DeviceState | null> {
    const access = await this.feed.access();
    if (access.kind !== 'ready') return null;
    const r = await access.api.state(null, null, 0, { quiet: true });
    return r.kind === 'ok' ? deviceStateFromFeed(r.value.state) : null;
  }
}
