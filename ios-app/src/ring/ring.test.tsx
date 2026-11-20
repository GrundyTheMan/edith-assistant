import { bytesToBase64 } from '../core/bytes';
import { memorySecrets } from '../core/secrets';
import { secureFetch } from '../core/secureFetch';
import { Home } from '../home/home';
import { eventTime, recentEventsTool } from '../home/homeTools';
import type { HomeBackend } from '../home/model';
import type { HttpFetch } from '../ollama/client';
import { memoryStore } from '../settings/settings';
import { FakeClock } from '../testing/nodeSqlite';
import { jsonResponse, streamResponse } from '../testing/fakeHttp';
import { ToolArgs } from '../tools/framework/toolArgs';
import type { Tool } from '../tools/framework/tool';
import { RingApi, CLIENT_ID, USER_AGENT } from './api';
import { batteryOf, parseCameras, parseEvents } from './parse';
import { RingService } from './service';

const JPEG = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3]);
const CODE = '123456';
const NOW = Date.parse('2026-11-20T18:00:00Z');

interface Seen {
  method: string;
  url: string;
  hardwareId: string | undefined;
  auth: string | undefined;
  code: string | undefined;
  body: Record<string, unknown> | null;
  userAgent: string | undefined;
}

/**
 * A pretend Ring that behaves like the real one where it matters: the refresh token **rotates** (the old one is refused at once),
 * every request is recorded with its hardware id, the 2FA code is checked, and a session must be started before the lists answer.
 */
class PretendRing {
  seen: Seen[] = [];
  refreshTokens = new Set<string>();
  accessTokens = new Set<string>();
  private counter = 0;
  sessionFor = new Set<string>();
  needSession = true;
  devices: unknown = {
    doorbots: [{ id: 111, description: 'Front door', kind: 'lpd_v4', location_id: 'loc1', battery_life: '84', alerts: { connection: 'online' }, settings: { motion_detection_enabled: true } }],
    stickup_cams: [{ id: 222, description: 'Driveway', kind: 'stickup_cam_v4', location_id: 'loc1', battery_life: 60, battery_life_2: 40, alerts: { connection: 'online' } }],
    chimes: [{ id: 333, description: 'Hall chime' }],
    base_stations: [{ id: 444 }],
  };
  events: Record<string, unknown[]> = {};
  snapshotStatus = 200;
  snapshotHang = false;
  refreshStatus: number | null = null;
  refreshCalls = 0;
  refreshGate: Promise<void> | null = null;
  eventsStatus = 200;
  password = 'pw';
  sessionCalls = 0;

  private mint(): { access_token: string; refresh_token: string; expires_in: number } {
    const n = ++this.counter;
    const access = `access-${n}`;
    const refresh = `refresh-${n}`;
    this.accessTokens.add(access);
    this.refreshTokens.add(refresh);
    return { access_token: access, refresh_token: refresh, expires_in: 3600 };
  }
  expireAccess() {
    this.accessTokens.clear();
  }
  latestRefresh() {
    return [...this.refreshTokens].at(-1) ?? '';
  }

