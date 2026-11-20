import { createHash } from 'crypto';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { md5, utf8Bytes } from '../core/md5';
import { memorySecrets } from '../core/secrets';
import { secureFetch } from '../core/secureFetch';
import { confirmationFor, stateWords } from '../home/actions';
import { Home } from '../home/home';
import type { HomeDevice } from '../home/model';
import type { HttpFetch, HttpResponse } from '../ollama/client';
import { memoryStore } from '../settings/settings';
import { ServiceRow } from '../ui/HomeSettings';
import { themes } from '../ui/theme';
import { MerossApi, UNTRUSTED } from './api';
import { LanError, MerossLan, scanForDevices, subnetHosts } from './lan';
import { GARAGE_STATE, SECRET, SYSTEM_ALL, apiBase, deviceMessage, encodeParams, envelope, isGarageOpener, isTrustedDomain, nonce, requestSign, verify } from './protocol';
import { MerossService } from './service';

jest.mock('../core/services', () => ({
  useServices: () => ({}),
  useStore: (store: { get: () => unknown }) => store.get(),
}));

jest.mock('react-native-webview', () => ({ WebView: () => null }));

const nodeMd5 = (s: string) => createHash('md5').update(s, 'utf8').digest('hex');

describe('md5 and encoding', () => {
  it('matches RFC 1321', () => {
    const vectors: [string, string][] = [
      ['', 'd41d8cd98f00b204e9800998ecf8427e'],
      ['a', '0cc175b9c0f1b6a831c399e269772661'],
      ['abc', '900150983cd24fb0d6963f7d28e17f72'],
      ['message digest', 'f96b697d7cb7938d525a2f31aaf161d0'],
      ['abcdefghijklmnopqrstuvwxyz', 'c3fcd3d76192e4007dfb496cca67e13b'],
      ['12345678901234567890123456789012345678901234567890123456789012345678901234567890', '57edf4a22be3c955ac49da2e2107b67a'],
    ];
    for (const [text, hex] of vectors) expect(md5(text)).toBe(hex);
  });
  it('agrees with the platform on awkward text (UTF-8, long, block-boundary lengths)', () => {
    for (const s of ['héllo wörld', 'emoji 😀 text', 'x'.repeat(55), 'x'.repeat(56), 'x'.repeat(63), 'x'.repeat(64), 'x'.repeat(1000), '日本語']) expect(md5(s)).toBe(nodeMd5(s));
  });
  it('encodes UTF-8 by hand', () => {
    expect(Array.from(utf8Bytes('é😀'))).toEqual([0xc3, 0xa9, 0xf0, 0x9f, 0x98, 0x80]);
  });
});

describe('the cloud protocol', () => {
  it('signs a request as md5(SECRET + timestamp + nonce + base64 params)', () => {
    const params = { email: 'me@example.com', password: nodeMd5('pw') };
    const encoded = encodeParams(params);
    expect(encoded).toBe(Buffer.from(JSON.stringify(params), 'utf8').toString('base64'));
    expect(requestSign(1700000000000, 'ABCDEFGHIJKLMNOP', encoded)).toBe(nodeMd5(`${SECRET}1700000000000ABCDEFGHIJKLMNOP${encoded}`));
    expect(envelope(params, 1700000000000, 'ABCDEFGHIJKLMNOP')).toEqual({ params: encoded, sign: requestSign(1700000000000, 'ABCDEFGHIJKLMNOP', encoded), timestamp: 1700000000000, nonce: 'ABCDEFGHIJKLMNOP' });
  });
  it('makes a 16-character nonce from A–Z and 0–9', () => {
    expect(nonce()).toMatch(/^[A-Z0-9]{16}$/);
    let i = 0;
    expect(nonce(() => (i++ % 36) / 36)).toBe('ABCDEFGHIJKLMNOP');
  });
  it('signs a device message with md5(messageId + key + timestamp) and only believes the same', () => {
    const { text, messageId } = deviceMessage('SET', GARAGE_STATE, { state: { channel: 0, open: 1 } }, 'thekey', 'http://10.0.0.5/config', 1700000000, () => 0.5, 'UUID1');
    const m = JSON.parse(text);
    expect(m.header).toMatchObject({ method: 'SET', namespace: GARAGE_STATE, payloadVersion: 1, timestamp: 1700000000, from: 'http://10.0.0.5/config', uuid: 'UUID1', messageId });
    expect(m.header.sign).toBe(nodeMd5(`${messageId}thekey1700000000`));
    expect(verify(m.header, 'thekey')).toBe(true);
    expect(verify(m.header, 'otherkey')).toBe(false);
    expect(verify({ ...m.header, timestamp: 1 }, 'thekey')).toBe(false);
    expect(verify(null, 'k')).toBe(false);
    expect(verify({ messageId: 'x' }, 'k')).toBe(false);
  });
  it('trusts only https addresses under meross.com', () => {
    for (const ok of ['iotx-us.meross.com', 'https://iotx-eu.meross.com', 'mqtt-us.meross.com:443', 'https://iot.meross.com/']) expect(isTrustedDomain(ok)).toBe(true);
    for (const bad of ['http://iot.meross.com', 'meross.com.evil.org', 'evil.org', 'https://evil.org/iot.meross.com', 'iot.meross.com.evil.org:443', '', 'ftp://iot.meross.com']) expect(isTrustedDomain(bad)).toBe(false);
    expect(apiBase('iotx-eu.meross.com')).toBe('https://iotx-eu.meross.com');
    expect(apiBase('http://x.meross.com/')).toBe('http://x.meross.com');
    expect(isGarageOpener('msg100')).toBe(true);
    expect(isGarageOpener('mss310')).toBe(false);
  });
});

