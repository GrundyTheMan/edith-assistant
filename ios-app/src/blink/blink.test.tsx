import { createHash } from 'crypto';
import { memorySecrets } from '../core/secrets';
import { Home } from '../home/home';
import { recentEventsTool } from '../home/homeTools';
import type { HttpFetch, HttpResponse } from '../ollama/client';
import { memoryStore } from '../settings/settings';
import { FakeClock } from '../testing/nodeSqlite';
import { ToolArgs } from '../tools/framework/toolArgs';
import { BlinkApi } from './api';
import { codeFrom, csrfToken, parseHomescreen, parseMedia, parseTime, sinceText, snapshotPath, thumbnailPath } from './parse';
import { BlinkService } from './service';

const NOW = Date.parse('2026-11-20T18:00:00Z');
const JPEG = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 9, 9]);
const UUID = /^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$/;
const CODE = '654321';
const OAUTH = 'https://api.oauth.blink.com';
const REST = 'https://rest-u014.immedia-semi.com';

function res(status: number, text: string | Uint8Array, headers: Record<string, string> = {}): HttpResponse {
  const bytes = typeof text === 'string' ? new TextEncoder().encode(text) : text;
  const lower = Object.fromEntries(Object.entries(headers).map(([k, v]) => [k.toLowerCase(), v]));
  let done = false;
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (n) => lower[n.toLowerCase()] ?? null },
    text: async () => new TextDecoder().decode(bytes),
    body: { getReader: () => ({ read: async () => (done ? { done: true } : ((done = true), { done: false, value: bytes })) }) },
  };
}
const json = (status: number, body: unknown, headers: Record<string, string> = {}) => res(status, JSON.stringify(body), { 'content-type': 'application/json', ...headers });
const formOf = (body: string | undefined) => Object.fromEntries((body ?? '').split('&').filter(Boolean).map((p) => p.split('=').map(decodeURIComponent) as [string, string]));

const HOMESCREEN = {
  sync_modules: [{ network_id: 7, status: 'online' }],
  cameras: [
    { id: 11, network_id: 7, name: 'Backyard', type: 'catalina', status: 'done', thumbnail: '/media/thumb/backyard1', battery: 'ok' },
    { id: 12, network_id: 7, name: 'Attic', status: 'done', thumbnail: '/media/thumb/attic1' },
  ],
  owls: [{ id: 21, network_id: 7, name: 'Hall mini', thumbnail: '1700000000' }],
  doorbells: [{ id: 31, network_id: 7, name: 'Front door', thumbnail: '/media/thumb/front1', battery: 'low' }],
};

/**
 * A pretend Blink that follows blinkpy's form flow and **checks** what a real one would: cookies carried between steps, the CSRF token, the PKCE verifier
 * against the challenge, a UUID hardware id, the one-time code, and rotating refresh tokens.
 */
class PretendBlink {
  requests: { method: string; url: string }[] = [];
  needsCode = true;
  password = 'pw';
  signinPageStatus = 200;
  pageHtml = (csrf: string) => `<html><script id="oauth-args" type="application/json">{"csrf-token":"${csrf}"}</script></html>`;
  firstStep202 = false;
  private challenge = '';
  private hardwareId = '';
  private cookie = 'sess-1';
  private verified = false;
  private signedIn = false;
  private counter = 0;
  refreshTokens = new Set<string>();
  accessTokens = new Set<string>();
  refreshCalls = 0;
  refreshStatus: number | null = null;
  homescreen: unknown = null;
  private derived() {
    const h = JSON.parse(JSON.stringify(HOMESCREEN));
    for (const g of ['cameras', 'owls', 'doorbells']) for (const c of h[g]) if (this.thumbs[String(c.id)]) c.thumbnail = this.thumbs[String(c.id)];
    return h;
  }
  thumbs: Record<string, string> = {};
  snapshotPosts: string[] = [];
  commandsDone = true;
  busyFor = 0;
  busyMessage = 'Command in progress';
  newThumbOnPost = true;
  pictureType = 'image/jpeg';
  media: unknown[] = [];
  mediaPages = 0;
  pictureAuth: string[] = [];