  fetch: HttpFetch = async (url, init) => {
    const h = init.headers ?? {};
    const body = init.body ? (JSON.parse(init.body) as Record<string, unknown>) : null;
    this.seen.push({ method: init.method, url, hardwareId: h.hardware_id, auth: h.Authorization, code: h['2fa-code'], body, userAgent: h['User-Agent'] });
    const hid = h.hardware_id;
    if (url.startsWith('https://oauth.ring.com/oauth/token')) {
      if (body?.client_id !== CLIENT_ID) return jsonResponse({ error: 'invalid_client' }, 401);
      if (body.grant_type === 'password') {
        if (body.password !== this.password) return jsonResponse({ error: 'access_denied' }, 401);
        if (!h['2fa-code']) return jsonResponse({ tsv_state: 'sms', phone: '+1xxxxxxxx56', error: 'verification code needed' }, 412);
        if (h['2fa-code'] !== CODE) return jsonResponse({ error: 'Verification Code is invalid or expired' }, 400);
        return jsonResponse(this.mint());
      }
      if (body.grant_type === 'refresh_token') {
        this.refreshCalls++;
        if (this.refreshGate) await this.refreshGate;
        if (this.refreshStatus) return jsonResponse({ error: 'x' }, this.refreshStatus);
        const old = String(body.refresh_token);
        if (!this.refreshTokens.has(old)) return jsonResponse({ error: 'invalid_grant' }, 400);
        this.refreshTokens.delete(old); // rotates: the one it replaces stops working at once
        return jsonResponse(this.mint());
      }
    }
    const token = (h.Authorization ?? '').replace('Bearer ', '');
    if (!this.accessTokens.has(token)) return jsonResponse({ error: 'unauthorized' }, 401);
    if (url === 'https://api.ring.com/clients_api/session') {
      this.sessionCalls++;
      this.sessionFor.add(hid ?? '');
      return jsonResponse({ profile: {} });
    }
    if (this.needSession && !this.sessionFor.has(hid ?? '')) return jsonResponse({ error: `session for ${hid} not found` }, 404);
    if (url === 'https://api.ring.com/clients_api/ring_devices') return jsonResponse(this.devices);
    const ev = /clients_api\/locations\/([^/]+)\/devices\/(\d+)\/events\?limit=50$/.exec(url);
    if (ev) return this.eventsStatus === 200 ? jsonResponse({ events: this.events[ev[2]] ?? [] }) : jsonResponse({}, this.eventsStatus);
    const snap = /app-snaps\.ring\.com\/snapshots\/next\/(\d+)\?after-ms=\d+&extras=force$/.exec(url);
    if (snap) {
      if (this.snapshotHang) return new Promise<never>(() => {});
      return this.snapshotStatus === 200 ? streamResponse([JPEG]) : jsonResponse({}, this.snapshotStatus);
    }
    return jsonResponse({}, 404);
  };
}

function setup(over: { saved?: { refreshToken: string; hardwareId: string } } = {}) {
  const ring = new PretendRing();
  const secrets = memorySecrets();
  let n = 0;
  const clock = { t: NOW };
  const service = new RingService({
    secrets,
    api: new RingApi({ fetch: secureFetch(ring.fetch, { allowHosts: ['ring.com'] }) }),
    now: () => clock.t,
    uuid: () => `uuid-${++n}`,
  });
  return { ring, secrets, service, clock, uuids: () => n };
}

async function signedIn() {
  const s = setup();
  const first = await s.service.signIn({ email: 'me@example.com', password: 'pw' });
  expect(first.kind).toBe('needs_code');
  expect(await s.service.signIn({ email: 'me@example.com', password: 'pw', code: CODE })).toEqual({ kind: 'ok' });
  return s;
}

