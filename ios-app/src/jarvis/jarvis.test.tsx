import { createHmac } from 'crypto';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { memorySecrets } from '../core/secrets';
import { secureFetch } from '../core/secureFetch';
import { Home } from '../home/home';
import type { HttpFetch, HttpResponse } from '../ollama/client';
import { memoryStore } from '../settings/settings';
import { ServiceSignIn } from '../ui/ServiceSignIn';
import { themes } from '../ui/theme';
import { JarvisApi, pairingProof, parseShared } from './api';
import { JarvisLink } from './link';
import { DEFAULT_PORT, normalizeCode, parseJarvisAddress } from './names';
import { JarvisService, NEEDS_PAIR } from './service';

const HOST = 'ben-pc.tail1234.ts.net';
const CODE = 'ABCDEFGHJK';
const TOKEN = 'T0KEN-0123456789-abcdefghijklmnopqrstuvwxyz';

const res = (status: number, body: unknown): HttpResponse => ({ ok: status < 300, status, text: async () => (typeof body === 'string' ? body : JSON.stringify(body)), body: null });

/** A pretend Jarvis (Tailscale listener) that checks what the real one would: the hello fields, the proof (computed with Node's crypto), one-use codes, tries, the bearer token. */
class PretendJarvis {
  code: string | null = CODE;
  tries = 0;
  failures = 0;
  tokens = new Set<string>();
  calls: { method: string; path: string; auth?: string; body?: string }[] = [];
  down = false;
  helloHost = HOST;
  helloTransport = 'tailscale';
  shared: unknown = { pcName: 'BEN-PC', mail: [{ id: 'a' }], calendars: [{ id: 'c' }], tasks: [], printer: { name: 'AD5X' } };

  fetch: HttpFetch = async (url, init) => {
    const path = url.replace(`https://${HOST}:${DEFAULT_PORT}`, '');
    this.calls.push({ method: init.method, path, auth: init.headers?.Authorization, body: init.body });
    if (this.down) throw new TypeError('Network request failed');
    if (path === '/v1/hello') return res(200, { app: 'jarvis-feed', version: 1, transport: this.helloTransport, host: this.helloHost });
    if (path === '/v1/pair') {
      if (this.failures >= 10) return res(429, { error: { code: 'BUSY', message: 'Slow down.' } });
      const { proof, name } = JSON.parse(init.body ?? '{}') as { proof: string; name: string };
      const want = this.code ? createHmac('sha256', this.code).update(`tailscale:${HOST}`).digest('hex') : null;
      if (!this.code || this.tries >= 5 || proof !== want || name !== 'iPhone') {
        this.tries++;
        this.failures++;
        return res(403, { error: { code: 'PAIRING_REFUSED', message: 'No.' } });
      }
      this.code = null; // one use
      this.tokens.add(TOKEN);
      return res(200, { token: TOKEN, deviceId: 'dev-1', pcName: 'BEN-PC', version: 1 });
    }
    const token = (init.headers?.Authorization ?? '').replace('Bearer ', '');
    if (!this.tokens.has(token)) return res(401, { error: { code: 'UNAUTHORIZED', message: 'Not paired.' } });
    if (path === '/v1/shared') return res(200, this.shared);
    if (path === '/v1/printer/state') return res(404, { error: { code: 'NOT_SHARED', message: 'Not shared.' } });
    if (path === '/v1/mail/messages') return res(503, { error: { code: 'OUTLOOK_UNAVAILABLE', message: "Outlook isn't running." } });
    return res(500, { error: { code: 'FEED_ERROR', message: 'Something went wrong.' } });
  };
}

function setup(over: { existing?: boolean } = {}) {
  const jarvis = new PretendJarvis();
  const secrets = memorySecrets(over.existing ? { 'home.jarvis': new JarvisLink(HOST, DEFAULT_PORT, 'BEN-PC', TOKEN).serialize() } : {});
  if (over.existing) jarvis.tokens.add(TOKEN);
  let t = 1_000_000;
  const service = new JarvisService({ secrets, api: new JarvisApi({ fetch: secureFetch(jarvis.fetch, { allowHosts: ['ts.net'] }) }), now: () => t });
  return { jarvis, secrets, service, advance: (ms: number) => (t += ms) };
}

