import { bytesToBase64 } from '../core/bytes';
import { utf8Bytes } from '../core/md5';
import { hostAllowed, resolveLocation } from '../core/secureFetch';
import { sha256Bytes } from '../core/sha256';
import type { HttpFetch } from '../ollama/client';
import { AMAZON_SITES, type AmazonSite } from './parse';

/** Where an Amazon site's three services live. Tests point all three at one pretend Amazon. */
export interface AlexaEndpoints {
  api: string;
  www: string;
  alexa: string;
}
export const endpointsOf = (site: AmazonSite): AlexaEndpoints => ({ api: `https://api.${site.site}`, www: `https://www.${site.site}`, alexa: `https://alexa.${site.site}` });

/** What the owner's sign-in page needs to start and Edith needs to finish: the page, and the secrets that match it. */
export interface AlexaSignInStart {
  site: AmazonSite;
  /** Amazon's sign-in page, as the Alexa app opens it. */
  url: string;
  deviceId: string;
  /** PKCE: the page was given the hash of this, and registration must show the original. */
  verifier: string;
  /** The two cookies the Alexa app's sign-in carries; made up here, as `alexa-cookie2` does. */
  frc: string;
  mapMd: string;
}
export interface AlexaRegistration {
  refreshToken: string;
  deviceSerial: string;
}
/** Alexa's cookies for one Amazon site, and the csrf token its API wants with them. */
export interface AlexaSession {
  cookie: string;
  csrf: string;
}
/** `rejected`: Amazon no longer accepts what was sent: the refresh token (sign in again), or the cookie (get new ones). */
export type AlexaReply<T> = { kind: 'ok'; value: T } | { kind: 'rejected' } | { kind: 'failed'; why: string };

export interface AlexaApiDeps {
  /** Plain `httpFetch`: this client follows redirects itself to keep the cookies set along the way, and checks every address it is sent to. */
  fetch: HttpFetch;
  randomBytes: (n: number) => Uint8Array;
  endpointsFor?: (site: AmazonSite) => AlexaEndpoints;
  /** Hosts a request may go to (suffixes). Default: every Amazon site Alexa supports. Tests use a made-up one. */
  allowHosts?: string[];
}

const CODE_PARAM = 'openid.oa2.authorization_code';
const APP_NAME = 'Edith';
const APP_VERSION = '2.2.651540.0';
/** The Alexa app for iPhone's device type: what Amazon expects of a registered "device" (as `alexa-cookie2`). */
const DEVICE_TYPE = 'A2IVLV5VM2W81';
/** `#A2IVLV5VM2W81` in hex, which ends every device id. */
const DEVICE_TYPE_SUFFIX = '23413249564c5635564d32573831';
const APP_AGENT = 'AmazonWebView/Amazon Alexa/2.2.651540.0/iOS/18.3.1/iPhone';
const OFFICIAL_AGENT = 'AppleWebKit PitanguiBridge/2.2.595606.0-[HARDWARE=iPhone14_7][SOFTWARE=17.4.1][DEVICE=iPhone]';
const WEB_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36';
const CSRF_PATHS = ['/api/language', '/spa/index.html', '/api/devices-v2/device?cached=false', '/templates/oobe/d-device-pick.handlebars', '/api/strings'];
const CALL_S = 20;
const MAX_REDIRECTS = 5;

/** Alexa's device list, as `alexa-remote2` asks for it (the whole query, as proven on Android). */
export const ENDPOINTS_QUERY = `query Endpoints {
  endpoints {
    items {
      endpointId
      id
      friendlyName
      displayCategories {
        primary {
          value
        }
      }
      legacyIdentifiers {
          dmsIdentifier {
              deviceType {
                  type
                  value {
                      text
                  }
              }
              deviceSerialNumber {
                  type
                  value {
                      text
                  }
              }
          }
      }
      legacyAppliance {
        applianceId
        applianceTypes
        endpointTypeId
        friendlyName
        friendlyDescription
        manufacturerName
        connectedVia
        modelName
        entityId
        actions
        mergedApplianceIds
        capabilities
        applianceNetworkState
        version
        isEnabled
        customerDefinedDeviceType
        customerPreference
        alexaDeviceIdentifierList
        aliases
        driverIdentity
        additionalApplianceDetails
        isConsentRequired
        applianceKey
        appliancePairs
        deduplicatedPairs
        entityPairs
        deduplicatedAliasesByEntityId
        relations
      }
      serialNumber {
        value {
          text
        }
      }
      enablement
      model {
        value {
          text
        }
      }
      manufacturer {
        value {
          text
        }
      }
      features {
        name
        operations {
          name
        }
      }
    }
  }
}
`;