describe('sign-in with 2FA', () => {
  it('asks for a code (412), then signs in with it, and keeps only the token and hardware id', async () => {
    const { ring, secrets, service } = setup();
    const first = await service.signIn({ email: 'me@example.com', password: 'pw' });
    expect(first).toMatchObject({ kind: 'needs_code', form: { fields: [{ key: 'code', keyboard: 'number' }] } });
    expect(JSON.stringify(first)).toContain('+1xxxxxxxx56');
    expect(service.state.get().kind).toBe('signed_out');
    expect(await service.signIn({ code: CODE })).toEqual({ kind: 'ok' }); // the first answers were kept in memory
    expect(service.state.get()).toEqual({ kind: 'connected' });
    const saved = JSON.parse(secrets.dump()['home.ring']);
    expect(saved).toEqual({ refreshToken: ring.latestRefresh(), hardwareId: 'uuid-1' });
    expect(secrets.dump()['home.ring']).not.toContain('pw');
    expect(secrets.dump()['home.ring']).not.toContain('me@example.com');
  });

  it('sends the same hardware id on every request of a sign-in, and the library headers', async () => {
    const { ring } = await signedIn();
    const ids = new Set(ring.seen.map((r) => r.hardwareId));
    expect(ids).toEqual(new Set(['uuid-1']));
    expect(ring.seen.every((r) => r.userAgent === USER_AGENT)).toBe(true);
    expect(ring.seen[0].body).toMatchObject({ grant_type: 'password', scope: 'client', client_id: CLIENT_ID });
    expect(ring.seen[0].code).toBe('');
    expect(ring.seen[1].code).toBe(CODE);
  });

  it("says the code didn't work (400 Verification Code…) and the same phone id is used for the next try", async () => {
    const { ring, service, uuids } = setup();
    await service.signIn({ email: 'me@example.com', password: 'pw' });
    const wrong = await service.signIn({ code: '000000' });
    expect(wrong).toEqual({ kind: 'failed', message: expect.stringContaining("That code didn't work") });
    expect(await service.signIn({ code: CODE })).toEqual({ kind: 'ok' });
    expect(new Set(ring.seen.map((r) => r.hardwareId))).toEqual(new Set(['uuid-1']));
    expect(uuids()).toBe(1);
  });

  it('says the password is wrong, and forgets the answers', async () => {
    const { service } = setup();
    const r = await service.signIn({ email: 'me@example.com', password: 'nope' });
    expect(r).toEqual({ kind: 'failed', message: "Ring says the email or password isn't right." });
    expect((await service.signIn({ code: CODE })).kind).toBe('failed'); // nothing was kept
    expect(service.state.get().kind).toBe('signed_out');
  });

  it("says Ring can't be reached without signing anything out", async () => {
    const s = await signedIn();
    const down = new RingService({ secrets: s.secrets, api: new RingApi({ fetch: async () => { throw new Error('offline'); } }), uuid: () => 'x' });
    await down.restore();
    expect(down.state.get()).toEqual({ kind: 'connected' });
    const r = await down.signIn({ email: 'a@b.c', password: 'pw' });
    expect(r).toEqual({ kind: 'failed', message: "Ring can't be reached right now." });
    expect(down.state.get()).toEqual({ kind: 'connected' });
  });

  it('restores from the Keychain; a missing or damaged token means signed out; disconnect forgets it', async () => {
    const s = await signedIn();
    const again = new RingService({ secrets: s.secrets, api: new RingApi({ fetch: s.ring.fetch }), uuid: () => 'x' });
    await again.restore();
    expect(again.state.get()).toEqual({ kind: 'connected' });
    const bad = new RingService({ secrets: memorySecrets({ 'home.ring': '{nope' }), api: new RingApi({ fetch: s.ring.fetch }), uuid: () => 'x' });
    await bad.restore();
    expect(bad.state.get().kind).toBe('signed_out');
    await s.service.disconnect();
    expect(s.secrets.dump()['home.ring']).toBeUndefined();
    expect(s.service.state.get().kind).toBe('signed_out');
  });
});