// --- a pretend Meross cloud that checks every signature ---------------------------------------------------------------------------

interface Cloud {
  fetch: HttpFetch;
  calls: { url: string; auth: string; params: Record<string, unknown> }[];
  /** What signIn answers, per call (the last one repeats). */
  signIn: Record<string, unknown>[];
  devices: unknown;
  devListStatus: number;
  down: boolean;
}
const TOKEN = 'TOKEN123';
const KEY = 'userkey';
function pretendCloud(): Cloud {
  const c: Cloud = {
    calls: [],
    down: false,
    devListStatus: 0,
    devices: [
      { uuid: 'DOOR1', devName: 'Garage Door', deviceType: 'msg100', onlineStatus: 1 },
      { uuid: 'PLUG1', devName: 'Lamp plug', deviceType: 'mss310', onlineStatus: 1 },
    ],
    signIn: [{ apiStatus: 0, data: { token: TOKEN, key: KEY, userid: '42', email: 'me@example.com', domain: 'iotx-us.meross.com', mqttDomain: 'mqtt-us.meross.com' } }],
    fetch: async (url, init) => {
      if (c.down) throw new TypeError('Network request failed');
      const env = JSON.parse(init.body!) as { params: string; sign: string; timestamp: number; nonce: string };
      // Every request must carry the right signature (a pretend Meross that checks, like the real one).
      expect(env.sign).toBe(nodeMd5(`${SECRET}${env.timestamp}${env.nonce}${env.params}`));
      const params = JSON.parse(Buffer.from(env.params, 'base64').toString('utf8')) as Record<string, unknown>;
      c.calls.push({ url, auth: init.headers?.Authorization ?? '', params });
      const reply = (body: unknown): HttpResponse => ({ ok: true, status: 200, text: async () => JSON.stringify(body), body: null });
      if (url.endsWith('/v1/Auth/signIn')) {
        const n = c.calls.filter((x) => x.url.endsWith('/v1/Auth/signIn')).length;
        return reply(c.signIn[Math.min(n - 1, c.signIn.length - 1)]);
      }
      if (url.endsWith('/v1/Device/devList')) return reply({ apiStatus: c.devListStatus, data: c.devices });
      if (url.endsWith('/v1/Profile/logout')) return reply({ apiStatus: 0 });
      return { ok: false, status: 404, text: async () => '', body: null };
    },
  };
  return c;
}
const apiFor = (cloud: Cloud) => new MerossApi({ fetch: cloud.fetch, now: () => 1_700_000_000_000 });
const signInCalls = (c: Cloud) => c.calls.filter((x) => x.url.endsWith('/v1/Auth/signIn'));

