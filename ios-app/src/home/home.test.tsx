import { render, screen, fireEvent } from '@testing-library/react-native';
import { memoryStore } from '../settings/settings';
import { ToolArgs } from '../tools/framework/toolArgs';
import type { Tool, ToolContext } from '../tools/framework/tool';
import { ToolRegistry } from '../tools/framework/toolRegistry';
import { HomeScreen, inChip, runFromScreen } from '../ui/HomeScreen';
import { FakeClock } from '../testing/nodeSqlite';
import { ageWords, buildCommand, confirmationFor, stateWords } from './actions';
import { DemoBackend } from './demoBackend';
import { Home } from './home';
import { homeControlTool, homeDevicesTool } from './homeTools';
import type { DeviceCommand, HomeBackend, HomeDevice } from './model';
import { matchDevice } from './names';

const mockCurrent: { home: unknown } = { home: null };
jest.mock('expo-router', () => ({ useFocusEffect: (cb: () => void | (() => void)) => require('react').useEffect(cb, [cb]) }));
jest.mock('../core/services', () => ({
  useServices: () => ({ home: mockCurrent.home }),
  useStore: (s: { get: () => unknown }) => s.get(),
}));

const dev = (over: Partial<HomeDevice>): HomeDevice => ({
  id: 'x',
  name: 'Porch light',
  room: 'Outside',
  type: 'light',
  capabilities: ['on_off', 'brightness'],
  backend: 'demo',
  online: true,
  state: { on: false },
  stateAt: 0,
  ...over,
});

async function demoHome() {
  let t = 1_000_000;
  const demo = new DemoBackend(() => t, 4000);
  const timers: { fn: () => void; ms: number }[] = [];
  const home = new Home({ store: memoryStore() as never, now: () => t, setTimer: (fn, ms) => void timers.push({ fn, ms }) });
  home.setBackends([demo]);
  await home.refresh();
  return { home, demo, timers, advance: (ms: number) => void (t += ms) };
}
const ctx: ToolContext = { clock: new FakeClock() };
const run = (tool: Tool, args: Record<string, string>) => tool.execute(new ToolArgs(args, tool.spec), ctx);
const confirm = (tool: Tool, args: Record<string, string>) => tool.confirmation!(new ToolArgs(args, tool.spec), ctx);

describe('name resolution', () => {
  const list = [
    dev({ id: 'a', name: 'Porch light', room: 'Outside' }),
    dev({ id: 'b', name: 'Lamp', room: 'Living room' }),
    dev({ id: 'c', name: 'Lamp', room: 'Bedroom' }),
    dev({ id: 'd', name: 'Front door', room: 'Entry', type: 'lock' }),
  ];
  it('matches exact names in any case, and a copied "(Light)"', () => {
    expect(matchDevice(list, 'porch LIGHT')).toMatchObject({ kind: 'found', device: { id: 'a' } });
    expect(matchDevice(list, 'Porch light (Light)')).toMatchObject({ kind: 'found', device: { id: 'a' } });
  });
  it('uses the room: "living room lamp" and "lamp in the bedroom"', () => {
    expect(matchDevice(list, 'living room lamp')).toMatchObject({ kind: 'found', device: { id: 'b' } });
    expect(matchDevice(list, 'lamp in the bedroom')).toMatchObject({ kind: 'found', device: { id: 'c' } });
  });
  it('finds the only name containing the words, and is ambiguous when several do', () => {
    expect(matchDevice(list, 'the front')).toMatchObject({ kind: 'found', device: { id: 'd' } });
    expect(matchDevice(list, 'lamp')).toMatchObject({ kind: 'ambiguous', candidates: [{ id: 'b' }, { id: 'c' }] });
    expect(matchDevice(list, 'garage')).toEqual({ kind: 'none' });
    expect(matchDevice(list, '  ')).toEqual({ kind: 'none' });
  });
});

