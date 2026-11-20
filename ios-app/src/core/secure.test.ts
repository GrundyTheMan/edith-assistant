import { AppError } from './errors';
import { checkSecretKey, keychainSecrets, memorySecrets } from './secrets';
import { hostAllowed, resolveLocation, secureFetch } from './secureFetch';
import type { HttpFetch, HttpResponse } from '../ollama/client';

const mockStore = new Map<string, string>();
jest.mock('expo-secure-store', () => ({
  WHEN_UNLOCKED_THIS_DEVICE_ONLY: 'when_unlocked_this_device_only',
  getItemAsync: jest.fn(async (k: string) => mockStore.get(k) ?? null),
  setItemAsync: jest.fn(async (k: string, v: string) => void mockStore.set(k, v)),
  deleteItemAsync: jest.fn(async (k: string) => void mockStore.delete(k)),
}));

describe('secrets', () => {
  it('the memory store round-trips and deletes', async () => {
    const s = memorySecrets({ a: '1' });
    expect(await s.get('a')).toBe('1');
    await s.set('b', '2');
    expect(s.dump()).toEqual({ a: '1', b: '2' });
    await s.delete('a');
    expect(await s.get('a')).toBeNull();
  });

  it('the Keychain store goes through expo-secure-store, device-only', async () => {
    const SecureStore = jest.requireMock('expo-secure-store');
    await keychainSecrets.set('home.meross', '{"token":"t"}');
    expect(SecureStore.setItemAsync).toHaveBeenCalledWith('home.meross', '{"token":"t"}', { keychainAccessible: 'when_unlocked_this_device_only' });
    expect(await keychainSecrets.get('home.meross')).toBe('{"token":"t"}');
    await keychainSecrets.delete('home.meross');
    expect(await keychainSecrets.get('home.meross')).toBeNull();
  });

  it('accepts only the characters SecureStore allows in a key', () => {
    expect(checkSecretKey('account.a1b2.refresh_token')).toBe('account.a1b2.refresh_token');
    expect(() => checkSecretKey('home/meross')).toThrow();
    expect(() => checkSecretKey('has space')).toThrow();
  });
});

const response = (status: number, headers: Record<string, string> = {}, url?: string): HttpResponse => ({
  ok: status >= 200 && status < 300,
  status,
  url,
  headers: { get: (n) => headers[n.toLowerCase()] ?? null },
  text: async () => '',
  body: null,
});
function fake(...answers: HttpResponse[]) {
  const seen: { url: string; redirect?: string }[] = [];
  const fetch: HttpFetch = async (url, init) => {
    seen.push({ url, redirect: init.redirect });
    return answers[Math.min(seen.length - 1, answers.length - 1)];
  };
  return { fetch, seen };
}