  private mint() {
    const n = ++this.counter;
    this.accessTokens.add(`acc-${n}`);
    this.refreshTokens.add(`ref-${n}`);
    return { access_token: `acc-${n}`, refresh_token: `ref-${n}`, expires_in: 3600 };
  }
  expireAccess() {
    this.accessTokens.clear();
  }
  latestRefresh() {
    return [...this.refreshTokens].at(-1) ?? '';
  }

  fetch: HttpFetch = async (url, init) => {
    this.requests.push({ method: init.method, url });
    const h = init.headers ?? {};
    const hasSession = (h.Cookie ?? '').includes(`session=${this.cookie}`);
    if (url.startsWith(`${OAUTH}/oauth/v2/authorize`)) {
      if (url.includes('?')) {
        const q = formOf(url.split('?')[1]);
        if (q.client_id !== 'ios' || q.code_challenge_method !== 'S256' || !UUID.test(q.hardware_id)) return res(400, 'bad authorize');
        this.challenge = q.code_challenge;
        this.hardwareId = q.hardware_id;
        return res(200, '<html>ok</html>', { 'set-cookie': `session=${this.cookie}; Path=/; HttpOnly` });
      }
      if (!hasSession || !this.signedIn) return res(302, '', { location: `${OAUTH}/oauth/v2/signin` });
      return res(302, '', { location: 'immedia-blink://applinks.blink.com/signin/callback?code=ONETIME&state=x' });
    }
    if (url === `${OAUTH}/oauth/v2/signin` && init.method === 'GET') {
      if (this.signinPageStatus !== 200) return res(this.signinPageStatus, '<html>Not Acceptable</html>', { server: 'cloudflare' });
      return hasSession ? res(200, this.pageHtml('CSRF1'), { 'content-type': 'text/html' }) : res(403, 'no cookie');
    }
    if (url === `${OAUTH}/oauth/v2/signin` && init.method === 'POST') {
      const f = formOf(init.body);
      if (!hasSession || f['csrf-token'] !== 'CSRF1') return res(403, 'csrf');
      if (f.password !== this.password) return res(401, '{}');
      if (this.firstStep202) return json(202, { tsv_state: 'sms', next_time_in_secs: 30 });
      if (this.needsCode) return res(412, '{}');
      this.signedIn = true;
      return res(302, '', { location: `${OAUTH}/oauth/v2/authorize` });
    }
    if (url === `${OAUTH}/oauth/v2/2fa/verify`) {
      const f = formOf(init.body);
      if (!hasSession || f['csrf-token'] !== 'CSRF1') return res(403, 'csrf');
      if (f['2fa_code'] !== CODE) return json(400, { message: 'bad code' });
      this.signedIn = true;
      this.verified = true;
      return json(201, { status: 'auth-completed' });
    }
    if (url === `${OAUTH}/oauth/token`) {
      const f = formOf(init.body);
      if (f.grant_type === 'authorization_code') {
        const proof = createHash('sha256').update(f.code_verifier).digest('base64url');
        if (f.code !== 'ONETIME' || proof !== this.challenge || f.hardware_id !== this.hardwareId || !UUID.test(f.hardware_id)) return json(400, { error: 'invalid_grant' });
        return json(200, this.mint());
      }
      this.refreshCalls++;
      if (!UUID.test(f.hardware_id)) return json(400, {});
      if (this.refreshStatus) return json(this.refreshStatus, {});
      if (!this.refreshTokens.has(f.refresh_token)) return json(400, { error: 'invalid_grant' });
      this.refreshTokens.delete(f.refresh_token); // rotates
      return json(200, this.mint());
    }
    // The account.
    const token = (h.Authorization ?? '').replace('Bearer ', '');
    if (!this.accessTokens.has(token)) return json(401, {});
    if (url === 'https://rest-prod.immedia-semi.com/api/v1/users/tier_info') return json(200, { tier: 'u014', account_id: 555 });
    if (url === `${REST}/api/v3/accounts/555/homescreen`) return json(200, this.homescreen ?? this.derived());
    const post = /\/(?:network\/7\/camera\/(\d+)|api\/v1\/accounts\/555\/networks\/7\/(?:owls|doorbells)\/(\d+))\/thumbnail$/.exec(url);
    if (post && init.method === 'POST') {
      if (this.busyFor > 0) {
        this.busyFor--;
        return json(409, { message: this.busyMessage });
      }
      const id = post[1] ?? post[2];
      this.snapshotPosts.push(url.replace(REST, ''));
      if (this.newThumbOnPost) this.thumbs[id] = `/media/thumb/new-${id}`;
      return json(200, { id: 900 + Number(id), network_id: 7 });
    }
    const cmd = /\/network\/7\/command\/(\d+)$/.exec(url);
    if (cmd) return json(200, { complete: this.commandsDone });
    if (url.includes('/media/thumb/') && url.endsWith('.jpg')) {
      this.pictureAuth.push(token);
      return res(200, JPEG, { 'content-type': this.pictureType });
    }
    if (url.includes('/api/v1/accounts/555/media/changed')) {
      const page = Number(/page=(\d+)/.exec(url)?.[1] ?? 1);
      this.mediaPages++;
      return json(200, { media: this.media.slice((page - 1) * 2, page * 2) });
    }
    return json(404, {});
  };
}