describe('the rotating refresh token', () => {
  it('saves the rotated token BEFORE the access token it came with is used', async () => {
    const s = await signedIn();
    s.ring.expireAccess();
    s.clock.t += 2 * 3_600_000;
    let savedBeforeUse = false;
    const real = s.ring.fetch;
    const wrapped: HttpFetch = async (url, init) => {
      if (url.endsWith('/ring_devices')) {
        const token = JSON.parse(s.secrets.dump()['home.ring']).refreshToken;
        savedBeforeUse = token === s.ring.latestRefresh();
      }
      return real(url, init);
    };
    const svc = new RingService({ secrets: s.secrets, api: new RingApi({ fetch: wrapped }), now: () => s.clock.t, uuid: () => 'x' });
    await svc.restore();
    const devices = await svc.backend().devices();
    expect(devices).toHaveLength(2);
    expect(savedBeforeUse).toBe(true);
    expect(s.ring.refreshCalls).toBe(1);
  });

  it('six parallel calls spend the old token once', async () => {
    const s = await signedIn();
    s.ring.expireAccess();
    s.clock.t += 2 * 3_600_000;
    let release!: () => void;
    s.ring.refreshGate = new Promise<void>((r) => (release = r));
    const calls = Array.from({ length: 6 }, () => s.service.backend().devices());
    await Promise.resolve();
    release();
    const all = await Promise.all(calls);
    expect(all.every((d) => d.length === 2)).toBe(true);
    expect(s.ring.refreshCalls).toBe(1);
    expect(s.ring.sessionCalls).toBeGreaterThanOrEqual(1);
  });

  it('a refresh Ring refuses turns the service to needs_sign_in once, without a retry loop', async () => {
    const s = await signedIn();
    s.ring.expireAccess();
    s.clock.t += 2 * 3_600_000;
    s.ring.refreshStatus = 400;
    await expect(s.service.backend().devices()).rejects.toThrow('sign in again');
    expect(s.service.state.get()).toMatchObject({ kind: 'needs_sign_in' });
    expect(s.ring.refreshCalls).toBe(1);
  });

  it('an unreachable Ring is not a sign-out', async () => {
    const s = await signedIn();
    s.ring.expireAccess();
    s.clock.t += 2 * 3_600_000;
    const real = s.ring.fetch;
    let down = true;
    const flaky: HttpFetch = async (u, i) => {
      if (down) throw new Error('offline');
      return real(u, i);
    };
    const svc = new RingService({ secrets: s.secrets, api: new RingApi({ fetch: flaky }), now: () => s.clock.t, uuid: () => 'x' });
    await svc.restore();
    await expect(svc.backend().devices()).rejects.toThrow("can't be reached");
    expect(svc.state.get()).toEqual({ kind: 'connected' });
    down = false;
    expect(await svc.backend().devices()).toHaveLength(2); // the saved token still works
  });

  it('a 401 on a call refreshes once and asks again; a forgotten session is started again', async () => {
    const s = await signedIn();
    expect((await s.service.backend().devices()).length).toBe(2);
    s.ring.expireAccess(); // the cached access token is now refused
    expect((await s.service.backend().devices()).length).toBe(2);
    expect(s.ring.refreshCalls).toBe(1);
    s.ring.sessionFor.clear();
    expect((await s.service.backend().devices()).length).toBe(2);
  });
});

describe('devices', () => {
  it('lists doorbells and cameras with battery, ignoring chimes and base stations', async () => {
    const s = await signedIn();
    const list = await s.service.backend().devices();
    expect(list.map((d) => [d.id, d.name, d.type, d.capabilities, d.state.battery, d.online])).toEqual([
      ['ring:222', 'Driveway', 'camera', ['snapshot', 'events'], 40, true],
      ['ring:111', 'Front door', 'doorbell', ['snapshot', 'events'], 84, true],
    ]);
  });

  it('parses the battery like the library, an offline camera, motion detection and bad input', () => {
    expect(batteryOf({ battery_life: 'x' })).toBeNull();
    expect(batteryOf({ battery_life: 150 })).toBe(100);
    expect(batteryOf({ battery_life: 80, health: { battery_present: false, battery_percentage: 0 } })).toBeNull();
    const cams = parseCameras({
      doorbots: [{ id: 1, description: 'A', alerts: { connection: 'offline' }, settings: { motion_detection_enabled: false } }],
      stickup_cams: [{ id: 1, description: 'dup' }, { id: 'x' }, 7, { description: 'no id' }],
      authorized_doorbots: [{ id: 5, description: 'Shared' }],
      other: [{ id: 9, kind: 'onvif_camera', description: 'Onvif' }, { id: 10, kind: 'sensor' }],
    });
    expect(cams?.map((c) => [c.id, c.offline, c.motionDetection, c.doorbell])).toEqual([
      ['1', true, false, true],
      ['x', false, true, false],
      ['5', false, true, true],
      ['9', false, true, false],
    ]);
    expect(parseCameras('nope')).toBeNull();
  });

  it('a Ring failure does not empty the home: the other services still answer', async () => {
    const s = await signedIn();
    s.ring.devices = 'not a list';
    const other: HomeBackend = { id: 'demo', name: 'Demo', devices: async () => [{ id: 'd1', name: 'Lamp', room: 'Den', type: 'light', capabilities: ['on_off'], backend: 'demo', online: true, state: { on: true }, stateAt: 0 }], state: async () => ({}), command: async () => {} };
    const home = new Home({ store: memoryStore() as never, setTimer: () => 0 });
    home.setBackends([other, s.service.backend()]);
    const snap = await home.refresh();
    expect(snap.devices.map((d) => d.name)).toEqual(['Lamp']);
    expect(snap.problems[0]).toContain("Ring: Ring's answer for the device list wasn't what Edith expected");
  });

  it('cannot be controlled', async () => {
    const s = await signedIn();
    await expect(s.service.backend().command('ring:111', { type: 'turn_on' })).rejects.toThrow('pictures and events only');
  });
});

