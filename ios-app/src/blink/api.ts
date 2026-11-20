import { CookieJar, splitSetCookie } from '../alexa/api';
import { bytesToBase64, readBytes } from '../core/bytes';
import { log } from '../core/log';
import { sha256Bytes } from '../core/sha256';
import { hostAllowed, resolveLocation } from '../core/secureFetch';
import { utf8Bytes } from '../core/md5';
import type { HttpFetch, HttpResponse } from '../ollama/client';
import { codeFrom, csrfToken, parseCommand, parseHomescreen, parseMedia, sinceText, snapshotPath, type BlinkCamera, type BlinkMedia } from './parse';

/**
 * Everything about how Blink is spoken to that changes when Blink changes something, in one place (C52): the app Blink thinks it is talking to.
 * Blink rejected a `hardware_id` that is not a UUID in July 2026, and may move these again.
 */
export const OAUTH = 'https://api.oauth.blink.com';
export const TIER_INFO = 'https://rest-prod.immedia-semi.com/api/v1/users/tier_info';
export const restOf = (tier: string) => `https://rest-${tier}.immedia-semi.com`;
export const CLIENT_ID = 'ios';
export const APP_VERSION = '50.1';
export const REDIRECT_URI = 'immedia-blink://applinks.blink.com/signin/callback';
/** What Blink's sign-in pages and token service expect to be talking to: its iPhone app. */
export const PAGE_AGENT = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.1 Mobile/15E148 Safari/604.1';
export const TOKEN_AGENT = 'Blink/2511191620 CFNetwork/3860.200.71 Darwin/25.1.0';
const PAGE_ACCEPT = 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8';
const ALLOW = ['blink.com', 'immedia-semi.com'];
const MAX_REDIRECTS = 5;
const MAX_PICTURE_BYTES = 4_000_000;
const UNREACHABLE = "Blink can't be reached right now";

export interface BlinkTokens {
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
}
export interface BlinkTier {
  tier: string;
  accountId: string;
}

/**
 * A sign-in that has started: Blink's page was loaded, its cookies and CSRF token are held here, and the PKCE verifier the token exchange needs. It lives
 * in memory only, until a code is typed or the sign-in ends.
 */
export interface BlinkSignInSession {
  jar: CookieJar;
  csrf: string;
  verifier: string;
  hardwareId: string;
}

export type BlinkStart = { kind: 'ok'; session: BlinkSignInSession } | { kind: 'failed'; why: string; status: number };
export type BlinkLogin = { kind: 'ok'; tokens: BlinkTokens } | { kind: 'needs_code' } | { kind: 'wrong_code' } | { kind: 'bad_login' } | { kind: 'failed'; why: string };
export type BlinkRefresh = { kind: 'ok'; tokens: BlinkTokens } | { kind: 'rejected' } | { kind: 'failed'; why: string };
export type BlinkReply<T> =
  | { kind: 'ok'; value: T }
  /** HTTP 401: the access token is no good (expired): refresh and ask again. */
  | { kind: 'unauthorized' }
  /** HTTP 409: the sync module is busy with an earlier command; asking again in a few seconds usually works. */
  | { kind: 'busy'; why: string }
  | { kind: 'failed'; why: string };

type Raw = { ok: true; status: number; text: string; location: string | null; type: string; server: string } | { ok: false; why: string };
type Init = { method: string; headers?: Record<string, string>; body?: string };

const base64Url = (b: Uint8Array) => bytesToBase64(b).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const enc = encodeURIComponent;
const form = (pairs: [string, string][]) => pairs.map(([k, v]) => `${enc(k)}=${enc(v)}`).join('&');
const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null);
const strOf = (o: Record<string, unknown> | null, k: string): string | null => {
  const v = o?.[k];
  return typeof v === 'string' && v ? v : typeof v === 'number' ? String(v) : null;
};
const parseJson = (t: string): unknown => {
  try {
    return JSON.parse(t);
  } catch {
    return null;
  }
};