function setup(over: { pretend?: (b: PretendBlink) => void } = {}) {
  const blink = new PretendBlink();
  over.pretend?.(blink);
  const secrets = memorySecrets();
  const clock = { t: NOW };
  let n = 0;
  const uuids = ['aaaaaaaa-1111-2222-3333-444444444444', 'bbbbbbbb-1111-2222-3333-444444444444'];
  const service = new BlinkService({
    secrets,
    api: new BlinkApi({ fetch: blink.fetch, randomBytes: (k) => Uint8Array.from({ length: k }, (_, i) => (i * 7 + 3) % 256) }),
    now: () => clock.t,
    uuid: () => uuids[n++],
    pause: async () => {
      clock.t += 1000;
    },
  });
  return { blink, secrets, service, clock };
}

async function signedIn() {
  const s = setup();
  expect((await s.service.signIn({ email: 'me@example.com', password: 'pw' })).kind).toBe('needs_code');
  expect(await s.service.signIn({ code: CODE })).toEqual({ kind: 'ok' });
  return s;
}

describe('parsing', () => {
  it('reads cameras, minis and doorbells, offline sync modules and odd input', () => {
    const cams = parseHomescreen(HOMESCREEN)!;
    expect(cams.map((c) => [c.id, c.name, c.kind, c.lowBattery, c.online])).toEqual([
      ['12', 'Attic', 'camera', null, true],
      ['11', 'Backyard', 'camera', false, true],
      ['31', 'Front door', 'doorbell', true, null],
      ['21', 'Hall mini', 'mini', null, null],
    ]);
    const off = parseHomescreen({ sync_modules: [{ network_id: '7', status: 'offline' }], cameras: [{ id: 1, network_id: 7, name: 'A', status: 'done' }, { id: 2, name: 'no network' }, 5] })!;
    expect(off).toHaveLength(1);
    expect(off[0].online).toBe(false);
    expect(parseHomescreen('x')).toBeNull();
  });

  it('finds the snapshot and thumbnail addresses of each kind', () => {
    const [attic, backyard, front, mini] = parseHomescreen(HOMESCREEN)!;
    expect(snapshotPath('555', backyard)).toBe('/network/7/camera/11/thumbnail');
    expect(snapshotPath('555', mini)).toBe('/api/v1/accounts/555/networks/7/owls/21/thumbnail');
    expect(snapshotPath('555', front)).toBe('/api/v1/accounts/555/networks/7/doorbells/31/thumbnail');
    expect(thumbnailPath('555', backyard)).toBe('/media/thumb/backyard1.jpg');
    expect(thumbnailPath('555', mini)).toBe('/api/v3/media/accounts/555/networks/7/catalina/21/thumbnail/thumbnail.jpg?ts=1700000000&ext=');
    expect(thumbnailPath('555', { ...attic, thumbnail: '/x/y?ts=1&ext=' })).toBe('/x/y?ts=1&ext=');
    expect(thumbnailPath('555', { ...attic, thumbnail: null })).toBeNull();
  });

  it('reads media in both time spellings, skipping deleted clips and snapshots, newest first', () => {
    const media = parseMedia({
      media: [
        { id: 1, device_id: 11, device_name: 'Backyard', created_at: '2026-11-20T10:00:00+00:00', media: '/m/1.mp4', source: 'pir' },
        { id: 2, device_id: 11, device_name: 'Backyard', created_at: '2026-11-20T12:00:00+0000', media: '/m/2.mp4' },
        { id: 3, created_at: '2026-11-20T13:00:00+0000', media: '/m/3.mp4', deleted: true },
        { id: 4, created_at: '2026-11-20T13:00:00+0000', media: '/m/4.jpg', source: 'snapshot' },
        { id: 5, device_id: 31, created_at: '2026-11-20T14:00:00Z', media: '/m/5.mp4', source: 'doorbell' },
        { id: 6, created_at: 'garbage', media: '/m/6' },
      ],
    })!;
    expect(media.map((m) => [m.id, m.kind])).toEqual([['5', 'ring'], ['2', 'motion'], ['1', 'motion']]);
    expect(parseTime('2026-11-20T12:00:00+0000')).toBe(Date.parse('2026-11-20T12:00:00Z'));
    expect(parseTime('2026-11-20T12:00:00+02:00')).toBe(Date.parse('2026-11-20T10:00:00Z'));
    expect(sinceText(Date.parse('2026-10-29T08:34:19.900Z'))).toBe('2026-10-29T08:34:19+0000');
  });

  it('finds the CSRF token and the code', () => {
    expect(csrfToken('<script id="oauth-args" type="application/json">{"csrf-token":"abc"}</script>')).toBe('abc');
    expect(csrfToken('<script type="application/json" id=\'oauth-args\'>{"csrf-token":"x y"}</script>')).toBe('x y');
    expect(csrfToken('<script id="oauth-args">not json</script>')).toBeNull();
    expect(csrfToken('<html></html>')).toBeNull();
    expect(codeFrom('immedia-blink://applinks.blink.com/signin/callback?state=1&code=AB%2BC')).toBe('AB+C');
    expect(codeFrom('immedia-blink://x?state=1')).toBeNull();
    expect(codeFrom(null)).toBeNull();
  });
});