describe('snapshots', () => {
  it('returns a forced new picture as base64, asked with extras=force', async () => {
    const s = await signedIn();
    const shot = await s.service.backend().snapshot!('ring:111');
    expect(shot).toEqual({ jpegBase64: bytesToBase64(JPEG), takenAt: NOW });
    const asked = s.ring.seen.at(-1)!;
    expect(asked.url).toMatch(/^https:\/\/app-snaps\.ring\.com\/snapshots\/next\/111\?after-ms=\d+&extras=force$/);
    expect(asked.hardwareId).toBe('uuid-1');
  });

  it('says why when the camera is offline or motion detection is off', async () => {
    const s = await signedIn();
    s.ring.devices = {
      doorbots: [
        { id: 1, description: 'Front door', location_id: 'l', alerts: { connection: 'offline' } },
        { id: 2, description: 'Garage', location_id: 'l', settings: { motion_detection_enabled: false } },
      ],
    };
    await expect(s.service.backend().snapshot!('ring:1')).rejects.toThrow('Front door is offline.');
    await expect(s.service.backend().snapshot!('ring:2')).rejects.toThrow('Motion detection is off for Garage');
  });

  it('says what Ring answered when it gives no picture', async () => {
    const s = await signedIn();
    s.ring.snapshotStatus = 500;
    await expect(s.service.backend().snapshot!('ring:111')).rejects.toThrow('Ring answered HTTP 500 for the picture');
  });

  it("gives up after 35 seconds with the camera's name", async () => {
    jest.useFakeTimers();
    try {
      const s = await signedIn();
      s.ring.snapshotHang = true;
      const home = new Home({ store: memoryStore() as never, setTimer: () => 0 });
      home.setBackends([s.service.backend()]);
      await home.refresh();
      expect(s.service.backend().snapshotTimeoutMs).toBe(35_000);
      const device = home.cached().find((d) => d.name === 'Front door')!;
      const pending = home.snapshot(device).then(
        () => 'resolved',
        (e: Error) => e.message,
      );
      await jest.advanceTimersByTimeAsync(34_000);
      await jest.advanceTimersByTimeAsync(1_500);
      expect(await pending).toBe("Front door didn't answer in 35 seconds.");
    } finally {
      jest.useRealTimers();
    }
  });
});