describe('the cloud API', () => {
  it('signs in with the md5 of the password, on the iPhone, and returns the keys', async () => {
    const cloud = pretendCloud();
    const r = await apiFor(cloud).login('me@example.com', 'hunter2', 'INSTALL1');
    expect(r).toEqual({ kind: 'ok', creds: { token: TOKEN, key: KEY, userId: '42', email: 'me@example.com', domain: 'iotx-us.meross.com', mqttDomain: 'mqtt-us.meross.com', installId: 'INSTALL1' } });
    const p = signInCalls(cloud)[0].params;
    expect(p).toMatchObject({ email: 'me@example.com', password: nodeMd5('hunter2'), mobileInfo: { uuid: 'INSTALL1', mobileOs: 'iOS' } });
    expect(JSON.stringify(cloud.calls)).not.toContain('hunter2'); // the password is never sent
    expect(signInCalls(cloud)[0].auth).toBe('Basic');
  });
  it('follows one region redirect to a Meross address, and no further', async () => {
    const cloud = pretendCloud();
    cloud.signIn = [{ apiStatus: 1030, data: { domain: 'iotx-eu.meross.com' } }, cloud.signIn[0]];
    expect((await apiFor(cloud).login('a@b.c', 'pw', 'I')).kind).toBe('ok');
    expect(signInCalls(cloud).map((c) => c.url)).toEqual(['https://iotx-us.meross.com/v1/Auth/signIn', 'https://iotx-eu.meross.com/v1/Auth/signIn']);
    const twice = pretendCloud();
    twice.signIn = [{ apiStatus: 1030, data: { domain: 'iotx-eu.meross.com' } }];
    expect(await apiFor(twice).login('a@b.c', 'pw', 'I')).toEqual({ kind: 'failed', why: 'Meross sent Edith to another region twice' });
  });
  it('stops, and sends nothing, when told to go to an address outside meross.com', async () => {
    for (const evil of ['evil.example.org', 'http://iotx-eu.meross.com', 'iotx-eu.meross.com.evil.org']) {
      const cloud = pretendCloud();
      cloud.signIn = [{ apiStatus: 1030, data: { domain: evil } }];
      expect(await apiFor(cloud).login('a@b.c', 'pw', 'I')).toEqual({ kind: 'failed', why: UNTRUSTED });
      expect(cloud.calls).toHaveLength(1); // the second request was never made
    }
  });
  it('gives the token back and keeps nothing when a sign-in names a foreign API address', async () => {
    const cloud = pretendCloud();
    cloud.signIn = [{ apiStatus: 0, data: { token: TOKEN, key: KEY, userid: '42', domain: 'evil.example.org', mqttDomain: 'mqtt-us.meross.com' } }];
    expect(await apiFor(cloud).login('a@b.c', 'pw', 'I')).toEqual({ kind: 'failed', why: UNTRUSTED });
    expect(cloud.calls.map((c) => c.url)).toEqual(['https://iotx-us.meross.com/v1/Auth/signIn', 'https://iotx-us.meross.com/v1/Profile/logout']);
  });
  it('maps Meross\'s codes: code wanted, wrong code, wrong password, too many sign-ins, anything else', async () => {
    const answer = async (status: number) => {
      const c = pretendCloud();
      c.signIn = [{ apiStatus: status }];
      return (await apiFor(c).login('a@b.c', 'pw', 'I')).kind;
    };
    expect(await answer(1033)).toBe('needs_code');
    expect(await answer(1032)).toBe('wrong_code');
    expect(await answer(1004)).toBe('bad_login');
    expect(await answer(1002)).toBe('bad_login');
    expect(await answer(1301)).toBe('too_many_tokens');
    expect(await answer(5000)).toBe('failed');
  });
  it('sends the code when given one', async () => {
    const cloud = pretendCloud();
    await apiFor(cloud).login('a@b.c', 'pw', 'I', '123456');
    expect(signInCalls(cloud)[0].params.mfaCode).toBe('123456');
  });
  it('lists devices with the token, reports a refused token and failures', async () => {
    const cloud = pretendCloud();
    const creds = { token: TOKEN, key: KEY, userId: '42', email: 'a', domain: 'iotx-us.meross.com', mqttDomain: 'm', installId: 'I' };
    const ok = await apiFor(cloud).devices(creds);
    expect(ok).toEqual({ kind: 'ok', value: [{ uuid: 'DOOR1', name: 'Garage Door', type: 'msg100', online: true }, { uuid: 'PLUG1', name: 'Lamp plug', type: 'mss310', online: true }] });
    expect(cloud.calls.at(-1)?.auth).toBe(`Basic ${TOKEN}`);
    for (const code of [1019, 1022, 1200]) {
      cloud.devListStatus = code;
      expect(await apiFor(cloud).devices(creds)).toEqual({ kind: 'rejected' });
    }
    cloud.devListStatus = 9999;
    expect((await apiFor(cloud).devices(creds)).kind).toBe('failed');
    cloud.down = true;
    expect(await apiFor(cloud).devices(creds)).toEqual({ kind: 'failed', why: "Meross can't be reached right now" });
  });
  it('refuses a redirect off meross.com through secureFetch, and says so in words', async () => {
    const base: HttpFetch = async () => ({ ok: false, status: 302, headers: { get: (n) => (n === 'location' ? 'https://evil.example.org/x' : null) }, text: async () => '', body: null });
    const api = new MerossApi({ fetch: secureFetch(base, { allowHosts: ['meross.com'] }), now: () => 1 });
    expect(await api.login('a@b.c', 'pw', 'I')).toEqual({ kind: 'failed', why: UNTRUSTED });
  });
});

