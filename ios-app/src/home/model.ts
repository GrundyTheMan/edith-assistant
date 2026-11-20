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
  | { type: 'close' };

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
  clips?(sinceMs: number, untilMs: number): Promise<DeviceClip[]>;
  /** Where to play a clip, asked at play time because addresses expire (C53). */
  clipSource?(clipId: string): Promise<{ uri: string; headers?: Record<string, string> }>;
  /** Live video (C71). */
  hasLive?: boolean;
  startLive?(deviceId: string): Promise<LiveFeed>;
}

export const ROOM_NONE = 'No room';