describe('events and recent_events', () => {
  const iso = (hoursAgo: number) => new Date(NOW - hoursAgo * 3_600_000).toISOString();
  const ev = (id: number, kind: string, hoursAgo: number, cv?: unknown) => ({ id, kind, created_at: iso(hoursAgo), ...(cv ? { cv_properties: cv } : {}) });

  it('maps kinds, drops others and old ones, sorts newest first, and finds people', () => {
    const cam = parseCameras({ doorbots: [{ id: 111, description: 'Front door', location_id: 'l' }] })![0];
    const out = parseEvents(
      { events: [ev(1, 'motion', 5), ev(2, 'ding', 1), ev(3, 'on_demand', 2), ev(4, 'other', 1), ev(5, 'motion', 3, { person_detected: true }), ev(6, 'motion', 4, { detection_type: 'human' }), ev(7, 'ding', 30), { id: 8, kind: 'ding', created_at: 'garbage' }, 'x'] },
      cam,
      NOW - 24 * 3_600_000,
    )!;
    expect(out.map((e) => [e.id, e.kind])).toEqual([['2', 'ring'], ['3', 'live'], ['5', 'person'], ['6', 'person'], ['1', 'motion']]);
    expect(out[0]).toMatchObject({ deviceId: 'ring:111', deviceName: 'Front door' });
    expect(parseEvents([], cam, 0)).toBeNull();
  });

  async function homeAndTool() {
    const s = await signedIn();
    s.ring.events = {
      '111': [ev(1, 'ding', 2), ev(2, 'motion', 3), ev(3, 'motion', 50)],
      '222': [ev(4, 'motion', 1, { person_detected: true }), ev(5, 'ding', 70)],
    };
    const home = new Home({ store: memoryStore() as never, setTimer: () => 0 });
    home.setBackends([s.service.backend()]);
    await home.refresh();
    return { s, home, tool: recentEventsTool(home, () => NOW) };
  }
  const run = (tool: Tool, args: Record<string, string>) => tool.execute(new ToolArgs(args, tool.spec), { clock: new FakeClock() });

  it('answers newest first with day, time, camera and kind, limited to the hours asked', async () => {
    const { tool } = await homeAndTool();
    expect(tool.enabled?.()).toBe(true);
    const r = await run(tool, {});
    expect(r.ok).toBe(true);
    const lines = r.text.split('\n');
    expect(lines).toEqual([
      `${eventTime(NOW - 3_600_000)} — Driveway — Person`,
      `${eventTime(NOW - 2 * 3_600_000)} — Front door — Ring`,
      `${eventTime(NOW - 3 * 3_600_000)} — Front door — Motion`,
    ]);
    const long = await run(tool, { hours: '72' });
    expect(long.text.split('\n')).toHaveLength(5);
    const one = await run(tool, { hours: '1' });
    expect(one.text.split('\n')).toHaveLength(1); // 1 hour: the event at exactly 1 h counts
  });

  it('asks one camera, says nothing happened, and names the cameras for a wrong name', async () => {
    const { tool, s } = await homeAndTool();
    const one = await run(tool, { device: 'driveway' });
    expect(one.text).toBe(`${eventTime(NOW - 3_600_000)} — Driveway — Person`);
    s.ring.events = {};
    expect((await run(tool, { hours: '24' })).text).toBe('No events in the last 24 hours.');
    expect((await run(tool, { hours: '1' })).text).toBe('No events in the last hour.');
    const bad = await run(tool, { device: 'garage' });
    expect(bad.ok).toBe(false);
    expect(bad.text).toContain('Cameras: Driveway, Front door');
  });

  it('keeps hidden cameras out, caps the list at 20, and reports a service that failed', async () => {
    const { tool, home, s } = await homeAndTool();
    home.setHidden('ring:222', true);
    expect((await run(tool, {})).text).not.toContain('Driveway');
    home.setHidden('ring:222', false);
    s.ring.events = { '111': Array.from({ length: 30 }, (_, i) => ev(i + 1, 'motion', 1 + i / 100)) };
    expect((await run(tool, {})).text.split('\n')).toHaveLength(20);
    s.ring.eventsStatus = 500;
    const failed = await run(tool, {});
    expect(failed.text).toContain("Didn't answer: Ring: Ring answered HTTP 500 for the events");
  });

  it('is off when no camera can report events', async () => {
    const home = new Home({ store: memoryStore() as never, setTimer: () => 0 });
    expect(recentEventsTool(home).enabled?.()).toBe(false);
  });
});

describe('secure client', () => {
  it('refuses anything outside ring.com and any plain address', async () => {
    const ring = new PretendRing();
    const api = new RingApi({ fetch: secureFetch(ring.fetch, { allowHosts: ['ring.com'] }), endpoints: { oauth: 'http://oauth.ring.com', clients: 'https://example.com/x', snapshots: 'https://app-snaps.ring.com' } });
    expect((await api.signIn('a@b.c', 'pw', null, 'h')).kind).toBe('failed');
    expect((await api.cameras('t', 'h')).kind).toBe('failed');
    expect(ring.seen).toHaveLength(0);
  });
});