describe('sign-in', () => {
  it('walks the form flow with a code: cookies, CSRF, PKCE and a UUID hardware id, kept in the Keychain', async () => {
    const s = await signedIn();
    expect(s.service.state.get()).toEqual({ kind: 'connected' });
    const saved = JSON.parse(s.secrets.dump()['home.blink']);
    expect(saved).toEqual({ refreshToken: s.blink.latestRefresh(), hardwareId: 'AAAAAAAA-1111-2222-3333-444444444444', tier: 'u014', accountId: '555' });
    expect(s.secrets.dump()['home.blink']).not.toMatch(/pw|me@example/);
    expect(saved.hardwareId).toMatch(UUID);
  });

  it('signs in without a code when the account asks for none', async () => {
    const s = setup({ pretend: (b) => (b.needsCode = false) });
    expect(await s.service.signIn({ email: 'me@example.com', password: 'pw' })).toEqual({ kind: 'ok' });
  });

  it('treats a 202 listing code methods as a code wanted', async () => {
    const s = setup({ pretend: (b) => (b.firstStep202 = true) });
    expect((await s.service.signIn({ email: 'me@example.com', password: 'pw' })).kind).toBe('needs_code');
  });

  it('says a wrong code is wrong and lets the same sign-in try again', async () => {
    const s = setup();
    await s.service.signIn({ email: 'me@example.com', password: 'pw' });
    expect(await s.service.signIn({ code: '000000' })).toEqual({ kind: 'failed', message: expect.stringContaining("That code didn't work") });
    expect(await s.service.signIn({ code: CODE })).toEqual({ kind: 'ok' });
    expect(s.blink.requests.filter((r) => r.url.includes('/oauth/v2/signin') && r.method === 'POST')).toHaveLength(1); // the page was not walked again
  });

  it('says a wrong password is wrong, and forgets the half-done sign-in', async () => {
    const s = setup();
    expect(await s.service.signIn({ email: 'me@example.com', password: 'nope' })).toEqual({ kind: 'failed', message: "Blink says the email or password isn't right." });
    expect((await s.service.signIn({ code: CODE })).kind).toBe('failed'); // nothing is waiting: it asks for the email and password
    expect(s.service.state.get().kind).toBe('signed_out');
  });

  it('starts a new sign-in and tries the typed code on it when the app was closed in between', async () => {
    const s = setup();
    await s.service.signIn({ email: 'me@example.com', password: 'pw' });
    const again = new BlinkService({ secrets: s.secrets, api: new BlinkApi({ fetch: s.blink.fetch, randomBytes: (k) => new Uint8Array(k).fill(5) }), uuid: () => 'cccccccc-1111-2222-3333-444444444444' });
    expect(await again.signIn({ email: 'me@example.com', password: 'pw', code: CODE })).toEqual({ kind: 'ok' });
  });

  it('keeps one hardware id: a second sign-in reuses the saved one', async () => {
    const s = await signedIn();
    const first = JSON.parse(s.secrets.dump()['home.blink']).hardwareId;
    await s.service.signIn({ email: 'me@example.com', password: 'pw' });
    await s.service.signIn({ code: CODE });
    expect(JSON.parse(s.secrets.dump()['home.blink']).hardwareId).toBe(first);
  });

  it('says what changed when the sign-in page is not what it expects', async () => {
    const s = setup({ pretend: (b) => (b.pageHtml = () => '<html>new page</html>') });
    const r = await s.service.signIn({ email: 'me@example.com', password: 'pw' });
    expect(r).toEqual({ kind: 'failed', message: expect.stringContaining('Blink may have changed it') });
  });

  it('says the VPN sentence for a 406, naming the step, and does not retry', async () => {
    const s = setup({ pretend: (b) => (b.signinPageStatus = 406) });
    const r = await s.service.signIn({ email: 'me@example.com', password: 'pw' });
    expect(r).toMatchObject({ kind: 'failed', message: expect.stringMatching(/Blink refused the connection \(HTTP 406\) at the second step \(the sign-in page\).*turn it off and try again/) });
    expect(s.blink.requests.filter((q) => q.url.endsWith('/oauth/v2/signin'))).toHaveLength(1);
    expect(s.service.state.get().kind).toBe('signed_out');
  });

  it('refuses a redirect off Blink', async () => {
    const api = new BlinkApi({ fetch: async () => res(302, '', { location: 'https://evil.example.com/steal' }), randomBytes: (k) => new Uint8Array(k) });
    const start = await api.begin('AAAAAAAA-1111-2222-3333-444444444444');
    expect(start).toMatchObject({ kind: 'failed', why: expect.stringContaining("isn't Blink's") });
  });

  it('restores from the Keychain; damaged means signed out; disconnect forgets', async () => {
    const s = await signedIn();
    const again = new BlinkService({ secrets: s.secrets, api: new BlinkApi({ fetch: s.blink.fetch, randomBytes: (k) => new Uint8Array(k) }), uuid: () => 'x' });
    await again.restore();
    expect(again.state.get()).toEqual({ kind: 'connected' });
    const bad = new BlinkService({ secrets: memorySecrets({ 'home.blink': '{"refreshToken":1}' }), api: new BlinkApi({ fetch: s.blink.fetch, randomBytes: (k) => new Uint8Array(k) }), uuid: () => 'x' });
    await bad.restore();
    expect(bad.state.get().kind).toBe('signed_out');
    await s.service.disconnect();
    expect(s.secrets.dump()['home.blink']).toBeUndefined();
  });
});