/** The cookies of one conversation with Amazon: names and values, in the order they were first set. */
export class CookieJar {
  private readonly values = new Map<string, string>();
  get(name: string): string | undefined {
    return this.values.get(name);
  }
  put(name: string, value: string) {
    this.values.set(name, value);
  }
  /** A header holds only printable ASCII: a cookie with anything else is left out, because a request with one is refused outright. */
  entries(): [string, string][] {
    const ok = (t: string) => [...t].every((c) => c.charCodeAt(0) >= 0x20 && c.charCodeAt(0) <= 0x7e);
    return [...this.values].filter(([k, v]) => k.length > 0 && ok(k) && ok(v));
  }
  /** A `Cookie:` header's text (`a=1; b=2`), cookies separated by ";" and, in some web views, line breaks. */
  addHeader(header: string) {
    for (const part of header.split(/[;\n\r]/)) {
      const i = part.indexOf('=');
      if (i > 0) this.values.set(part.slice(0, i).trim(), part.slice(i + 1).trim());
    }
  }
  /** `Set-Cookie` lines: only each one's `name=value`. */
  addSetCookies(lines: string[]) {
    for (const line of lines) {
      const first = line.split(';')[0];
      const i = first.indexOf('=');
      if (i <= 0) continue;
      const value = first.slice(i + 1).trim();
      if (value === '""') continue;
      this.values.set(first.slice(0, i).trim(), value);
    }
  }
  header(): string {
    return this.entries()
      .map(([k, v]) => `${k}=${v}`)
      .join('; ');
  }
}

/** A `Set-Cookie` response header as a list: iOS joins several into one string with ", ", so split where a new `name=` begins (not inside an Expires date). */
export function splitSetCookie(header: string | null | undefined): string[] {
  if (!header) return [];
  return header.split(/,(?=\s*[A-Za-z0-9_.-]+=)/).map((s) => s.trim());
}

type Raw = { ok: true; code: number; body: string; signedOut: boolean } | { ok: false; why: string };
const obj = (v: unknown): Record<string, unknown> => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const str = (v: unknown): string | undefined => (typeof v === 'string' ? v : undefined);
const parse = (t: string): Record<string, unknown> => {
  try {
    return obj(JSON.parse(t));
  } catch {
    return {};
  }
};

const hex = (bytes: Uint8Array) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
const base64Url = (bytes: Uint8Array) => bytesToBase64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const enc = encodeURIComponent;

/**
 * Amazon's side of Alexa (C30, C54), as `alexa-cookie2` 5.0.6 and `alexa-remote2` 8.1.1 (the libraries Jarvis uses) do it: the sign-in page,
 * registering Edith as an Alexa app "device" to get a refresh token, trading that token for the cookies and csrf token Alexa's own web API takes,
 * then the smart-home device list, states and commands. Unofficial: Amazon can change any of it. Tokens, cookies, passwords and response bodies are
 * never logged.
 */
export class AlexaApi {
  private readonly endpointsFor: (s: AmazonSite) => AlexaEndpoints;
  private readonly allow: string[];
  constructor(private readonly deps: AlexaApiDeps) {
    this.endpointsFor = deps.endpointsFor ?? endpointsOf;
    this.allow = deps.allowHosts ?? AMAZON_SITES.map((s) => s.site);
  }

  // ---- Sign-in ----