// --- a pretend door on the home network ---------------------------------------------------------------------------------------------

interface Door {
  uuid: string;
  key: string;
  open: boolean;
  lmTime?: number;
  /** Fail this many requests first (a cold connection). */
  coldFailures: number;
  sets: unknown[];
  badSign?: boolean;
}
function pretendWifi(doors: Record<string, Door>) {
  const calls: string[] = [];
  const fetch: HttpFetch = async (url, init) => {
    const ip = /^http:\/\/([^/]+)\/config$/.exec(url)?.[1] ?? '';
    calls.push(ip);
    const d = doors[ip];
    if (!d) throw new TypeError('Network request failed');
    if (d.coldFailures > 0) {
      d.coldFailures--;
      throw new TypeError('Network request failed');
    }
    const msg = JSON.parse(init.body!) as { header: { messageId: string; namespace: string; method: string; sign: string; timestamp: number }; payload: Record<string, unknown> };
    expect(verify(msg.header, d.key)).toBe(true); // the phone's request is signed with the key
    const answer = (namespace: string, payload: unknown) => {
      const ts = msg.header.timestamp;
      const messageId = msg.header.messageId;
      const sign = d.badSign ? 'f'.repeat(32) : nodeMd5(`${messageId}${d.key}${ts}`);
      return { ok: true, status: 200, text: async () => JSON.stringify({ header: { messageId, namespace, method: 'GETACK', payloadVersion: 1, sign, timestamp: ts }, payload }), body: null } as HttpResponse;
    };
    if (msg.header.namespace === SYSTEM_ALL) {
      return answer(SYSTEM_ALL, { all: { system: { hardware: { uuid: d.uuid } }, digest: { garageDoor: [{ channel: 0, open: d.open ? 1 : 0, lmTime: d.lmTime ?? 0 }] } } });
    }
    if (msg.header.namespace === GARAGE_STATE) {
      d.sets.push(msg.payload);
      d.open = (msg.payload.state as { open: number }).open === 1;
      return answer(GARAGE_STATE, {});
    }
    return { ok: false, status: 404, text: async () => '', body: null };
  };
  return { fetch, calls };
}
const newDoor = (over: Partial<Door> = {}): Door => ({ uuid: 'DOOR1', key: KEY, open: false, coldFailures: 0, sets: [], ...over });