describe('the refresh token', () => {
  it('rotates: the new one is saved before the access token it came with is used, and refreshes never overlap', async () => {
    const s = await signedIn();
    s.blink.expireAccess();
    s.clock.t += 2 * 3_600_000;
    let savedFirst = false;
    const real = s.blink.fetch;
    const api = new BlinkApi({
      fetch: async (u, i) => {
        if (u.endsWith('/homescreen')) savedFirst = JSON.parse(s.secrets.dump()['home.blink']).refreshToken === s.blink.latestRefresh();
        return real(u, i);
      },
      randomBytes: (k) => new Uint8Array(k),
    });
    const svc = new BlinkService({ secrets: s.secrets, api, now: () => s.clock.t, uuid: () => 'x' });
    await svc.restore();
    const all = await Promise.all(Array.from({ length: 6 }, () => svc.backend().devices()));
    expect(all.every((d) => d.length === 4)).toBe(true);
    expect(savedFirst).toBe(true);
    expect(s.blink.refreshCalls).toBe(1);
  });

  it('a refused refresh is "sign in again" once; an unreachable Blink is not a sign-out', async () => {
    const s = await signedIn();
    s.blink.expireAccess();
    s.clock.t += 2 * 3_600_000;
    s.blink.refreshStatus = 400;
    await expect(s.service.backend().devices()).rejects.toThrow('sign in again');
    expect(s.service.state.get()).toMatchObject({ kind: 'needs_sign_in' });
    expect(s.blink.refreshCalls).toBe(1);

    const t = await signedIn();
    t.clock.t += 2 * 3_600_000;
    const real = t.blink.fetch;
    let down = true;
    const svc = new BlinkService({
      secrets: t.secrets,
      api: new BlinkApi({ fetch: async (u, i) => (down ? Promise.reject(new Error('offline')) : real(u, i)), randomBytes: (k) => new Uint8Array(k) }),
      now: () => t.clock.t,
      uuid: () => 'x',
    });
    await svc.restore();
    await expect(svc.backend().devices()).rejects.toThrow("can't be reached");
    expect(svc.state.get()).toEqual({ kind: 'connected' });
    down = false;
    expect(await svc.backend().devices()).toHaveLength(4);
  });
});

