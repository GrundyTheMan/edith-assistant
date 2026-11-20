/** The smart-home model (C25 on iOS): devices, their state, commands, and what a service has to provide. */

export type DeviceType = 'light' | 'switch' | 'plug' | 'fan' | 'thermostat' | 'lock' | 'garage_door' | 'camera' | 'doorbell' | 'printer' | 'sensor' | 'speaker' | 'other';
export type Capability = 'on_off' | 'brightness' | 'percentage' | 'color' | 'white' | 'thermostat' | 'lock_unlock' | 'open_close' | 'snapshot' | 'events' | 'live' | 'print_control';

/** Every field optional, as Jarvis's StateValues: a device reports what it knows. */
export interface DeviceState {
  on?: boolean;
  /** 0-100 */
  brightness?: number;
  /** Fan speed, 0-100 */
  percentage?: number;
  color?: string;
  white?: string;
  temperature?: number;
  setpoint?: number;
  mode?: string;
  lock?: 'locked' | 'unlocked' | 'jammed';
  door?: 'open' | 'closed' | 'opening' | 'closing';
  battery?: number;
  /** When the device last moved (a door, a lock), ms since the epoch: "moved 17 h ago". */
  changedAt?: number;
  /** A device that can only be reached on the home Wi-Fi and isn't right now: it shows its last report and says so (C50). */
  homeOnly?: boolean;
  /** A 3D printer's job (C55). */
  print?: PrintJob;
  /** A 3D printer's nozzle and bed (C55). */
  heaters?: { nozzle?: HeaterReading; bed?: HeaterReading };
}

export type PrintState = 'standby' | 'printing' | 'paused' | 'complete' | 'cancelled' | 'error';
export interface PrintJob {
  state: PrintState;
  filename?: string;
  /** The file's name for people ("Benchy", "Name (plate 1)"). */
  title?: string;
  /** 0..1, the slicer's own (M73) when the file sends it. */
  progress: number;
  /** Seconds left, or null when too early to say. */
  timeLeftSec: number | null;
  layer?: { current: number; total: number };
  message?: string;
  /** Printing or paused (or Klipper's idle timeout says printing). */
  busy: boolean;
  /** Klipper isn't ready (starting, shut down, error): Klipper's first line is in `message`. */
  klipper?: 'startup' | 'shutdown' | 'error';
}
export interface HeaterReading {
  id: string;
  label: string;
  temperature: number;
  target: number;
}

export interface HomeDevice {
  /** Unique across backends. */
  id: string;
  name: string;
  room: string;
  type: DeviceType;
  capabilities: Capability[];
  backend: string;
  online: boolean;
  state: DeviceState;
  /** When `state` was reported (ms since the epoch), or null when it never was. */
  stateAt: number | null;
  /** The thermostat's unit. */
  unit?: 'F' | 'C';
  modes?: string[];
}

export type DeviceCommand =
  | { type: 'turn_on' }
  | { type: 'turn_off' }
  | { type: 'set_brightness'; value: number }
  | { type: 'set_percentage'; value: number }
  | { type: 'set_color'; value: string }
  | { type: 'set_white'; value: string }
  | { type: 'set_temperature'; value: number }
  | { type: 'set_mode'; value: string }
  | { type: 'lock' }
  | { type: 'unlock' }
  | { type: 'open' }
  | { type: 'close' }
  | { type: 'pause' }
  | { type: 'resume' }
  | { type: 'cancel_print' };

/** Something a camera or doorbell noticed (C51, C52). */
export interface DeviceEvent {
  id: string;
  deviceId: string;
  deviceName: string;
  kind: 'ring' | 'motion' | 'person' | 'live' | 'other';
  at: number;
}
/** A recorded clip (C53). */
export interface DeviceClip {
  id: string;
  deviceId: string;
  deviceName: string;
  backend: string;
  kind: DeviceEvent['kind'];
  at: number;
  durationSec?: number;
}
/** A page of clips from one service. `noPlan`: the account keeps no recordings (Ring without a Ring Protect plan): not an error. */
export interface ClipsPage {
  clips: DeviceClip[];
  noPlan?: boolean;
}
/** Where to play a clip, and the header the player must send with it (Blink's video needs the bearer token). */
export interface ClipSource {
  uri: string;
  headers?: Record<string, string>;
}
/** A live video feed (C71 defines it; declared here so backends can name the type). */
export type LiveFeed = unknown;

/** One connected service (Meross, Ring, ...), or the Demo home. Each real one is its own chunk. */
export interface HomeBackend {
  id: string;
  name: string;
  /** Alexa-style: another backend's copy of a device wins over this one's (D19). */
  isHub?: boolean;
  devices(): Promise<HomeDevice[]>;
  state(deviceId: string): Promise<DeviceState>;
  command(deviceId: string, command: DeviceCommand): Promise<void>;
  /** Read limit for this service (default 10 s). */
  timeoutMs?: number;
  /** A fresh picture from a camera (C49's `camera_snapshot`; C51, C52). */
  snapshot?(deviceId: string, signal?: AbortSignal): Promise<{ jpegBase64: string; takenAt: number }>;
  snapshotTimeoutMs?: number;
  /** What a camera noticed since `sinceMs` (C51, C52). `deviceId` null: every camera. */
  events?(deviceId: string | null, sinceMs: number): Promise<DeviceEvent[]>;
  /** Recorded clips (C53). */
  hasClips?: boolean;
  clips?(sinceMs: number, untilMs: number): Promise<ClipsPage>;
  /** Where to play a clip, asked at play time because addresses expire (C53). */
  clipSource?(clipId: string): Promise<ClipSource>;
  /** Live video (C71). */
  hasLive?: boolean;
  startLive?(deviceId: string): Promise<LiveFeed>;
}

export const ROOM_NONE = 'No room';
