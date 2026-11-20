import type { DeviceCommand, DeviceState, HomeBackend, HomeDevice } from './model';

/**
 * The Demo home (C25 §9): made-up devices so the Home tab and the tools can be tried with no service. Locks, the door
 * and the thermostat report a change a moment late, as real clouds do. Development builds only (Expo Go is one).
 */
export class DemoBackend implements HomeBackend {
  readonly id = 'demo';
  readonly name = 'Demo home';
  private readonly devices_: Map<string, HomeDevice>;
  /** A command's effect on locks and the thermostat shows after this long. */
  constructor(
    private readonly now: () => number = Date.now,
    private readonly lagMs = 4000,
  ) {
    const mk = (d: Omit<HomeDevice, 'backend' | 'online' | 'stateAt'>): [string, HomeDevice] => [d.id, { ...d, backend: 'demo', online: true, stateAt: this.now() }];
    this.devices_ = new Map([
      mk({ id: 'porch', name: 'Porch light', room: 'Outside', type: 'light', capabilities: ['on_off', 'brightness'], state: { on: false, brightness: 100 } }),
      mk({ id: 'lamp', name: 'Living room lamp', room: 'Living room', type: 'light', capabilities: ['on_off', 'brightness', 'color', 'white'], state: { on: true, brightness: 60, white: 'warm' } }),
      mk({ id: 'plug', name: 'Kitchen plug', room: 'Kitchen', type: 'plug', capabilities: ['on_off'], state: { on: false } }),
      mk({ id: 'fan', name: 'Bedroom fan', room: 'Bedroom', type: 'fan', capabilities: ['on_off', 'percentage'], state: { on: false, percentage: 33 } }),
      mk({
        id: 'thermostat',
        name: 'Hallway thermostat',
        room: 'Hallway',
        type: 'thermostat',
        capabilities: ['thermostat'],
        unit: 'F',
        modes: ['heat', 'cool', 'auto', 'off'],
        state: { mode: 'heat', setpoint: 68, temperature: 67 },
      }),
      mk({ id: 'front', name: 'Front door', room: 'Entry', type: 'lock', capabilities: ['lock_unlock'], state: { lock: 'locked' } }),
      mk({ id: 'back', name: 'Back door', room: 'Kitchen', type: 'lock', capabilities: ['lock_unlock'], state: { lock: 'locked' } }),
      mk({ id: 'garage', name: 'Garage door', room: 'Garage', type: 'garage_door', capabilities: ['open_close'], state: { door: 'closed' } }),
    ]);
  }

  private pendingUntil = new Map<string, number>();
  private target = new Map<string, DeviceState>();

  async devices(): Promise<HomeDevice[]> {
    return [...this.devices_.keys()].map((id) => this.view(id));
  }
  async state(id: string): Promise<DeviceState> {
    return this.view(id).state;
  }

  /** A device with a lagging change in flight still shows its old state until the lag has passed. */
  private view(id: string): HomeDevice {
    const d = this.devices_.get(id);
    if (!d) throw new Error(`Unknown device ${id}`);
    const until = this.pendingUntil.get(id);
    if (until !== undefined && this.now() >= until) {
      this.devices_.set(id, { ...d, state: { ...d.state, ...this.target.get(id) }, stateAt: this.now() });
      this.pendingUntil.delete(id);
      this.target.delete(id);
    }
    const out = this.devices_.get(id)!;
    return { ...out, state: { ...out.state } };
  }

  async command(id: string, c: DeviceCommand): Promise<void> {
    const d = this.devices_.get(id);
    if (!d) throw new Error(`Unknown device ${id}`);
    const change = this.change(d, c);
    if (d.type === 'lock' || d.type === 'thermostat' || d.type === 'garage_door') {
      this.pendingUntil.set(id, this.now() + this.lagMs);
      this.target.set(id, { ...this.target.get(id), ...change });
    } else {
      this.devices_.set(id, { ...d, state: { ...d.state, ...change }, stateAt: this.now() });
    }
  }

  private change(d: HomeDevice, c: DeviceCommand): DeviceState {
    switch (c.type) {
      case 'turn_on':
        return { on: true };
      case 'turn_off':
        return { on: false };
      case 'set_brightness':
        return { on: true, brightness: c.value };
      case 'set_percentage':
        return { on: c.value > 0, percentage: c.value };
      case 'set_color':
        return { on: true, color: c.value, white: undefined };
      case 'set_white':
        return { on: true, white: c.value, color: undefined };
      case 'set_temperature':
        return { setpoint: c.value };
      case 'set_mode':
        return { mode: c.value };
      case 'lock':
        return { lock: 'locked' };
      case 'unlock':
        return { lock: 'unlocked' };
      case 'open':
        return { door: 'open' };
      case 'close':
        return { door: 'closed' };
    }
  }
}