describe('talking to a door on the Wi-Fi', () => {
  it('reads the door and moves it, with signed messages', async () => {
    const door = newDoor({ lmTime: 1_700_000_000 });
    const lan = new MerossLan(pretendWifi({ '192.168.4.20': door }).fetch, () => 1_700_000_100_000);
    expect(await lan.read('192.168.4.20', KEY)).toEqual({ uuid: 'DOOR1', doors: [{ channel: 0, open: false, lastMovedSec: 1_700_000_000 }] });
    await lan.setDoor('192.168.4.20', KEY, 'DOOR1', 0, true);
    expect(door.sets).toEqual([{ state: { channel: 0, open: 1, uuid: 'DOOR1' } }]);
  });
  it('never believes an answer with a wrong signature, and says a door that is not there is not reachable', async () => {
    const lan = new MerossLan(pretendWifi({ '10.0.0.5': newDoor({ badSign: true }) }).fetch, () => 1);
    await expect(lan.read('10.0.0.5', KEY)).rejects.toMatchObject({ kind: 'untrusted' });
    await expect(lan.read('10.0.0.6', KEY)).rejects.toMatchObject({ kind: 'unreachable', message: 'Not reachable. It works on your home Wi-Fi.' });
    await expect(lan.read('10.0.0.5', 'wrongkey')).rejects.toBeInstanceOf(LanError);
  });
  it('lists the /24 without the phone, and scans 20 at a time until it has what it wants', async () => {
    const hosts = subnetHosts('192.168.4.7');
    expect(hosts).toHaveLength(253);
    expect(hosts).not.toContain('192.168.4.7');
    expect(hosts[0]).toBe('192.168.4.1');
    expect(subnetHosts(null)).toEqual([]);
    expect(subnetHosts('not an ip')).toEqual([]);
    expect(subnetHosts('300.1.1.1')).toEqual([]);
    let live = 0;
    let peak = 0;
    const found = await scanForDevices('192.168.4.7', ['DOOR1'], async (ip) => {
      live++;
      peak = Math.max(peak, live);
      await new Promise((r) => setTimeout(r, 0));
      live--;
      return ip === '192.168.4.100' ? 'DOOR1' : ip === '192.168.4.101' ? 'OTHER' : null;
    });
    expect(found).toEqual(new Map([['DOOR1', '192.168.4.100']]));
    expect(peak).toBeLessThanOrEqual(20);
  });
});

// --- the service ---------------------------------------------------------------------------------------------------------------------

async function setup(opts: { doors?: Record<string, Door>; phoneIp?: string | null } = {}) {
  const cloud = pretendCloud();
  const wifi = pretendWifi(opts.doors ?? { '192.168.4.20': newDoor({ lmTime: 1_699_990_000 }) });
  const secrets = memorySecrets();
  const store = memoryStore();
  let t = 1_700_000_000_000;
  const mk = () =>
    new MerossService({
      secrets,
      store,
      cloudFetch: cloud.fetch,
      lanFetch: wifi.fetch,
      now: () => t,
      phoneIp: async () => (opts.phoneIp === undefined ? '192.168.4.7' : opts.phoneIp),
      uuid: () => 'INSTALL-UUID',
      random: () => 0.1,
    });
  const service = mk();
  const home = new Home({ store: memoryStore() as never, now: () => t, setTimer: () => 0 });
  home.registerService(service);
  return { cloud, wifi, secrets, store, service, home, mk, advance: (ms: number) => void (t += ms) };
}
const doorOf = (home: Home): HomeDevice => home.cached().find((d) => d.type === 'garage_door')!;