  /** A new sign-in: Amazon's page for the Alexa app's "device authorization", with a fresh device id and PKCE pair. */
  start(site: AmazonSite): AlexaSignInStart {
    const deviceId = hex(utf8Bytes(hex(this.deps.randomBytes(16)).toUpperCase())) + DEVICE_TYPE_SUFFIX;
    const verifier = base64Url(this.deps.randomBytes(32));
    const challenge = base64Url(sha256Bytes(utf8Bytes(verifier)));
    const tail = site.site.slice(site.site.lastIndexOf('.') + 1);
    const handle = tail === 'com' ? '' : `_${tail}`;
    const host = `www.${site.site}`;
    const url =
      `https://${host}/ap/signin` +
      `?openid.return_to=${enc(`https://${host}/ap/maplanding`)}` +
      `&openid.assoc_handle=amzn_dp_project_dee_ios${handle}` +
      `&openid.identity=${enc('http://specs.openid.net/auth/2.0/identifier_select')}` +
      `&pageId=amzn_dp_project_dee_ios${handle}&accountStatusPolicy=P1` +
      `&openid.claimed_id=${enc('http://specs.openid.net/auth/2.0/identifier_select')}` +
      `&openid.mode=checkid_setup` +
      `&openid.ns.oa2=${enc(`http://${host}/ap/ext/oauth/2`)}` +
      `&openid.oa2.client_id=${enc(`device:${deviceId}`)}` +
      `&openid.ns.pape=${enc('http://specs.openid.net/extensions/pape/1.0')}` +
      `&openid.oa2.response_type=code&openid.ns=${enc('http://specs.openid.net/auth/2.0')}` +
      `&openid.pape.max_auth_age=0&openid.oa2.scope=device_auth_access` +
      `&openid.oa2.code_challenge_method=S256&openid.oa2.code_challenge=${challenge}` +
      `&language=${site.language}`;
    const frc = bytesToBase64(this.deps.randomBytes(313));
    const mapMd = bytesToBase64(
      utf8Bytes(`{"device_user_dictionary":[],"device_registration_data":{"software_version":"1"},"app_identifier":{"app_version":"${APP_VERSION}","bundle_id":"com.amazon.echo"}}`),
    );
    return { site, url, deviceId, verifier, frc, mapMd };
  }

  /** The authorization code in the address the sign-in ended on (`…/ap/maplanding?openid.oa2.authorization_code=…`), or null. */
  authorizationCode(address: string): string | null {
    const t = address.trim();
    if (!t.includes('/ap/maplanding?')) return null;
    const m = new RegExp(`[?&]${CODE_PARAM.replace(/\./g, '\\.')}=([^&#\\s]+)`).exec(t);
    if (!m) return null;
    try {
      return decodeURIComponent(m[1]) || null;
    } catch {
      return m[1] || null;
    }
  }
  isFinish(address: string): boolean {
    return this.authorizationCode(address) !== null;
  }

