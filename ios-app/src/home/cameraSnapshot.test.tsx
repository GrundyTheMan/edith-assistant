import { memoryStore } from '../settings/settings';
import { ToolArgs } from '../tools/framework/toolArgs';
import type { Tool, ToolContext } from '../tools/framework/tool';
import { ToolRegistry } from '../tools/framework/toolRegistry';
import { FakeClock } from '../testing/nodeSqlite';
import { DEMO_JPEG, DemoBackend } from './demoBackend';
import { Home } from './home';
import { cameraSnapshotTool } from './homeTools';
import type { HomeBackend, HomeDevice } from './model';

const ctx: ToolContext = { clock: new FakeClock() };
const run = (tool: Tool, args: Record<string, string>) => tool.execute(new ToolArgs(args, tool.spec), ctx);
const cam = (id: string, name: string): HomeDevice => ({ id, name, room: 'Entry', type: 'doorbell', capabilities: ['snapshot'], backend: 'cams', online: true, state: {}, stateAt: 0 });

async function homeWith(backend: HomeBackend) {
  const home = new Home({ store: memoryStore() as never, setTimer: () => 0 });
  home.setBackends([backend]);
  await home.refresh();
  return home;
}
const backend = (devices: HomeDevice[], snapshot: HomeBackend['snapshot'], snapshotTimeoutMs?: number): HomeBackend => ({
  id: 'cams',
  name: 'Cams',
  devices: async () => devices,
  state: async () => ({}),
  command: async () => {},
  snapshot,
  snapshotTimeoutMs,
});

describe('camera_snapshot', () => {
  it('returns the Demo doorbell picture with a plain sentence', async () => {
    const demo = new DemoBackend(() => 1_000_000);
    const home = new Home({ store: memoryStore() as never, setTimer: () => 0 });
    home.setBackends([demo]);
    await home.refresh();
    const r = await run(cameraSnapshotTool(home, () => 1_000_000), { device: 'doorbell' });
    expect(r.ok).toBe(true);
    expect(r.text).toBe('Showing Doorbell camera, taken just now.');
    expect(r.images).toEqual([{ fileName: expect.stringMatching(/^doorbell-camera-\d+\.jpg$/), mimeType: 'image/jpeg', base64: DEMO_JPEG }]);
  });

  it('says how old the picture is', async () => {
    const home = await homeWith(backend([cam('a', 'Front door')], async () => ({ jpegBase64: 'QQ==', takenAt: 0 })));
    const r = await run(cameraSnapshotTool(home, () => 5 * 60_000), { device: 'front door' });
    expect(r.text).toBe('Showing Front door, taken 5 min ago.');
  });

  it('asks which camera when the name is ambiguous, names the cameras when none matches', async () => {
    const home = await homeWith(backend([cam('a', 'Front door'), cam('b', 'Back door')], async () => ({ jpegBase64: 'QQ==', takenAt: 0 })));
    const tool = cameraSnapshotTool(home);
    expect((await run(tool, { device: 'door' })).text).toContain('More than one device matches');
    const none = await run(tool, { device: 'garage' });
    expect(none.ok).toBe(false);
    expect(none.text).toContain('Cameras: Front door, Back door');
  });

  it("reports the service's own words when it can't get a picture", async () => {
    const home = await homeWith(
      backend([cam('a', 'Front door')], async () => {
        throw new Error('Front door is offline.');
      }),
    );
    const r = await run(cameraSnapshotTool(home), { device: 'front door' });
    expect(r).toEqual({ ok: false, text: 'Front door is offline.' });
  });

  it("gives up at the service's own limit", async () => {
    const home = await homeWith(backend([cam('a', 'Front door')], () => new Promise(() => {}), 30));
    const r = await run(cameraSnapshotTool(home), { device: 'front door' });
    expect(r.ok).toBe(false);
    expect(r.text).toContain("didn't answer");
  });

  it('a device whose service cannot take pictures says so', async () => {
    const home = await homeWith(backend([cam('a', 'Front door')], undefined));
    const r = await run(cameraSnapshotTool(home), { device: 'front door' });
    expect(r.text).toBe("Front door can't show a picture.");
  });

  it('is offered only while a camera exists', async () => {
    const home = new Home({ store: memoryStore() as never, setTimer: () => 0 });
    const registry = new ToolRegistry([cameraSnapshotTool(home)]);
    expect(registry.available({ name: 'm', capabilities: ['tools'] })).toHaveLength(0);
    home.setBackends([backend([cam('a', 'Front door')], async () => ({ jpegBase64: 'QQ==', takenAt: 0 }))]);
    await home.refresh();
    expect(registry.available({ name: 'm', capabilities: ['tools'] })).toHaveLength(1);
  });
});