describe('the address and the code', () => {
  it('accepts a Tailscale name in the forms people paste, and nothing else', () => {
    expect(parseJarvisAddress('ben-pc.tail1234.ts.net')).toEqual({ ok: true, host: HOST, port: 47822 });
    expect(parseJarvisAddress('  BEN-PC.Tail1234.TS.NET  ')).toEqual({ ok: true, host: HOST, port: 47822 });
    expect(parseJarvisAddress('https://ben-pc.tail1234.ts.net:48000/')).toEqual({ ok: true, host: HOST, port: 48000 });
    expect(parseJarvisAddress('ben-pc.tail1234.ts.net.')).toMatchObject({ ok: false }); // a trailing dot is not a name Jarvis shows
    for (const bad of ['', '192.168.1.20', '100.101.102.103', 'ben-pc', 'ben-pc.local', 'example.com', 'ts.net', '.ts.net', 'a.ts.net', 'tail1234.ts.net', 'a.b.example.com', 'ben-pc.tail1234.ts.net.evil.com']) {
      expect(parseJarvisAddress(bad)).toMatchObject({ ok: false });
    }
    expect(parseJarvisAddress('http://ben-pc.tail1234.ts.net')).toEqual({ ok: false, message: expect.stringContaining('HTTPS only') });
    expect(parseJarvisAddress('ben-pc.tail1234.ts.net:80')).toEqual({ ok: false, message: 'The port has to be between 1024 and 65535.' });
    expect(parseJarvisAddress('ben-pc.tail1234.ts.net:99999')).toMatchObject({ ok: false });
  });

  it('reads the code as Jarvis shows it, in any case, with or without the dash and spaces', () => {
    expect(normalizeCode('ABCDE-FGHJK')).toBe(CODE);
    expect(normalizeCode(' abcde fghjk ')).toBe(CODE);
    expect(normalizeCode('ABCDE-FGHJ')).toBeNull();
    expect(normalizeCode('ABCDE-FGHJ0')).toBeNull(); // 0 is not in Jarvis's alphabet
    expect(normalizeCode('ABCDE-FGHJKL')).toBeNull();
  });

  it('computes the proof as HMAC-SHA256(code, "tailscale:" + name) and never sends the code', () => {
    expect(pairingProof(CODE, HOST)).toBe(createHmac('sha256', CODE).update(`tailscale:${HOST}`).digest('hex'));
    expect(pairingProof(CODE, 'other.tail1234.ts.net')).not.toBe(pairingProof(CODE, HOST));
  });
});

describe('pairing', () => {
  it('pairs: hello checked, proof accepted, the token saved BEFORE it says connected, nothing secret in the notice', async () => {
    const { jarvis, secrets, service } = setup();
    let savedBeforeConnected = false;
    service.state.subscribe(() => {
      if (service.state.get().kind === 'connected' && !savedBeforeConnected) savedBeforeConnected = !!secrets.dump()['home.jarvis'];
    });
    const r = await service.signIn({ host: HOST, code: 'abcde-fghjk' });
    expect(r).toEqual({ kind: 'ok' });
    expect(savedBeforeConnected).toBe(true);
    expect(service.state.get()).toMatchObject({ kind: 'connected', detail: 'BEN-PC · shares mail, calendar, printer' });
    expect(JSON.parse(secrets.dump()['home.jarvis'])).toEqual({ host: HOST, port: 47822, pcName: 'BEN-PC', token: TOKEN });
    expect(jarvis.calls.map((c) => `${c.method} ${c.path}`)).toEqual(['GET /v1/hello', 'POST /v1/pair', 'GET /v1/shared']);
    const pair = jarvis.calls[1];
    expect(pair.auth).toBeUndefined(); // no token yet
    expect(pair.body).not.toContain(CODE); // the proof, not the code
    expect(jarvis.calls[2].auth).toBe(`Bearer ${TOKEN}`);
  });

  it('says a wrong or used code is wrong, the same words for every cause, and stays signed out', async () => {
    const { jarvis, service } = setup();
    const wrong = await service.signIn({ host: HOST, code: 'ZZZZZ-ZZZZZ' });
    expect(wrong).toEqual({ kind: 'failed', message: "Jarvis didn't accept that code. Make a new one on the PC (it lasts 5 minutes)." });
    expect(service.state.get().kind).toBe('signed_out');
    jarvis.code = null;
    expect(await service.signIn({ host: HOST, code: CODE })).toEqual(wrong);
  });

  it('five wrong tries burn the code even when the right one comes next', async () => {
    const { service } = setup();
    for (let i = 0; i < 5; i++) await service.signIn({ host: HOST, code: 'ZZZZZ-ZZZZZ' });
    expect((await service.signIn({ host: HOST, code: CODE })).kind).toBe('failed');
  });

  it('asks the owner to slow down when Jarvis rate-limits', async () => {
    const { jarvis, service } = setup();
    jarvis.failures = 10;
    expect(await service.signIn({ host: HOST, code: CODE })).toEqual({ kind: 'failed', message: 'Jarvis says to slow down; try again in a minute.' });
  });

  it('refuses a bad name or code before any request is sent', async () => {
    const { jarvis, service } = setup();
    expect((await service.signIn({ host: '192.168.1.20', code: CODE })).kind).toBe('failed');
    expect(await service.signIn({ host: HOST, code: 'short' })).toEqual({ kind: 'failed', message: 'The pairing code is 10 letters and numbers, like ABCDE-FGHJK.' });
    expect(jarvis.calls).toEqual([]);
  });

  it('is not fooled by something that is not Jarvis, another listener, or another name', async () => {
    const a = setup();
    a.jarvis.helloHost = 'someone-else.tail1234.ts.net';
    expect(await a.service.signIn({ host: HOST, code: CODE })).toEqual({ kind: 'failed', message: expect.stringContaining("isn't the Jarvis you meant") });
    const b = setup();
    b.jarvis.helloTransport = 'self-signed';
    expect(await b.service.signIn({ host: HOST, code: CODE })).toEqual({ kind: 'failed', message: expect.stringContaining("other listener") });
    expect(b.jarvis.calls.map((c) => c.path)).toEqual(['/v1/hello']); // never sent a proof to it
    const api = new JarvisApi({ fetch: async () => res(200, '<html>hello</html>') });
    expect(await api.hello(HOST, 47822)).toEqual({ kind: 'notJarvis', message: expect.stringContaining("isn't Jarvis") });
  });

  it('says what to check when Jarvis cannot be reached, and a refused certificate or a non-ts.net host never gets a request', async () => {
    const { jarvis, service } = setup();
    jarvis.down = true;
    expect(await service.signIn({ host: HOST, code: CODE })).toEqual({ kind: 'failed', message: expect.stringContaining('Is Tailscale on for this iPhone') });
    expect(service.state.get().kind).toBe('signed_out');
    let asked = 0;
    const strict = new JarvisApi({ fetch: secureFetch(async () => (asked++, res(200, {})), { allowHosts: ['ts.net'] }) });
    expect(await strict.hello('ben-pc.example.com', 47822)).toEqual({ kind: 'unreachable' }); // refused by the secure client
    expect(asked).toBe(0);
  });
});

