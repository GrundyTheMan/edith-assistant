import { log } from '../core/log';
import { md5 } from '../core/md5';
import type { HttpFetch } from '../ollama/client';
import { BASE_URL, Status, TOKEN_REJECTED, apiBase, envelope, isTrustedDomain, nonce } from './protocol';

/** What signing in gives, kept in the Keychain. The password is never part of it. */
export interface MerossCreds {
  token: string;
  key: string;
  userId: string;
  email: string;
  /** The API address of the owner's region. */
  domain: string;
  mqttDomain: string;
  /** Sent as the sign-in's `mobileInfo.uuid`, made once per sign-in. */
  installId: string;
}

/** How a sign-in ended (C26 §1). */
export type MerossLogin =
  | { kind: 'ok'; creds: MerossCreds }
  /** Meross wants the code from the owner's authenticator or email. */
  | { kind: 'needs_code' }
  | { kind: 'wrong_code' }
  | { kind: 'bad_login' }
  | { kind: 'too_many_tokens' }
  | { kind: 'failed'; why: string };

/** A refused token is its own case: it means "sign in again", not "try again". */
export type MerossReply<T> = { kind: 'ok'; value: T } | { kind: 'rejected' } | { kind: 'failed'; why: string };

export interface MerossDevice {
  uuid: string;
  name: string;
  type: string;
  online: boolean;
}

export const UNTRUSTED = "Meross named an address that isn't Meross's, so Edith stopped and sent nothing there";

type Raw = { ok: true; body: Record<string, unknown> } | { ok: false; why: string };
const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null);
const text = (o: Record<string, unknown> | null, k: string): string | undefined => (typeof o?.[k] === 'string' ? (o[k] as string) : undefined);
const int = (o: Record<string, unknown> | null, k: string): number | undefined => (typeof o?.[k] === 'number' ? (o[k] as number) : undefined);

export interface MerossApiDeps {
  /** Must be a `secureFetch(httpFetch, { allowHosts: ['meross.com'] })`: HTTPS only, and a redirect can't leave Meross. */
  fetch: HttpFetch;
  now: () => number;
  random?: () => number;
  baseUrl?: string;
  /** Which addresses Meross may send Edith to; tests answer from a made-up one. */
  trusted?: (domain: string) => boolean;
}

/**
 * Meross's HTTP API: sign in, the device list, sign out. Every call is the same signed envelope. A sign-in issues a token, and Meross
 * limits how many an account may hold, so the token is kept and reused, and returned to Meross when the owner disconnects. Request and
 * response bodies, tokens and keys are never logged.
 */
export class MerossApi {
  private readonly base: string;
  private readonly trusted: (d: string) => boolean;
  constructor(private readonly deps: MerossApiDeps) {
    this.base = deps.baseUrl ?? BASE_URL;
    this.trusted = deps.trusted ?? isTrustedDomain;
  }

  /** Signs in. A region redirect is followed once; `mfaCode` only after `needs_code`. */
  login(email: string, password: string, installId: string, mfaCode?: string): Promise<MerossLogin> {
    return this.loginAt(this.base, email, password, installId, mfaCode, false);
  }