describe('secureFetch', () => {
  it('refuses http:// without calling the base fetch, naming only the host', async () => {
    const { fetch, seen } = fake(response(200));
    await expect(secureFetch(fetch)('http://user:secret@api.example.com/path?token=abc', { method: 'GET' })).rejects.toMatchObject({
      kind: 'http_blocked',
      message: expect.stringContaining('api.example.com'),
    });
    expect(seen).toHaveLength(0);
    try {
      await secureFetch(fetch)('http://user:secret@api.example.com/path?token=abc', { method: 'GET' });
    } catch (e) {
      expect((e as Error).message).not.toMatch(/secret|token|abc|path/);
    }
  });

  it('allows https and asks the platform for manual redirects', async () => {
    const { fetch, seen } = fake(response(200));
    const res = await secureFetch(fetch)('https://iot.meross.com/v1/Auth/signIn', { method: 'POST', body: '{}' });
    expect(res.status).toBe(200);
    expect(seen).toEqual([{ url: 'https://iot.meross.com/v1/Auth/signIn', redirect: 'manual' }]);
  });

  it('follows a redirect inside the allowed hosts by hand, and resolves a relative Location', async () => {
    const { fetch, seen } = fake(response(302, { location: '/v2/other' }), response(200));
    const res = await secureFetch(fetch, { allowHosts: ['meross.com'] })('https://iot.meross.com/v1/a', { method: 'GET' });
    expect(res.status).toBe(200);
    expect(seen.map((s) => s.url)).toEqual(['https://iot.meross.com/v1/a', 'https://iot.meross.com/v2/other']);
    const second = fake(response(301, { location: 'https://iotx-eu.meross.com/v1/a' }), response(200));
    await secureFetch(second.fetch, { allowHosts: ['meross.com'] })('https://iot.meross.com/v1/a', { method: 'GET' });
    expect(second.seen[1].url).toBe('https://iotx-eu.meross.com/v1/a');
  });

  it('stops a redirect to another host, to http, or after too many hops', async () => {
    const elsewhere = fake(response(302, { location: 'https://evil.example.org/steal' }));
    await expect(secureFetch(elsewhere.fetch, { allowHosts: ['meross.com'] })('https://iot.meross.com/a', { method: 'GET' })).rejects.toBeInstanceOf(AppError);
    expect(elsewhere.seen).toHaveLength(1); // the second request was never made
    const plain = fake(response(302, { location: 'http://iot.meross.com/a' }));
    await expect(secureFetch(plain.fetch, { allowHosts: ['meross.com'] })('https://iot.meross.com/a', { method: 'GET' })).rejects.toMatchObject({ kind: 'http_blocked' });
    const loop = fake(response(302, { location: '/again' }));
    await expect(secureFetch(loop.fetch)('https://a.example.com/', { method: 'GET' })).rejects.toMatchObject({ kind: 'http_blocked' });
    expect(loop.seen).toHaveLength(4); // the first request and 3 redirects
  });

  it('by default a request may only stay on the host it started on', async () => {
    const away = fake(response(302, { location: 'https://other.example.com/' }));
    await expect(secureFetch(away.fetch)('https://a.example.com/', { method: 'GET' })).rejects.toMatchObject({ kind: 'http_blocked' });
  });

  it('throws away an answer from a platform that already followed a redirect off the allowed hosts', async () => {
    const { fetch } = fake(response(200, {}, 'https://evil.example.org/landing'));
    await expect(secureFetch(fetch, { allowHosts: ['meross.com'] })('https://iot.meross.com/a', { method: 'GET' })).rejects.toMatchObject({ kind: 'http_blocked' });
    const downgraded = fake(response(200, {}, 'http://iot.meross.com/a'));
    await expect(secureFetch(downgraded.fetch, { allowHosts: ['meross.com'] })('https://iot.meross.com/a', { method: 'GET' })).rejects.toMatchObject({ kind: 'http_blocked' });
  });

  it('refuses a starting host that is not in the allowed list', async () => {
    const { fetch, seen } = fake(response(200));
    await expect(secureFetch(fetch, { allowHosts: ['meross.com'] })('https://meross.com.evil.org/', { method: 'GET' })).rejects.toBeInstanceOf(AppError);
    expect(seen).toHaveLength(0);
  });

  it('matches host suffixes on a dot boundary and resolves locations', () => {
    expect(hostAllowed('iot.meross.com', ['meross.com'])).toBe(true);
    expect(hostAllowed('meross.com', ['meross.com'])).toBe(true);
    expect(hostAllowed('notmeross.com', ['meross.com'])).toBe(false);
    expect(resolveLocation('https://x.example/y', 'https://a.example/b')).toBe('https://x.example/y');
    expect(resolveLocation('/z', 'https://a.example/b?q=1')).toBe('https://a.example/z');
    expect(resolveLocation('c', 'https://a.example/b/d?q=1')).toBe('https://a.example/b/c');
    expect(resolveLocation('//x.example/y', 'https://a.example/b')).toBe('https://x.example/y');
  });
});