describe('the connection afterwards', () => {
  it('restores from the Keychain; damaged means signed out', async () => {
    const { service } = setup({ existing: true });
    await service.restore();
    expect(service.state.get()).toEqual({ kind: 'connected', detail: 'BEN-PC' });
    const bad = new JarvisService({ secrets: memorySecrets({ 'home.jarvis': '{"host":1}' }), api: new JarvisApi({ fetch: async () => res(200, {}) }) });
    await bad.restore();
    expect(bad.state.get().kind).toBe('signed_out');
  });

  it('gives other features an authenticated client and maps Jarvis\'s answers', async () => {
    const { service } = setup({ existing: true });
    await service.restore();
    const ok = await service.call('GET', '/v1/shared', parseShared);
    expect(ok).toMatchObject({ kind: 'ok', value: { mail: 1, calendars: 1, tasks: 0, printer: true } });
    expect(await service.call('GET', '/v1/printer/state', () => ({}))).toEqual({ kind: 'notShared' });
    expect(await service.call('GET', '/v1/mail/messages', () => ({}))).toEqual({ kind: 'unavailable', message: "Outlook isn't running." });
    expect(await service.call('GET', '/v1/nothing', () => ({}))).toEqual({ kind: 'failed', message: 'Something went wrong.' });
    expect(await service.call('GET', '/v1/shared', () => null)).toEqual({ kind: 'failed', message: "Jarvis's answer wasn't what Edith expected." });
    expect(service.state.get().kind).toBe('connected');
  });

  it('a 401 turns it to "pair it again" once, and the refused token is never sent again', async () => {
    const { jarvis, service } = setup({ existing: true });
    await service.restore();
    jarvis.tokens.clear(); // removed in Jarvis's list
    expect(await service.call('GET', '/v1/shared', parseShared)).toEqual({ kind: 'unauthorized' });
    expect(service.state.get()).toEqual({ kind: 'needs_sign_in', reason: NEEDS_PAIR });
    const before = jarvis.calls.length;
    expect(await service.call('GET', '/v1/shared', parseShared)).toEqual({ kind: 'unauthorized' });
    expect(jarvis.calls).toHaveLength(before); // no loop
  });

  it('pairing again after that uses the NEW token', async () => {
    const { jarvis, secrets, service } = setup({ existing: true });
    await service.restore();
    jarvis.tokens.clear();
    await service.call('GET', '/v1/shared', parseShared);
    jarvis.code = CODE;
    jarvis.tries = 0;
    expect(await service.signIn({ host: HOST, code: CODE })).toEqual({ kind: 'ok' });
    expect(service.state.get().kind).toBe('connected');
    expect(JSON.parse(secrets.dump()['home.jarvis']).token).toBe(TOKEN);
    expect(await service.call('GET', '/v1/shared', parseShared)).toMatchObject({ kind: 'ok' });
  });

  it('an unreachable Jarvis is not a sign-out and not an error state: it only says so, then recovers', async () => {
    const { jarvis, service } = setup({ existing: true });
    await service.restore();
    jarvis.down = true;
    expect(await service.call('GET', '/v1/shared', parseShared)).toEqual({ kind: 'unreachable' });
    expect(service.state.get()).toEqual({ kind: 'connected', detail: "BEN-PC · can't reach it right now" });
    jarvis.down = false;
    expect(await service.call('GET', '/v1/shared', parseShared)).toMatchObject({ kind: 'ok' });
    expect(service.state.get()).toMatchObject({ kind: 'connected' });
    expect((service.state.get() as { detail?: string }).detail).not.toContain("can't reach");
  });

  it('a call that takes too long answers timeout, and can be cancelled', async () => {
    const hang: HttpFetch = (_u, init) => new Promise((_, reject) => init.signal?.addEventListener('abort', () => reject(new Error('aborted'))));
    const api = new JarvisApi({ fetch: hang });
    const link = new JarvisLink(HOST, 47822, 'BEN-PC', TOKEN);
    expect(await api.call(link, 'GET', '/v1/shared', parseShared, { timeoutMs: 20 })).toEqual({ kind: 'timeout' });
    const ctl = new AbortController();
    const p = api.call(link, 'GET', '/v1/shared', parseShared, { signal: ctl.signal, timeoutMs: 5_000 });
    ctl.abort();
    expect(await p).toEqual({ kind: 'unreachable' });
  });

  it('asks Jarvis what it shares at most once a minute from the Home refresh, and never raises a banner', async () => {
    const { jarvis, service, advance } = setup({ existing: true });
    await service.restore();
    const home = new Home({ store: memoryStore() as never, setTimer: () => 0 });
    home.registerService(service);
    const shared = () => jarvis.calls.filter((c) => c.path === '/v1/shared').length;
    await home.refresh();
    expect(shared()).toBe(1);
    await home.refresh();
    expect(shared()).toBe(1);
    advance(61_000);
    jarvis.down = true;
    const snap = await home.refresh();
    expect(shared()).toBe(2);
    expect(snap.problems).toEqual([]); // the PC being off is normal
    expect(snap.devices).toEqual([]);
  });

  it('disconnect forgets the pairing and the Keychain entry', async () => {
    const { secrets, service } = setup({ existing: true });
    await service.restore();
    await service.disconnect();
    expect(secrets.dump()['home.jarvis']).toBeUndefined();
    expect(service.state.get().kind).toBe('signed_out');
    expect(await service.call('GET', '/v1/shared', parseShared)).toEqual({ kind: 'unauthorized' });
  });
});

