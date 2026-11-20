/** The smart-home model (C25 on iOS): devices, their state, commands, and what a service has to provide. */

export type DeviceType = 'light' | 'switch' | 'plug' | 'fan' | 'thermostat' | 'lock' | 'garage_door' | 'other';
export type Capability = 'on_off' | 'brightness' | 'percentage' | 'color' | 'white' | 'thermostat' | 'lock_unlock' | 'open_close';

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

/** One connected service (Meross, Ring, ...), or the Demo home. Each real one is its own chunk. */
export interface HomeBackend {
  id: string;
  name: string;
  /** Alexa-style: another backend's copy of a device wins over this one's (D19). */
  isHub?: boolean;
  devices(): Promise<HomeDevice[]>;
  state(deviceId: string): Promise<DeviceState>;
  command(deviceId: string, command: DeviceCommand): Promise<void>;
}

export const ROOM_NONE = 'No room';
