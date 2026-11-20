import { DemoBackend } from '../home/demoBackend';
import { Home } from '../home/home';
import type { ClipsPage, DeviceClip, DeviceEvent, HomeBackend, HomeDevice } from '../home/model';
import { memoryStore } from '../settings/settings';
import { AlertChoices } from './model';
import { AlertWatcher } from './watcher';

/** What `Home.activity` hands the alerts: clips when a service has them, else each camera's events (Android's AlertActivityTest). */
function camera(id: string, page: ClipsPage | Error | null, events: DeviceEvent[] | Error = []) {
  const calls = { devices: 0, events: [] as [string | null, number][] };
  const dev = (did: string, name: string, type: HomeDevice['type'], caps: HomeDevice['capabilities']): HomeDevice => ({ id: did, name, room: '', type, capabilities: caps, backend: id, online: true, state: {}, stateAt: 0 });
  const backend: HomeBackend = {
    id,
    name: id,
    hasClips: page !== null,
    devices: async () => {
      calls.devices++;
      return [dev('d1', 'Front door', 'doorbell', ['snapshot', 'events']), dev('d2', 'Porch', 'camera', ['snapshot', 'events']), dev('d3', 'Plug', 'plug', [])];
    },
    state: async () => ({}),
    command: async () => {},
    snapshot: async () => ({ jpegBase64: 'QQ==', takenAt: 1 }),
    events: async (deviceId, since) => {
      calls.events.push([deviceId, since]);
      if (events instanceof Error) throw events;
      return events;
    },
    ...(page !== null
      ? {
          clips: async () => {
            if (page instanceof Error) throw page;
            return page;
          },
        }
      : {}),
  };
  return { backend, calls };
}
const clip = (id: string, at: number, kind: DeviceClip['kind'] = 'motion'): DeviceClip => ({ id, deviceId: 'x', deviceName: 'Porch', backend: 'blink', kind, at });
const homeWith = (...b: HomeBackend[]) => {
  const home = new Home({ store: memoryStore() as never, setTimer: () => 0 });
  home.setBackends(b);
  return home;
};

describe('Home.activity', () => {
  it('a service with clips answers from its clips, keyed by service and clip, with no device list', async () => {
    const { backend, calls } = camera('blink', { clips: [clip('c1', 5), clip('c2', 9, 'ring')] });
    const found = await homeWith(backend).activity('blink', 0);
    expect(found.map((a) => a.key)).toEqual(['blink:c1', 'blink:c2']);
    expect(found.map((a) => a.device)).toEqual(['Porch', 'Porch']);
    expect(found[1].kind).toBe('ring');
    expect(calls.devices).toBe(0);
  });

  it('a service with no plan falls back to each camera\'s events (only cameras that report events are asked)', async () => {
    const { backend, calls } = camera('ring', { clips: [], noPlan: true }, [{ id: 'e', deviceId: 'd1', deviceName: 'x', kind: 'ring', at: 70 }]);
    const found = await homeWith(backend).activity('ring', 50);
    expect(found.map((a) => a.device)).toEqual(['Front door', 'Porch']);
    expect(calls.events).toEqual([['d1', 50], ['d2', 50]]);
    expect(found[0].key).toBe('ring:d1:70:ring');
  });

  it('the camera list is kept, not asked for again on every look', async () => {
    const { backend, calls } = camera('ring', null);
    const home = homeWith(backend);
    await home.activity('ring', 0);
    await home.activity('ring', 0);
    expect(calls.devices).toBe(1);
  });

  it('a failing service says so', async () => {
    const { backend } = camera('blink', new Error('it needs signing in again'));
    await expect(homeWith(backend).activity('blink', 0)).rejects.toThrow('it needs signing in again');
    const { backend: ring } = camera('ring', null, new Error('Ring answered HTTP 500 for the events'));
    await expect(homeWith(ring).activity('ring', 0)).rejects.toThrow('HTTP 500');
  });

  it('a service that is not connected is not asked', async () => {
    const { backend } = camera('ring', { clips: [] });
    const home = homeWith(backend);
    await expect(home.activity('nobody', 0)).rejects.toThrow("isn't connected");
  });

  it('an alert\'s camera is found by name, for its picture', async () => {
    const { backend } = camera('ring', null);
    const home = homeWith(backend);
    expect((await home.alertCamera('ring', 'Front door'))?.id).toBe('d1');
    expect(await home.alertCamera('ring', 'Garage')).toBeNull();
    expect(await home.alertCamera('blink', 'Front door')).toBeNull();
    expect((await home.alertCamera('ring', 'Porch'))?.id).toBe('d2');
  });

  it('asks the home tab to open the clips', () => {
    const home = homeWith();
    const seen: number[] = [];
    home.clipsRequest.subscribe(() => seen.push(home.clipsRequest.get()));
    home.requestClips();
    home.requestClips();
    expect(seen).toEqual([1, 2]);
  });
});

describe('the whole path with the Demo doorbell (C72 §7)', () => {
  it('a ring on the Demo home becomes an alert on the next look, once', async () => {
    let t = 1_000_000;
    const demo = new DemoBackend(() => t);
    const home = homeWith(demo);
    const watcher = new AlertWatcher(home, () => t);
    const choices = new AlertChoices(new Set([AlertChoices.key('demo', 'doorbell')]));
    expect((await watcher.check(choices)).alerts).toEqual([]); // learns
    t += 45_000;
    demo.ring();
    t += 1_000;
    const alerts = (await watcher.check(choices)).alerts;
    expect(alerts.map((a) => [a.backend, a.device, a.kind])).toEqual([['demo', 'Doorbell camera', 'doorbell']]);
    t += 45_000;
    expect((await watcher.check(choices)).alerts).toEqual([]);
    expect((await home.alertCamera('demo', 'Doorbell camera'))?.id).toBe('doorbell'); // so its picture can be taken
  });
});