describe('the Meross service', () => {
  it('signs in, keeps only the sign-in (no password), and shows connected', async () => {
    const s = await setup();
    expect(await s.service.signIn({ email: 'me@example.com', password: 'hunter2' })).toEqual({ kind: 'ok' });
    expect(s.service.state.get()).toEqual({ kind: 'connected', detail: 'me@example.com' });
    const saved = s.secrets.dump()['home.meross'];
    expect(JSON.parse(saved)).toMatchObject({ token: TOKEN, key: KEY, userId: '42' });
    expect(saved).not.toContain('hunter2');
    expect(JSON.stringify(s.cloud.calls)).not.toContain('hunter2');
  });
  it('asks for the code and signs in with it', async () => {
    const s = await setup();
    s.cloud.signIn = [{ apiStatus: 1033 }, s.cloud.signIn[0]];
    const first = await s.service.signIn({ email: 'a@b.c', password: 'pw' });
    expect(first).toMatchObject({ kind: 'needs_code', form: { fields: [{ key: 'code' }] } });
    expect(s.service.state.get().kind).toBe('signed_out');
    expect(await s.service.signIn({ email: 'a@b.c', password: 'pw', code: '654321' })).toEqual({ kind: 'ok' });
    expect(signInCalls(s.cloud)[1].params.mfaCode).toBe('654321');
  });
  it('says plainly what went wrong with a sign-in and saves nothing', async () => {
    const s = await setup();
    s.cloud.signIn = [{ apiStatus: 1004 }];
    expect(await s.service.signIn({ email: 'a@b.c', password: 'bad' })).toEqual({ kind: 'failed', message: "Meross didn't accept that email and password." });
    expect(await s.service.signIn({ email: '', password: '' })).toMatchObject({ kind: 'failed' });
    expect(s.secrets.dump()).toEqual({});
  });
  it('returns the old token when signing in again, and on disconnect, and forgets everything', async () => {
    const s = await setup();
    await s.service.signIn({ email: 'a@b.c', password: 'pw' });
    await s.service.signIn({ email: 'a@b.c', password: 'pw' });
    expect(s.cloud.calls.filter((c) => c.url.endsWith('/logout'))).toHaveLength(1);
    await s.home.refresh();
    await s.service.disconnect();
    expect(s.cloud.calls.filter((c) => c.url.endsWith('/logout'))).toHaveLength(2);
    expect(s.secrets.dump()).toEqual({});
    expect(await s.store.getItem('meross_lan_ips')).toBeNull();
    expect(s.service.state.get().kind).toBe('signed_out');
  });
  it('shows the doors with their state and "moved … ago", finding them on the Wi-Fi once', async () => {
    const s = await setup();
    await s.service.signIn({ email: 'a@b.c', password: 'pw' });
    const r = await s.home.refresh();
    expect(r.problems).toEqual([]);
    expect(s.home.cached().map((d) => d.name)).toEqual(['Garage Door']); // the plug isn't a garage opener
    const d = doorOf(s.home);
    expect(d).toMatchObject({ id: 'DOOR1#0', type: 'garage_door', online: true, state: { door: 'closed', changedAt: 1_699_990_000_000 } });
    expect(JSON.parse((await s.store.getItem('meross_lan_ips'))!)).toEqual({ DOOR1: '192.168.4.20' });
    const scanned = s.wifi.calls.length;
    await s.home.refresh();
    expect(s.wifi.calls.length - scanned).toBe(1); // the second read goes straight to the known address
  });
  it('comes back after a restart with the saved sign-in and addresses', async () => {
    const s = await setup();
    await s.service.signIn({ email: 'a@b.c', password: 'pw' });
    await s.home.refresh();
    const again = s.mk();
    await again.restore();
    expect(again.state.get().kind).toBe('connected');
    const home = new Home({ store: memoryStore() as never, now: () => 1_700_000_000_000, setTimer: () => 0 });
    home.registerService(again);
    const before = s.wifi.calls.length;
    await home.refresh();
    expect(s.wifi.calls.length - before).toBe(1);
    expect(doorOf(home).online).toBe(true);
  });
  it('asks a silent door twice before calling it not responding', async () => {
    const door = newDoor();
    const s = await setup({ doors: { '192.168.4.20': door } });
    await s.service.signIn({ email: 'a@b.c', password: 'pw' });
    await s.home.refresh();
    door.coldFailures = 1;
    expect(doorOf((await s.home.refresh(), s.home)).online).toBe(true);
  });
  it('away from home: not live, the last report kept, "works on your home Wi-Fi", and no problem line', async () => {
    const door = newDoor({ open: true });
    const s = await setup({ doors: { '192.168.4.20': door } });
    await s.service.signIn({ email: 'a@b.c', password: 'pw' });
    await s.home.refresh();
    s.advance(60_000);
    door.coldFailures = 99;
    const r = await s.home.refresh();
    expect(r.problems).toEqual([]);
    const d = doorOf(s.home);
    expect(d.online).toBe(false);
    expect(d.state).toMatchObject({ door: 'open', homeOnly: true });
    expect(stateWords(d)).toBe('Not reachable. It works on your home Wi-Fi. Last: Open.');
  });
  it('never scans on its own more than every 5 minutes', async () => {
    const s = await setup({ doors: {}, phoneIp: '192.168.4.7' });
    await s.service.signIn({ email: 'a@b.c', password: 'pw' });
    await s.home.refresh();
    const first = s.wifi.calls.length;
    expect(first).toBeGreaterThan(200);
    await s.home.refresh();
    expect(s.wifi.calls.length).toBe(first);
    s.advance(6 * 60_000);
    await s.home.refresh();
    expect(s.wifi.calls.length).toBeGreaterThan(first + 200);
  });
  it('a refused token asks to sign in again, once', async () => {
    const s = await setup();
    await s.service.signIn({ email: 'a@b.c', password: 'pw' });
    s.cloud.devListStatus = 1019;
    const r = await s.home.refresh();
    expect(r.problems[0]).toContain('sign in again');
    expect(s.service.state.get()).toMatchObject({ kind: 'needs_sign_in' });
  });
  it('the Meross cloud being down is a problem line, with the sign-in kept', async () => {
    const s = await setup();
    await s.service.signIn({ email: 'a@b.c', password: 'pw' });
    s.cloud.down = true;
    expect((await s.home.refresh()).problems[0]).toContain("can't be reached");
    expect(s.service.state.get().kind).toBe('connected');
  });
});