  private async loginAt(base: string, email: string, password: string, installId: string, mfaCode: string | undefined, redirected: boolean): Promise<MerossLogin> {
    const params: Record<string, unknown> = {
      email,
      password: md5(password),
      accountCountryCode: 'us',
      encryption: 1,
      agree: 0,
      mobileInfo: { deviceModel: 'iPhone', mobileOsVersion: 'iOS', mobileOs: 'iOS', uuid: installId, carrier: '' },
      ...(mfaCode ? { mfaCode } : {}),
    };
    const answer = await this.post(`${apiBase(base)}/v1/Auth/signIn`, params, null);
    if (!answer.ok) return { kind: 'failed', why: answer.why };
    const body = answer.body;
    const data = obj(body.data);
    const status = typeof body.apiStatus === 'number' ? body.apiStatus : undefined;
    switch (status) {
      case Status.OK: {
        const token = text(data, 'token');
        const key = text(data, 'key');
        const userId = text(data, 'userid');
        if (!token || !key || !userId) return { kind: 'failed', why: "Meross's answer wasn't what Edith expected" };
        const domain = text(data, 'domain') ?? base;
        const mqttDomain = text(data, 'mqttDomain') ?? domain;
        const creds: MerossCreds = { token, key, userId, email: text(data, 'email') ?? email, domain, mqttDomain, installId };
        if (!this.trusted(domain) || !this.trusted(mqttDomain)) {
          // The token is only ever sent to Meross: hand it back and keep nothing.
          log.warn('Meross', 'sign-in named an address outside meross.com');
          await this.logout({ ...creds, domain: base });
          return { kind: 'failed', why: UNTRUSTED };
        }
        return { kind: 'ok', creds };
      }
      case Status.REDIRECT_REGION: {
        const domain = text(data, 'domain');
        // The password goes to the address a response names, so it must be a Meross one (https, under meross.com).
        if (redirected || !domain) return { kind: 'failed', why: 'Meross sent Edith to another region twice' };
        if (!this.trusted(domain)) {
          log.warn('Meross', 'sign-in was sent to an address outside meross.com');
          return { kind: 'failed', why: UNTRUSTED };
        }
        return this.loginAt(domain, email, password, installId, mfaCode, true);
      }
      case Status.MFA_REQUIRED:
        return { kind: 'needs_code' };
      case Status.WRONG_MFA:
        return { kind: 'wrong_code' };
      case Status.WRONG_CREDENTIALS:
      case Status.UNKNOWN_ACCOUNT:
        return { kind: 'bad_login' };
      case Status.TOO_MANY_TOKENS:
        return { kind: 'too_many_tokens' };
      default:
        log.warn('Meross', `sign-in: apiStatus ${status}`);
        return { kind: 'failed', why: `Meross refused the sign-in (code ${status})` };
    }
  }

  /** The account's devices. */
  async devices(creds: MerossCreds): Promise<MerossReply<MerossDevice[]>> {
    const answer = await this.post(`${apiBase(creds.domain)}/v1/Device/devList`, {}, creds.token);
    if (!answer.ok) return { kind: 'failed', why: answer.why };
    const status = typeof answer.body.apiStatus === 'number' ? answer.body.apiStatus : undefined;
    if (status !== undefined && TOKEN_REJECTED.includes(status)) return { kind: 'rejected' };
    if (status !== Status.OK) {
      log.warn('Meross', `device list: apiStatus ${status}`);
      return { kind: 'failed', why: `Meross refused the device list (code ${status})` };
    }
    const list = Array.isArray(answer.body.data) ? answer.body.data : [];
    const devices: MerossDevice[] = [];
    for (const e of list) {
      const d = obj(e);
      const uuid = text(d, 'uuid');
      if (!uuid) continue;
      devices.push({ uuid, name: text(d, 'devName') ?? 'Meross device', type: text(d, 'deviceType') ?? '', online: int(d, 'onlineStatus') === 1 });
    }
    return { kind: 'ok', value: devices };
  }

  /** Gives the token back to Meross (it limits how many an account may hold). A failure is only logged. */
  async logout(creds: MerossCreds): Promise<void> {
    const a = await this.post(`${apiBase(creds.domain)}/v1/Profile/logout`, {}, creds.token);
    if (!a.ok) log.warn('Meross', `sign-out: ${a.why}`);
  }

  private async post(url: string, params: Record<string, unknown>, token: string | null): Promise<Raw> {
    const body = JSON.stringify(envelope(params, this.deps.now(), nonce(this.deps.random)));
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 6_000);
    try {
      const res = await this.deps.fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: token ? `Basic ${token}` : 'Basic',
          AppVersion: '2.0',
          vender: 'meross',
          AppType: 'MerossIOT',
          AppLanguage: 'EN',
          'User-Agent': 'Edith',
        },
        body,
        signal: ctl.signal,
      });
      if (res.status !== 200) return { ok: false, why: `Meross answered HTTP ${res.status}` };
      let json: unknown;
      try {
        json = JSON.parse(await res.text());
      } catch {
        return { ok: false, why: "Meross's answer wasn't what Edith expected" };
      }
      const o = obj(json);
      return o ? { ok: true, body: o } : { ok: false, why: "Meross's answer wasn't what Edith expected" };
    } catch (e) {
      // secureFetch refusing a redirect off meross.com lands here too: it says only the host.
      if (e instanceof Error && (e as { kind?: string }).kind === 'http_blocked') return { ok: false, why: UNTRUSTED };
      return { ok: false, why: "Meross can't be reached right now" };
    } finally {
      clearTimeout(timer);
    }
  }
}