  /**
   * Registers Edith as an Alexa app "device" with the authorization code the sign-in gave: Amazon answers with the refresh token (the only long-lived
   * thing kept) and a few cookies, which aren't kept. `cookies` are what a web view holds for the site after signing in; empty when none could be read.
   */
  async register(start: AlexaSignInStart, code: string, cookies: string): Promise<AlexaReply<AlexaRegistration>> {
    const e = this.endpointsFor(start.site);
    const serial = hex(this.deps.randomBytes(16));
    const jar = new CookieJar();
    jar.addHeader(cookies);
    jar.put('frc', start.frc);
    jar.put('map-md', start.mapMd);
    const body = {
      requested_extensions: ['device_info', 'customer_info'],
      cookies: { website_cookies: jar.entries().map(([Name, Value]) => ({ Value, Name })), domain: `.${start.site.site}` },
      registration_data: {
        domain: 'Device',
        app_version: APP_VERSION,
        device_type: DEVICE_TYPE,
        device_name: `%FIRST_NAME%'s%DUPE_STRATEGY_1ST%${APP_NAME}`,
        os_version: '18.3.1',
        device_serial: serial,
        device_model: 'iPhone',
        app_name: APP_NAME,
        software_version: '1',
      },
      auth_data: { client_id: start.deviceId, authorization_code: code, code_verifier: start.verifier, code_algorithm: 'SHA-256', client_domain: 'DeviceLegacy' },
      user_context_map: { frc: start.frc },
      requested_token_type: ['bearer', 'mac_dms', 'website_cookies'],
    };
    const r = await this.send(`${e.api}/auth/register`, {
      method: 'POST',
      headers: { ...this.appHeaders(start.site, `api.${start.site.site}`), Cookie: jar.header(), 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!r.ok) return { kind: 'failed', why: r.why };
    const token = str(obj(obj(obj(obj(parse(r.body).response).success).tokens).bearer).refresh_token);
    if (r.code >= 200 && r.code < 300 && token) return { kind: 'ok', value: { refreshToken: token, deviceSerial: serial } };
    if (r.code >= 400 && r.code < 500) return { kind: 'rejected' };
    return { kind: 'failed', why: `Amazon answered HTTP ${r.code} when Edith registered` };
  }

  // ---- Cookies for Alexa's web API ----

  /**
   * Alexa's cookies and csrf token for `site`, from the refresh token: Amazon's token exchange gives the cookies, and a page on `alexa.<site>` gives the
   * csrf token. `rejected`: the refresh token is no good any more.
   */
  async session(site: AmazonSite, refreshToken: string): Promise<AlexaReply<AlexaSession>> {
    const e = this.endpointsFor(site);
    const form = new URLSearchParamsLite()
      .add('di.os.name', 'iOS')
      .add('app_version', APP_VERSION)
      .add('domain', `.${site.site}`)
      .add('source_token', refreshToken)
      .add('requested_token_type', 'auth_cookies')
      .add('source_token_type', 'refresh_token')
      .add('di.hw.version', 'iPhone')
      .add('di.sdk.version', '6.12.4')
      .add('app_name', APP_NAME)
      .add('di.os.version', '16.6');
    const jar = new CookieJar();
    const exchange = await this.send(
      `${e.www}/ap/exchangetoken/cookies`,
      { method: 'POST', headers: { ...this.appHeaders(site, `api.${site.site}`), Accept: '*/*', 'Content-Type': 'application/x-www-form-urlencoded' }, body: form.toString() },
      jar,
    );
    if (!exchange.ok) return { kind: 'failed', why: exchange.why };
    if (exchange.code >= 400 && exchange.code < 500) return { kind: 'rejected' };
    const list = obj(obj(obj(parse(exchange.body).response).tokens).cookies)[`.${site.site}`];
    if (exchange.code < 200 || exchange.code >= 300 || !Array.isArray(list)) return { kind: 'failed', why: `Amazon gave no cookies (HTTP ${exchange.code})` };
    for (const c of list) {
      const o = obj(c);
      const n = str(o.Name);
      const v = str(o.Value);
      if (n && v) jar.put(n, v);
    }
    // The csrf token comes as a cookie from one of a few pages on the Alexa host.
    for (const path of CSRF_PATHS) {
      const page = await this.send(
        `${e.alexa}${path}`,
        { method: 'GET', headers: { 'User-Agent': WEB_AGENT, Referer: `${e.alexa}/spa/index.html`, Origin: e.alexa, Accept: '*/*', Cookie: jar.header() } },
        jar,
      );
      if (!page.ok) return { kind: 'failed', why: page.why };
      const csrf = jar.get('csrf');
      if (csrf) return { kind: 'ok', value: { cookie: jar.header(), csrf } };
    }
    return { kind: 'failed', why: 'Alexa gave no csrf token' };
  }

  // ---- Smart home ----

  /** The smart-home endpoints on the account (Alexa's device service). */
  async endpoints(site: AmazonSite, session: AlexaSession): Promise<AlexaReply<unknown[]>> {
    const r = await this.alexaCall(site, session, '/nexus/v1/graphql', 'POST', JSON.stringify({ query: ENDPOINTS_QUERY }), 40);
    if (r.kind !== 'ok') return r;
    const items = obj(obj(parse(r.value).data).endpoints).items;
    return Array.isArray(items) ? { kind: 'ok', value: items } : { kind: 'failed', why: "Alexa's device list wasn't what Edith expected" };
  }

  /** The state of the devices named in `stateRequests` (see `stateRequests` in parse.ts). */
  async states(site: AmazonSite, session: AlexaSession, stateRequests: unknown[], timeoutS: number): Promise<AlexaReply<unknown>> {
    const r = await this.alexaCall(site, session, '/api/phoenix/state', 'POST', JSON.stringify({ stateRequests }), timeoutS);
    return r.kind === 'ok' ? { kind: 'ok', value: parse(r.value) } : r;
  }

  /** Sends one control request (see `controlParameters`) to the device with `entityId`. Amazon answers 200 even when it refuses, so read `controlProblem` on the answer. */
  async control(site: AmazonSite, session: AlexaSession, entityId: string, parameters: Record<string, unknown>, timeoutS: number): Promise<AlexaReply<unknown>> {
    const body = JSON.stringify({ controlRequests: [{ entityId, entityType: 'APPLIANCE', parameters }] });
    const r = await this.alexaCall(site, session, '/api/phoenix/state', 'PUT', body, timeoutS);
    return r.kind === 'ok' ? { kind: 'ok', value: parse(r.value) } : r;
  }

  private async alexaCall(site: AmazonSite, session: AlexaSession, path: string, method: 'GET' | 'POST' | 'PUT', body: string | undefined, timeoutS: number): Promise<AlexaReply<string>> {
    const e = this.endpointsFor(site);
    const r = await this.send(
      `${e.alexa}${path}`,
      {
        method,
        headers: {
          'User-Agent': `${OFFICIAL_AGENT} Edith`,
          Accept: 'application/json; charset=utf-8',
          'Accept-Language': site.language.replace('_', '-'),
          Referer: `${e.alexa}/spa/index.html`,
          Origin: e.alexa,
          csrf: session.csrf,
          Cookie: session.cookie,
          ...(body ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
        },
        body,
      },
      undefined,
      timeoutS,
    );
    if (!r.ok) return { kind: 'failed', why: r.why };
    // Expired cookies: Amazon answers 401/403, or sends the caller to its sign-in page.
    if (r.code === 401 || r.code === 403 || r.signedOut) return { kind: 'rejected' };
    if (r.code >= 200 && r.code < 300) return { kind: 'ok', value: r.body };
    return { kind: 'failed', why: `Amazon answered HTTP ${r.code}` };
  }

  // ---- HTTP ----

  private guard(url: string): boolean {
    const m = /^https:\/\/([^/:?#]+)/i.exec(url);
    return !!m && hostAllowed(m[1], this.allow);
  }

  /**
   * One request, following redirects by hand so the cookies set along the way are kept in `jar`. Every address is checked: HTTPS, and Amazon's. A
   * platform that follows redirects itself (so we can't see the hops) is caught at the end: a final address on Amazon's sign-in page means the cookies are no good.
   */
  private async send(first: string, init: { method: string; headers?: Record<string, string>; body?: string }, jar?: CookieJar, timeoutS = CALL_S): Promise<Raw> {
    let url = first;
    let current = init;
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), timeoutS * 1000);
    try {
      for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
        if (!this.guard(url)) return { ok: false, why: "Amazon sent Edith to a page that isn't Amazon's, so she stopped" };
        const res = await this.deps.fetch(url, { ...current, signal: ctl.signal, redirect: 'manual' });
        jar?.addSetCookies(splitSetCookie(res.headers?.get('set-cookie')));
        if (res.url && res.url !== url && /\/ap\/signin/.test(res.url)) return { ok: true, code: 401, body: '', signedOut: true };
        const location = res.status >= 300 && res.status < 400 ? res.headers?.get('location') : null;
        if (location) {
          const next = resolveLocation(location, url);
          // A redirect to Amazon's sign-in page means the cookies are no good.
          if (/\/ap\/signin/.test(next.split('?')[0])) return { ok: true, code: 401, body: '', signedOut: true };
          if (!/^https:/i.test(next)) return { ok: false, why: "Amazon sent Edith to a page that isn't secure" };
          url = next;
          current = { method: 'GET', headers: { ...(current.headers ?? {}), ...(jar ? { Cookie: jar.header() } : {}) } };
          continue;
        }
        return { ok: true, code: res.status, body: await res.text(), signedOut: false };
      }
      return { ok: false, why: 'Amazon redirected Edith too many times' };
    } catch {
      return { ok: false, why: "Amazon can't be reached right now" };
    } finally {
      clearTimeout(timer);
    }
  }

  private appHeaders(site: AmazonSite, identityDomain: string): Record<string, string> {
    return {
      'User-Agent': APP_AGENT,
      'Accept-Language': site.language.replace('_', '-'),
      'Accept-Charset': 'utf-8',
      Accept: 'application/json',
      'x-amzn-identity-auth-domain': identityDomain,
    };
  }
}

/** A tiny `application/x-www-form-urlencoded` builder (Hermes' `URLSearchParams` is partial). */
class URLSearchParamsLite {
  private readonly parts: string[] = [];
  add(k: string, v: string) {
    this.parts.push(`${enc(k)}=${enc(v)}`);
    return this;
  }
  toString() {
    return this.parts.join('&');
  }
}