describe('actions and cards', () => {
  const lock = dev({ id: 'l', name: 'Front door', type: 'lock', capabilities: ['lock_unlock'], state: { lock: 'locked' } });
  const stat = dev({ id: 't', name: 'Thermostat', room: 'Hall', type: 'thermostat', capabilities: ['thermostat'], unit: 'F', modes: ['heat', 'cool', 'off'], state: { mode: 'heat', setpoint: 68, temperature: 67 } });
  const fan = dev({ id: 'f', name: 'Fan', type: 'fan', capabilities: ['on_off', 'percentage'] });

  it('refuses what the device cannot do and says what it can', () => {
    expect(buildCommand(lock, 'turn_on', null)).toEqual({ ok: false, error: expect.stringContaining('It can: lock, unlock') });
    expect(buildCommand(lock, 'dance', null)).toMatchObject({ ok: false });
  });
  it('builds values: fan words, clamped brightness, setpoint limits, modes', () => {
    expect(buildCommand(fan, 'set_percentage', 'low')).toEqual({ ok: true, command: { type: 'set_percentage', value: 33 } });
    expect(buildCommand(fan, 'set_percentage', 'high')).toEqual({ ok: true, command: { type: 'set_percentage', value: 100 } });
    expect(buildCommand(dev({}), 'set_brightness', '150')).toEqual({ ok: true, command: { type: 'set_brightness', value: 100 } });
    expect(buildCommand(dev({}), 'set_brightness', null)).toMatchObject({ ok: false });
    expect(buildCommand(stat, 'set_temperature', '95')).toMatchObject({ ok: false, error: expect.stringContaining('between 50 and 90') });
    expect(buildCommand(stat, 'set_thermostat_mode', 'Cool')).toEqual({ ok: true, command: { type: 'set_mode', value: 'cool' } });
    expect(buildCommand(stat, 'set_thermostat_mode', 'turbo')).toMatchObject({ ok: false });
  });
  it('asks for unlock, open, close and mode changes; not for lock, lights, or a small thermostat move', () => {
    expect(confirmationFor(lock, { type: 'unlock' })?.title).toBe('Unlock Front door?');
    expect(confirmationFor(lock, { type: 'lock' })).toBeNull();
    expect(confirmationFor(dev({ type: 'garage_door' }), { type: 'open' })?.confirmLabel).toBe('Open');
    expect(confirmationFor(dev({ type: 'garage_door' }), { type: 'close' })).not.toBeNull();
    expect(confirmationFor(dev({}), { type: 'turn_on' })).toBeNull();
    expect(confirmationFor(stat, { type: 'set_mode', value: 'cool' })).not.toBeNull();
    expect(confirmationFor(stat, { type: 'set_temperature', value: 70 })).toBeNull();
    expect(confirmationFor(stat, { type: 'set_temperature', value: 74 })?.title).toBe('Set Thermostat to 74°F?');
    expect(confirmationFor({ ...stat, state: { mode: 'heat' } }, { type: 'set_temperature', value: 69 })).not.toBeNull(); // setpoint unknown
  });
  it('writes the state and its age', () => {
    expect(stateWords(dev({ state: { on: true, brightness: 60 } }))).toBe('On, 60%');
    expect(stateWords(lock)).toBe('Locked');
    expect(stateWords(stat)).toBe('Heat to 68°F, now 67°F');
    expect(stateWords(dev({ online: false }))).toBe('Not responding');
    expect(ageWords(null, 5)).toBe('');
    expect(ageWords(0, 20_000)).toBe('just now');
    expect(ageWords(0, 3 * 60_000)).toBe('3 min ago');
  });
});