describe('devices and snapshots', () => {
  it('lists cameras and doorbells with no battery and no commands', async () => {
    const s = await signedIn();
    const list = await s.service.backend().devices();
    expect(list.map((d) => [d.id, d.name, d.type, d.capabilities, d.state, d.online])).toEqual([
      ['blink:12', 'Attic', 'camera', ['snapshot', 'events'], {}, true],
      ['blink:11', 'Backyard', 'camera', ['snapshot', 'events'], {}, true],
      ['blink:31', 'Front door', 'doorbell', ['snapshot', 'events'], {}, true],
      ['blink:21', 'Hall mini', 'camera', ['snapshot', 'events'], {}, true],
    ]);
    await expect(s.service.backend().command('blink:11', { type: 'turn_on' })).rejects.toThrow('pictures and motion only');
  });

  it('asks for a snapshot, waits for the command, reads the list again and fetches the NEW picture with the bearer token', async () => {
    const s = await signedIn();
    const shot = await s.service.backend().snapshot!('blink:11');
    expect(shot.jpegBase64).toBe(Buffer.from(JPEG).toString('base64'));
    expect(s.blink.snapshotPosts).toEqual(['/network/7/camera/11/thumbnail']);
    const order = s.blink.requests.map((r) => r.method + ' ' + r.url.replace(REST, ''));
    const post = order.indexOf('POST /network/7/camera/11/thumbnail');
    const poll = order.findIndex((o) => o.includes('/command/911'));
    const picture = order.findIndex((o) => o.endsWith('/media/thumb/new-11.jpg'));
    expect(post).toBeGreaterThan(-1);
    expect(poll).toBeGreaterThan(post);
    expect(picture).toBeGreaterThan(poll);
    expect(s.blink.pictureAuth).toHaveLength(1);
  });

  it("uses each kind's own snapshot address", async () => {
    const s = await signedIn();
    await s.service.backend().snapshot!('blink:21');
    await s.service.backend().snapshot!('blink:31');
    expect(s.blink.snapshotPosts).toEqual(['/api/v1/accounts/555/networks/7/owls/21/thumbnail', '/api/v1/accounts/555/networks/7/doorbells/31/thumbnail']);
  });

  it('a busy sync module (409) is asked again, up to four times, then says so', async () => {
    const s = await signedIn();
    const posts = () => s.blink.requests.filter((r) => r.method === 'POST' && r.url.endsWith('/thumbnail')).length;
    s.blink.busyFor = 2;
    await s.service.backend().snapshot!('blink:11');
    expect(posts()).toBe(3);
    s.blink.busyFor = 99;
    s.blink.requests.length = 0;
    await expect(s.service.backend().snapshot!('blink:11')).rejects.toThrow('Command in progress. Try again in a minute');
    expect(posts()).toBe(4);
  });

  it('a 409 that says offline is not retried and names the sync module', async () => {
    const s = await signedIn();
    s.blink.busyFor = 99;
    s.blink.busyMessage = 'Sync module is offline';
    await expect(s.service.backend().snapshot!('blink:11')).rejects.toThrow('sync module is offline');
    expect(s.blink.requests.filter((r) => r.method === 'POST' && r.url.endsWith('/thumbnail'))).toHaveLength(1);
  });

  it('gives up when the new picture never arrives, and says the camera is offline when it is', async () => {
    const s = await signedIn();
    s.blink.newThumbOnPost = false;
    await expect(s.service.backend().snapshot!('blink:11')).rejects.toThrow("Backyard didn't send a new picture in time");
    s.blink.homescreen = { sync_modules: [{ network_id: 7, status: 'offline' }], cameras: [{ id: 11, network_id: 7, name: 'Backyard' }] };
    const fresh = new BlinkService({ secrets: s.secrets, api: new BlinkApi({ fetch: s.blink.fetch, randomBytes: (k) => new Uint8Array(k) }), now: () => s.clock.t, uuid: () => 'x', pause: async () => {} });
    await fresh.restore();
    await expect(fresh.backend().snapshot!('blink:11')).rejects.toThrow('Backyard is offline.');
  });

  it('refuses something that is not a picture', async () => {
    const s = await signedIn();
    s.blink.pictureType = 'text/html';
    await expect(s.service.backend().snapshot!('blink:11')).rejects.toThrow("Blink didn't send the picture");
  });

  it('a dead token turns the service to sign in again, without a loop', async () => {
    const s = await signedIn();
    s.blink.refreshStatus = 401;
    s.blink.expireAccess();
    await expect(s.service.backend().snapshot!('blink:11')).rejects.toThrow('sign in again');
    expect(s.service.state.get().kind).toBe('needs_sign_in');
    expect(s.blink.refreshCalls).toBe(1);
  });
});