describe('moving a door', () => {
  const connect = async (door: Door) => {
    const s = await setup({ doors: { '192.168.4.20': door } });
    await s.service.signIn({ email: 'a@b.c', password: 'pw' });
    await s.home.refresh();
    return s;
  };
  it('open and close always ask on a card', () => {
    const d = { id: 'DOOR1#0', name: 'Garage Door', room: 'Garage', type: 'garage_door', capabilities: ['open_close'], backend: 'meross', online: true, state: {}, stateAt: 0 } as HomeDevice;
    expect(confirmationFor(d, { type: 'open' })?.title).toBe('Open Garage Door?');
    expect(confirmationFor(d, { type: 'close' })?.title).toBe('Close Garage Door?');
  });
  it('sends the exact signed command, then reports what the door says', async () => {
    const door = newDoor();
    const s = await connect(door);
    const r = await s.home.command(doorOf(s.home), { type: 'open' }, 'chat');
    expect(door.sets).toEqual([{ state: { channel: 0, open: 1, uuid: 'DOOR1' } }]);
    expect(r.ok).toBe(true);
    expect(r.text).toContain('Garage Door: Open');
    expect(s.home.commandLog()[0]).toMatchObject({ action: 'open', ok: true, service: 'meross' });
  });
  it('asking for what is already so sends nothing', async () => {
    const door = newDoor({ open: true });
    const s = await connect(door);
    expect((await s.home.command(doorOf(s.home), { type: 'open' }, 'chat')).ok).toBe(true);
    expect(door.sets).toEqual([]);
    await s.home.command(doorOf(s.home), { type: 'close' }, 'chat');
    expect(door.sets).toHaveLength(1);
  });
  it('away from home it says so and sends nothing', async () => {
    const door = newDoor();
    const s = await connect(door);
    door.coldFailures = 99;
    const r = await s.home.command(doorOf(s.home), { type: 'open' }, 'chat');
    expect(r.ok).toBe(false);
    expect(r.text).toContain('works on your home Wi-Fi');
    expect(door.sets).toEqual([]);
  });
  it('refuses anything but open and close', async () => {
    const s = await connect(newDoor());
    const r = await s.home.command(doorOf(s.home), { type: 'turn_on' }, 'chat');
    expect(r.ok).toBe(false);
  });
});

describe('Settings → Home → Meross: Find on my Wi-Fi', () => {
  it('shows a button for a connected service, runs it, and says what it found', async () => {
    const s = await setup();
    await s.service.signIn({ email: 'a@b.c', password: 'pw' });
    await render(<ServiceRow service={s.service} theme={themes.light} onSignIn={() => {}} />);
    await fireEvent.press(screen.getByLabelText('Find on my Wi-Fi'));
    expect(await screen.findByText('Found the door on your Wi-Fi.')).toBeTruthy();
  });
  it('says when it found none, and the button is absent when signed out', async () => {
    const s = await setup({ doors: {} });
    expect(s.service.actions()).toEqual([]);
    await s.service.signIn({ email: 'a@b.c', password: 'pw' });
    await render(<ServiceRow service={s.service} theme={themes.light} onSignIn={() => {}} />);
    await fireEvent.press(screen.getByLabelText('Find on my Wi-Fi'));
    expect(await screen.findByText('Found 0 of 1 on your Wi-Fi. Are you on your home Wi-Fi?')).toBeTruthy();
  });
});