describe('Home registry', () => {
  const backend = (id: string, devices: HomeDevice[] | Error, opts: { hub?: boolean; delay?: number } = {}): HomeBackend => ({
    id,
    name: id,
    isHub: opts.hub,
    devices: async () => {
      if (opts.delay) await new Promise((r) => setTimeout(r, opts.delay));
      if (devices instanceof Error) throw devices;
      return devices;
    },
    state: async () => ({}),
    command: async () => {},
  });

  it("one service failing is a problem line, and its last-known devices stay", async () => {
    const home = new Home({ store: memoryStore() as never });
    let fail = false;
    const flaky: HomeBackend = { ...backend('flaky', []), devices: async () => (fail ? Promise.reject(new Error('cloud down')) : [dev({ id: 'f1', name: 'Flaky lamp', backend: 'flaky' })]) };
    home.setBackends([flaky, backend('ok', [dev({ id: 'o1', name: 'Ok lamp', backend: 'ok' })])]);
    expect((await home.refresh()).devices.map((d) => d.id).sort()).toEqual(['f1', 'o1']);
    fail = true;
    const second = await home.refresh();
    expect(second.problems).toEqual([expect.stringContaining('flaky: cloud down')]);
    expect(second.devices.map((d) => d.id).sort()).toEqual(['f1', 'o1']);
  });

  it('a service that takes too long is a problem, and the others still answer', async () => {
    const home = new Home({ store: memoryStore() as never, readTimeoutMs: 20 });
    home.setBackends([backend('slow', [], { delay: 200 }), backend('ok', [dev({ id: 'o1', backend: 'ok' })])]);
    const r = await home.refresh();
    expect(r.problems).toEqual(['slow: took too long']);
    expect(r.devices).toHaveLength(1);
  });

  it("drops a hub's copy of a device a direct service reports, but keeps it when the direct one fails", async () => {
    const direct = dev({ id: 'm1', name: 'Garage door', type: 'garage_door', backend: 'meross' });
    const viaHub = dev({ id: 'a1', name: 'garage door', type: 'garage_door', backend: 'alexa' });
    const home = new Home({ store: memoryStore() as never });
    home.setBackends([backend('alexa', [viaHub], { hub: true }), backend('meross', [direct])]);
    expect((await home.refresh()).devices.map((d) => d.id)).toEqual(['m1']);
    home.setBackends([backend('alexa', [viaHub], { hub: true }), backend('meross', new Error('offline'))]);
    expect((await home.refresh()).devices.map((d) => d.id)).toEqual(['a1']);
  });

  it('remembers favourites, hidden devices and the chip, and hides devices from visible()', async () => {
    const store = memoryStore();
    const home = new Home({ store: store as never });
    home.setBackends([backend('b', [dev({ id: 'd1', backend: 'b' }), dev({ id: 'd2', name: 'Other', backend: 'b' })])]);
    await home.refresh();
    home.toggleFavourite('d1');
    home.setHidden('d2', true);
    home.setChip('lights');
    expect(home.visible().map((d) => d.id)).toEqual(['d1']);
    expect(home.hiddenDevices().map((d) => d.id)).toEqual(['d2']);
    await new Promise((r) => setTimeout(r, 0));
    const again = new Home({ store: store as never });
    await again.load();
    expect([again.isFavourite('d1'), again.isHidden('d2'), again.selectedChip]).toEqual([true, true, 'lights']);
    again.setHidden('d2', false);
    expect(again.isHidden('d2')).toBe(false);
  });
});

describe('Demo home and commands', () => {
  it('turns a light on, reports the state, and logs it', async () => {
    const { home } = await demoHome();
    const porch = home.visible().find((d) => d.name === 'Porch light')!;
    const r = await home.command(porch, { type: 'turn_on' }, 'home');
    expect(r).toEqual({ ok: true, text: 'Porch light: On, 100%.' });
    expect(home.commandLog()[0]).toMatchObject({ device: 'Porch light', action: 'turn_on', ok: true, source: 'home' });
  });

  it('a lock reports its change late, and the re-checks pick it up', async () => {
    const { home, timers, advance } = await demoHome();
    const front = home.visible().find((d) => d.name === 'Front door')!;
    const r = await home.command(front, { type: 'unlock' }, 'chat');
    expect(r.text).toContain('Locked'); // still the old state
    expect(r.text).toContain('can take a few seconds');
    expect(timers.map((t) => t.ms)).toEqual([3000, 10000, 30000]);
    advance(5000);
    timers[1].fn();
    await new Promise((r2) => setTimeout(r2, 0));
    expect(home.cached().find((d) => d.id === 'front')?.state.lock).toBe('unlocked');
  });

  it('a backend error is a failed result, logged', async () => {
    const home = new Home({ store: memoryStore() as never });
    const bad: HomeBackend = { id: 'b', name: 'b', devices: async () => [], state: async () => ({}), command: async () => Promise.reject(new Error('nope')) };
    home.setBackends([bad]);
    const r = await home.command(dev({ backend: 'b' }), { type: 'turn_on' }, 'chat');
    expect(r.ok).toBe(false);
    expect(home.commandLog()[0].ok).toBe(false);
  });

  it('keeps the newest 500 log entries', async () => {
    const { home } = await demoHome();
    const porch = home.visible()[0];
    for (let i = 0; i < 505; i++) await home.command(porch, { type: i % 2 ? 'turn_on' : 'turn_off' }, 'home');
    expect(home.commandLog()).toHaveLength(500);
  });
});