describe('events', () => {
  const media = (id: number, cam: number, name: string, hoursAgo: number, extra: object = {}) => ({
    id,
    device_id: cam,
    device_name: name,
    created_at: new Date(NOW - hoursAgo * 3_600_000).toISOString().replace('.000Z', '+0000'),
    media: `/m/${id}.mp4`,
    ...extra,
  });

  it('lists a camera\'s recordings or all, newest first, paging while pages are full', async () => {
    const s = await signedIn();
    s.blink.media = [media(1, 11, 'Backyard', 1), media(2, 31, 'Front door', 2, { source: 'doorbell' }), media(3, 11, 'Backyard', 3), media(4, 12, 'Attic', 4), media(5, 11, 'Backyard', 40)];
    const all = await s.service.backend().events!(null, NOW - 24 * 3_600_000);
    expect(all.map((e) => [e.id, e.kind])).toEqual([['1', 'motion'], ['2', 'ring'], ['3', 'motion'], ['4', 'motion']]);
    expect(all[0]).toMatchObject({ deviceId: 'blink:11', deviceName: 'Backyard' });
    const one = await s.service.backend().events!('blink:11', NOW - 24 * 3_600_000);
    expect(one.map((e) => e.id)).toEqual(['1', '3']);
    expect(s.blink.mediaPages).toBeGreaterThanOrEqual(2);
  });

  it('answers recent_events from Blink', async () => {
    const s = await signedIn();
    s.blink.media = [media(1, 11, 'Backyard', 1)];
    const home = new Home({ store: memoryStore() as never, setTimer: () => 0 });
    home.setBackends([s.service.backend()]);
    await home.refresh();
    const tool = recentEventsTool(home, () => NOW);
    const r = await tool.execute(new ToolArgs({}, tool.spec), { clock: new FakeClock() });
    expect(r.text).toMatch(/— Backyard — Motion$/);
  });
});