export interface BlinkApiDeps {
  /** The raw client: this api checks every address itself (HTTPS, Blink's hosts) because it follows redirects by hand and must see the sign-in's last one. */
  fetch: HttpFetch;
  randomBytes: (n: number) => Uint8Array;
  timeoutMs?: number;
}

/**
 * Blink's own (unofficial) service, as the maintained `blinkpy` uses it (C29 ported): a browser-style sign-in against `api.oauth.blink.com` (the sign-in
 * page's CSRF token, the password, a 2FA code, then an authorization code traded for tokens with PKCE), the account's tier, the homescreen, snapshots by
 * command and the media list. Stateless apart from `BlinkSignInSession`: `BlinkService` keeps the tokens. Passwords, codes, tokens and answers are
 * never logged; only each sign-in step's status code is.
 */
export class BlinkApi {
  private readonly timeoutMs: number;
  constructor(private readonly deps: BlinkApiDeps) {
    this.timeoutMs = deps.timeoutMs ?? 20_000;
  }

  private guard(url: string): boolean {
    const m = /^https:\/\/([^/:?#]+)/i.exec(url);
    return !!m && hostAllowed(m[1], ALLOW);
  }

  /**
   * One request. `follow`: redirects are followed by hand (so cookies set along the way are kept in `jar`); otherwise the first answer comes back, with its
   * `Location`, even when that is the app's own address (`immedia-blink://…`), which is never requested.
   */
  private async send(url: string, init: Init, opts: { jar?: CookieJar; follow?: boolean; credentials?: boolean } = {}): Promise<Raw> {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), this.timeoutMs);
    let current = url;
    let step = init;
    try {
      for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
        if (!this.guard(current)) return { ok: false, why: "Blink sent Edith to a page that isn't Blink's, so she stopped" };
        const headers = { ...(step.headers ?? {}), ...(opts.jar && opts.jar.header() ? { Cookie: opts.jar.header() } : {}) };
        const res: HttpResponse = await this.deps.fetch(current, { ...step, headers, signal: ctl.signal, redirect: 'manual', credentials: 'omit' });
        opts.jar?.addSetCookies(splitSetCookie(res.headers?.get('set-cookie')));
        const location = res.status >= 300 && res.status < 400 ? (res.headers?.get('location') ?? null) : null;
        if (location && opts.follow) {
          current = resolveLocation(location, current);
          step = { method: 'GET', headers: step.headers };
          continue;
        }
        return {
          ok: true,
          status: res.status,
          text: await res.text(),
          location,
          type: (res.headers?.get('content-type') ?? '').split(';')[0].trim().toLowerCase(),
          server: res.headers?.get('server') ?? '',
        };
      }
      return { ok: false, why: 'Blink redirected Edith too many times' };
    } catch {
      return { ok: false, why: UNREACHABLE };
    } finally {
      clearTimeout(timer);
    }
  }

  // --- sign-in -----------------------------------------------------------------------------------------------------------------
  /** Loads Blink's sign-in page the way its phone app does, to get its cookies and CSRF token. */
  async begin(hardwareId: string): Promise<BlinkStart> {
    const verifier = base64Url(this.deps.randomBytes(32));
    const challenge = base64Url(sha256Bytes(utf8Bytes(verifier)));
    const jar = new CookieJar();
    const q = form([
      ['app_brand', 'blink'],
      ['app_version', APP_VERSION],
      ['client_id', CLIENT_ID],
      ['code_challenge', challenge],
      ['code_challenge_method', 'S256'],
      ['device_brand', 'Apple'],
      ['device_model', 'iPhone16,1'],
      ['device_os_version', '26.1'],
      ['hardware_id', hardwareId],
      ['redirect_uri', REDIRECT_URI],
      ['response_type', 'code'],
      ['scope', 'client'],
    ]);
    const pageHeaders = { 'User-Agent': PAGE_AGENT, Accept: PAGE_ACCEPT, 'Accept-Language': 'en-US,en;q=0.9' };
    const first = await this.send(`${OAUTH}/oauth/v2/authorize?${q}`, { method: 'GET', headers: pageHeaders }, { jar, follow: true });
    if (!first.ok) return { kind: 'failed', why: first.why, status: 0 };
    log.info('Blink', `authorize ${first.status}`);
    if (first.status !== 200) return this.refused('the first step (authorize)', first);
    const page = await this.send(`${OAUTH}/oauth/v2/signin`, { method: 'GET', headers: pageHeaders }, { jar, follow: true });
    if (!page.ok) return { kind: 'failed', why: page.why, status: 0 };
    log.info('Blink', `signin page ${page.status}`);
    if (page.status !== 200) return this.refused('the second step (the sign-in page)', page);
    const csrf = csrfToken(page.text);
    if (!csrf) return { kind: 'failed', why: "Blink's sign-in page wasn't what Edith expected (Blink may have changed it)", status: 200 };
    return { kind: 'ok', session: { jar, csrf, verifier, hardwareId } };
  }

  /** A page Blink would not give: names the step and the status (never the page's own words: they might hold anything). */
  private refused(step: string, r: Extract<Raw, { ok: true }>): BlinkStart {
    const from = r.server ? `, from ${r.server.slice(0, 40)}` : '';
    if (r.status === 406) {
      // The owner's VPN: Blink's sign-in host refused its addresses (C29 As built). Not retried: JavaScript can't force IPv4.
      return { kind: 'failed', why: `Blink refused the connection (HTTP 406) at ${step}${from}. If you use a VPN, turn it off and try again`, status: 406 };
    }
    return { kind: 'failed', why: `Blink's sign-in page answered HTTP ${r.status} at ${step}${from}`, status: r.status };
  }

  private formHeaders(): Record<string, string> {
    return { 'User-Agent': PAGE_AGENT, Accept: '*/*', Origin: OAUTH, Referer: `${OAUTH}/oauth/v2/signin`, 'Content-Type': 'application/x-www-form-urlencoded' };
  }

  /** The password step. Success, or a code to type next. */
  async signIn(session: BlinkSignInSession, email: string, password: string): Promise<BlinkLogin> {
    const body = form([
      ['username', email],
      ['password', password],
      ['csrf-token', session.csrf],
    ]);
    const r = await this.send(`${OAUTH}/oauth/v2/signin`, { method: 'POST', headers: this.formHeaders(), body }, { jar: session.jar });
    if (!r.ok) return { kind: 'failed', why: r.why };
    log.info('Blink', `signin ${r.status}`);
    if (r.status === 412) return { kind: 'needs_code' };
    // 202 with the code methods listed is the same thing.
    if (r.status === 202 && wantsCode(r.text)) return { kind: 'needs_code' };
    if (r.status >= 300 && r.status < 400) return this.finish(session);
    if (r.status === 400 || r.status === 401 || r.status === 403 || r.status === 200) return { kind: 'bad_login' };
    if (r.status === 429) return { kind: 'failed', why: 'Blink says to slow down; try again in a few minutes' };
    return { kind: 'failed', why: `Blink's sign-in answered HTTP ${r.status}` };
  }

  /** The code step: `code` is what Blink sent. */
  async verify(session: BlinkSignInSession, code: string): Promise<BlinkLogin> {
    const body = form([
      ['2fa_code', code],
      ['csrf-token', session.csrf],
      ['remember_me', 'false'],
    ]);
    const r = await this.send(`${OAUTH}/oauth/v2/2fa/verify`, { method: 'POST', headers: this.formHeaders(), body }, { jar: session.jar });
    if (!r.ok) return { kind: 'failed', why: r.why };
    log.info('Blink', `verify ${r.status}`);
    const done = r.status === 201 && strOf(obj(parseJson(r.text)), 'status') === 'auth-completed';
    if (done) return this.finish(session);
    if (r.status === 429) return { kind: 'failed', why: 'Blink says to slow down; try again in a few minutes' };
    if (r.status >= 400 && r.status < 500) return { kind: 'wrong_code' };
    return { kind: 'failed', why: `Blink's code check answered HTTP ${r.status}` };
  }

  /** After the password (and code): Blink sends the sign-in to its app's address with a one-time code, which is traded for tokens. */
  private async finish(session: BlinkSignInSession): Promise<BlinkLogin> {
    const authorize = await this.send(
      `${OAUTH}/oauth/v2/authorize`,
      { method: 'GET', headers: { 'User-Agent': PAGE_AGENT, Accept: '*/*', Referer: `${OAUTH}/oauth/v2/signin` } },
      { jar: session.jar },
    );
    if (!authorize.ok) return { kind: 'failed', why: authorize.why };
    log.info('Blink', `final authorize ${authorize.status}`);
    const code = authorize.status >= 300 && authorize.status < 400 ? codeFrom(authorize.location) : null;
    if (!code) return { kind: 'failed', why: 'Blink finished the sign-in without a code' };
    const t = await this.token([
      ['app_brand', 'blink'],
      ['client_id', CLIENT_ID],
      ['code', code],
      ['code_verifier', session.verifier],
      ['grant_type', 'authorization_code'],
      ['hardware_id', session.hardwareId],
      ['redirect_uri', REDIRECT_URI],
      ['scope', 'client'],
    ]);
    if (t.kind === 'ok') return t;
    if (t.kind === 'rejected') return { kind: 'failed', why: "Blink wouldn't trade the sign-in for a session" };
    return { kind: 'failed', why: t.why };
  }

  /** Trades the refresh token for new tokens. */
  refresh(refreshToken: string, hardwareId: string): Promise<BlinkRefresh> {
    return this.token([
      ['grant_type', 'refresh_token'],
      ['refresh_token', refreshToken],
      ['client_id', CLIENT_ID],
      ['scope', 'client'],
      ['hardware_id', hardwareId],
    ]);
  }

  private async token(pairs: [string, string][]): Promise<BlinkRefresh> {
    const r = await this.send(`${OAUTH}/oauth/token`, { method: 'POST', headers: { 'User-Agent': TOKEN_AGENT, Accept: '*/*', 'Content-Type': 'application/x-www-form-urlencoded' }, body: form(pairs) });
    if (!r.ok) return { kind: 'failed', why: r.why };
    log.info('Blink', `token ${r.status}`);
    if (r.status === 400 || r.status === 401 || r.status === 403) return { kind: 'rejected' };
    if (r.status === 429) return { kind: 'failed', why: 'Blink says to slow down; try again in a few minutes' };
    if (r.status !== 200) return { kind: 'failed', why: `Blink's sign-in service answered HTTP ${r.status}` };
    const o = obj(parseJson(r.text));
    const access = strOf(o, 'access_token');
    const refresh = strOf(o, 'refresh_token');
    if (!access || !refresh) return { kind: 'failed', why: "Blink's sign-in service didn't return a session" };
    return { kind: 'ok', tokens: { accessToken: access, refreshToken: refresh, expiresInSeconds: typeof o?.expires_in === 'number' ? o.expires_in : 3600 } };
  }

  // --- the account -------------------------------------------------------------------------------------------------------------
  private async call<T>(url: string, method: 'GET' | 'POST', token: string, what: string, parse: (body: unknown) => T | null): Promise<BlinkReply<T>> {
    const r = await this.send(url, { method, headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' } });
    if (!r.ok) return { kind: 'failed', why: r.why };
    if (r.status === 401) return { kind: 'unauthorized' };
    if (r.status === 409) return busy(r.text);
    if (r.status === 429) return { kind: 'failed', why: 'Blink says to slow down; try again in a few minutes' };
    if (r.status < 200 || r.status >= 300) {
      const said = strOf(obj(parseJson(r.text)), 'message')?.slice(0, 100);
      log.warn('Blink', `HTTP ${r.status} for ${what}${said ? `: ${said}` : ''}`);
      return { kind: 'failed', why: `Blink answered HTTP ${r.status} for ${what}` };
    }
    const value = parse(parseJson(r.text));
    return value === null ? { kind: 'failed', why: `Blink's answer for ${what} wasn't what Edith expected` } : { kind: 'ok', value };
  }

  /** Which account and region the tokens belong to. */
  tier(token: string): Promise<BlinkReply<BlinkTier>> {
    return this.call(TIER_INFO, 'GET', token, 'the account', (b) => {
      const o = obj(b);
      const tier = strOf(o, 'tier');
      const accountId = strOf(o, 'account_id');
      return tier && accountId ? { tier, accountId } : null;
    });
  }

  cameras(token: string, where: BlinkTier): Promise<BlinkReply<BlinkCamera[]>> {
    return this.call(`${restOf(where.tier)}/api/v3/accounts/${where.accountId}/homescreen`, 'GET', token, 'the camera list', parseHomescreen);
  }

  /** Asks a camera for a new snapshot. The answer is the command to wait for (null: Blink named none). */
  requestSnapshot(token: string, where: BlinkTier, camera: BlinkCamera): Promise<BlinkReply<{ id: string; networkId: string | null } | 'none'>> {
    return this.call(`${restOf(where.tier)}${snapshotPath(where.accountId, camera)}`, 'POST', token, 'the snapshot', (b) => parseCommand(b) ?? 'none');
  }

  /** Whether the command Blink started has finished. */
  commandDone(token: string, where: BlinkTier, network: string, commandId: string): Promise<BlinkReply<boolean>> {
    return this.call(`${restOf(where.tier)}/network/${network}/command/${commandId}`, 'GET', token, 'the snapshot', (b) => obj(b)?.complete === true);
  }

  /** A camera's picture, from the address Blink gave for it. */
  async picture(token: string, where: BlinkTier, path: string): Promise<BlinkReply<Uint8Array>> {
    const url = restOf(where.tier) + path;
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 30_000);
    try {
      if (!this.guard(url)) return { kind: 'failed', why: "Blink gave an address Edith can't use" };
      const res = await this.deps.fetch(url, { method: 'GET', headers: { Authorization: `Bearer ${token}`, Accept: 'image/*' }, signal: ctl.signal, credentials: 'omit' });
      if (res.status === 401) return { kind: 'unauthorized' };
      if (res.status === 409) return busy(await res.text());
      if (res.status < 200 || res.status >= 300) return { kind: 'failed', why: `Blink answered HTTP ${res.status} for the picture` };
      if (!(res.headers?.get('content-type') ?? '').toLowerCase().startsWith('image/')) return { kind: 'failed', why: "Blink didn't send the picture" };
      const bytes = await readBytes(res.body, MAX_PICTURE_BYTES);
      return bytes.length === 0 ? { kind: 'failed', why: 'Blink sent an empty picture' } : { kind: 'ok', value: bytes };
    } catch {
      return { kind: 'failed', why: UNREACHABLE };
    } finally {
      clearTimeout(timer);
    }
  }

  /** One page of the media list (pages start at 1), newest first, since `sinceMs`. */
  media(token: string, where: BlinkTier, sinceMs: number, page: number): Promise<BlinkReply<BlinkMedia[]>> {
    const path = `/api/v1/accounts/${where.accountId}/media/changed?since=${enc(sinceText(sinceMs))}&page=${page}`;
    return this.call(`${restOf(where.tier)}${path}`, 'GET', token, 'the clips', parseMedia);
  }
}

/** 409 also says the sync module is offline, which waiting won't change; any other 409 is "busy". */
function busy(body: string): { kind: 'busy'; why: string } | { kind: 'failed'; why: string } {
  const message = strOf(obj(parseJson(body)), 'message') ?? '';
  if (/offline/i.test(message)) return { kind: 'failed', why: 'Blink says the sync module is offline. Check that it is plugged in and online in the Blink app' };
  return { kind: 'busy', why: (message || 'Blink is busy').slice(0, 120) };
}

function wantsCode(text: string): boolean {
  const o = obj(parseJson(text));
  if (!o) return false;
  return ['tsv_state', 'tsv_methods', 'next_time_in_secs'].some((k) => o[k] !== undefined && o[k] !== null && o[k] !== false && o[k] !== 'false');
}