describe('home tools', () => {
  it('home_devices lists by room with states, filters, and names a service that did not answer', async () => {
    const { home } = await demoHome();
    const tool = homeDevicesTool(home);
    const all = await run(tool, {});
    expect(all.text).toContain('Kitchen:');
    expect(all.text).toContain('- Front door (lock): Locked');
    const locks = await run(tool, { filter: 'lock' });
    expect(locks.text).toContain('Front door');
    expect(locks.text).not.toContain('Porch light');
    expect((await run(tool, { filter: 'zzz' })).text).toContain('No device matches "zzz".');
    home.setHidden('front', true);
    expect((await run(tool, {})).text).not.toContain('Front door');
  });

  it('home_control does the action and says what the device reports', async () => {
    const { home } = await demoHome();
    const tool = homeControlTool(home);
    expect(await run(tool, { device: 'porch light', action: 'turn_on' })).toEqual({ ok: true, text: 'Porch light: On, 100%.' });
    expect((await run(tool, { device: 'bedroom fan', action: 'set_percentage', value: 'low' })).text).toBe('Bedroom fan: On, 33%.');
    expect((await run(tool, { device: 'front door', action: 'turn_on' })).text).toContain("can't turn on");
    expect((await run(tool, { device: 'toaster', action: 'turn_on' })).text).toContain('No device called "toaster"');
  });

  it('asks the user to pick when the name is ambiguous, and does nothing', async () => {
    const { home } = await demoHome();
    const r = await run(homeControlTool(home), { device: 'door', action: 'unlock' });
    expect(r.ok).toBe(false);
    expect(r.text).toContain('More than one device matches');
    expect(home.cached().find((d) => d.id === 'front')?.state.lock).toBe('locked');
  });

  it('asks for approval to unlock and open, not to lock or switch a light', async () => {
    const { home } = await demoHome();
    const tool = homeControlTool(home);
    expect((await confirm(tool, { device: 'front door', action: 'unlock' }))?.title).toBe('Unlock Front door?');
    expect((await confirm(tool, { device: 'garage door', action: 'open' }))?.title).toBe('Open Garage door?');
    expect(await confirm(tool, { device: 'front door', action: 'lock' })).toBeNull();
    expect(await confirm(tool, { device: 'porch light', action: 'turn_on' })).toBeNull();
    expect(await confirm(tool, { device: 'nothing here', action: 'unlock' })).toBeNull();
  });

  it('is not offered with no service connected', () => {
    const home = new Home({ store: memoryStore() as never });
    const registry = new ToolRegistry([homeDevicesTool(home), homeControlTool(home)]);
    expect(registry.available({ name: 'm', capabilities: ['tools'] })).toHaveLength(0);
    home.setBackends([new DemoBackend()]);
    expect(registry.available({ name: 'm', capabilities: ['tools'] })).toHaveLength(2);
  });
});

describe('Home tab helpers', () => {
  it('chips pick devices by type, favorites by star', () => {
    const l = dev({});
    expect(inChip(l, 'lights', false)).toBe(true);
    expect(inChip(l, 'fans', false)).toBe(false);
    expect(inChip(l, 'favorites', false)).toBe(false);
    expect(inChip(l, 'favorites', true)).toBe(true);
    expect(inChip(dev({ type: 'garage_door' }), 'locks', false)).toBe(true);
    expect(inChip(l, 'all', false)).toBe(true);
  });

  it('sends a plain command at once and asks first for unlock', async () => {
    const { home } = await demoHome();
    const porch = home.visible().find((d) => d.name === 'Porch light')!;
    const front = home.visible().find((d) => d.name === 'Front door')!;
    const ask = jest.fn();
    const send = jest.spyOn(home, 'command');
    runFromScreen(home, porch, { type: 'turn_on' } as DeviceCommand, ask);
    expect(ask).not.toHaveBeenCalled();
    expect(send).toHaveBeenCalledTimes(1);
    runFromScreen(home, front, { type: 'unlock' }, ask);
    expect(ask).toHaveBeenCalledWith('Unlock Front door?', 'Unlock', expect.any(Function));
    expect(send).toHaveBeenCalledTimes(1); // nothing sent until the owner agrees
    ask.mock.calls[0][2]();
    expect(send).toHaveBeenCalledTimes(2);
  });
});

describe('HomeScreen', () => {
  it('shows tiles and chips, toggles a light, and has explicit lock buttons', async () => {
    const { home } = await demoHome();
    mockCurrent.home = home;
    home.setChip('all');
    await render(<HomeScreen />);
    expect(screen.getByText('Porch light')).toBeTruthy();
    expect(screen.getAllByText('Locks and doors')).toHaveLength(2); // the chip and the section
    await fireEvent.press(screen.getByLabelText('Turn Porch light on'));
    expect(home.commandLog()[0]?.action).toBe('turn_on');
    expect(screen.getByLabelText('Unlock Front door')).toBeTruthy();
  });
});