describe('secrets stay secret', () => {
  it('the token appears nowhere but the Keychain text and the Authorization header', async () => {
    const link = new JarvisLink(HOST, 47822, 'BEN-PC', TOKEN);
    expect(JSON.stringify(link)).not.toContain(TOKEN);
    expect(String(link)).not.toContain(TOKEN);
    expect(`${link}`).toBe(`JarvisLink(${HOST}:47822)`);
    expect(link.serialize()).toContain(TOKEN);
    const { service } = setup();
    const logged: string[] = [];
    const spies = [jest.spyOn(console, 'log').mockImplementation((...a) => void logged.push(a.join(' '))), jest.spyOn(console, 'warn').mockImplementation((...a) => void logged.push(a.join(' ')))];
    await service.signIn({ host: HOST, code: CODE });
    await service.call('GET', '/v1/shared', parseShared);
    spies.forEach((s) => s.mockRestore());
    expect(JSON.stringify(service.state.get())).not.toContain(TOKEN);
    expect(JSON.stringify(service)).not.toContain(TOKEN);
    expect(logged.join('\n')).not.toContain(TOKEN);
    expect(logged.join('\n')).not.toContain(CODE);
  });

  it('the form: two fields, the name in URL keyboard, and the whole pairing through it', async () => {
    const { service, jarvis } = setup();
    const onClose = jest.fn();
    await render(<ServiceSignIn service={service} again={false} theme={themes.light} onClose={onClose} />);
    expect(screen.getByText('Pair with Jarvis')).toBeTruthy();
    await fireEvent.changeText(screen.getByLabelText("PC's Tailscale name"), HOST);
    await fireEvent.changeText(screen.getByLabelText('Pairing code'), 'ABCDE-FGHJK');
    await fireEvent.press(screen.getByLabelText('Pair'));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(jarvis.tokens.has(TOKEN)).toBe(true);
  });
});
