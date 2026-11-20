import { readBytes } from '../core/bytes';
import type { HttpFetch, HttpResponse } from '../ollama/client';
import type { DeviceEvent } from '../home/model';
import { parseCameras, parseEvents, type RingCamera } from './parse';

/** Where Ring's three services live. Tests point all three at one pretend server. */
export interface RingEndpoints {
  oauth: string;
  clients: string;
  snapshots: string;
}
export const RING_ENDPOINTS: RingEndpoints = {
  oauth: 'https://oauth.ring.com',
  clients: 'https://api.ring.com/clients_api',
  snapshots: 'https://app-snaps.ring.com',
};

/** As `ring-client-api` v14.3.0 and the Android build (C27): the official Android app's id, and its user agent. */
export const CLIENT_ID = 'ring_official_android';
export const USER_AGENT = 'android:com.ringapp';
export const API_VERSION = 11;
export const EVENT_LIMIT = 50;
const MAX_PICTURE_BYTES = 4_000_000;

export interface RingTokens {
  accessToken: string;
  /** Rotates: the one it replaces stops working soon after. */
  refreshToken: string | null;
  expiresInSeconds: number;
}

export type RingLogin =
  | { kind: 'ok'; tokens: RingTokens }
  /** Ring sent a code (HTTP 412); `prompt` says where. */
  | { kind: 'needs_code'; prompt: string }
  | { kind: 'wrong_code' }
  | { kind: 'bad_login' }
  | { kind: 'too_many_codes' }
  | { kind: 'failed'; why: string };

export type RingRefresh = { kind: 'ok'; tokens: RingTokens } | { kind: 'rejected' } | { kind: 'failed'; why: string };

export type RingReply<T> =
  | { kind: 'ok'; value: T }
  /** HTTP 401: the access token is no good (expired): refresh and ask again. */
  | { kind: 'unauthorized' }
  /** HTTP 404 naming this phone's hardware id: Ring forgot the session; start one again. */
  | { kind: 'no_session' }
  | { kind: 'failed'; why: string };

const UNREACHABLE = "Ring can't be reached right now";
type Obj = Record<string, unknown>;
const asObj = (v: unknown): Obj | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Obj) : null);
const str = (o: Obj | null, k: string): string | null => (typeof o?.[k] === 'string' ? (o[k] as string) : null);

export interface RingApiDeps {
  /** HTTPS to Ring only: `secureFetch(httpFetch, { allowHosts: ['ring.com'] })`. */
  fetch: HttpFetch;
  endpoints?: RingEndpoints;
  timeoutMs?: number;
  snapshotTimeoutMs?: number;
}

/**
 * Ring's own (unofficial) service, as the maintained `ring-client-api` uses it (C27 ported): OAuth at `oauth.ring.com`
 * (password grant with the 2FA code in a header, then the rotating refresh-token grant), a session for this phone's hardware
 * id, the device list, snapshots and event history. Stateless: `RingService` keeps the tokens. Tokens, passwords, pictures
 * and response bodies are never logged.
 */
export class RingApi {
  private readonly ep: RingEndpoints;
  private readonly timeoutMs: number;
  private readonly snapshotTimeoutMs: number;
  constructor(private readonly deps: RingApiDeps) {
    this.ep = deps.endpoints ?? RING_ENDPOINTS;
    this.timeoutMs = deps.timeoutMs ?? 20_000;
    this.snapshotTimeoutMs = deps.snapshotTimeoutMs ?? 30_000;
  }

  /** One request with its own time limit (and the caller's signal). Throws on network failure or timeout. */
  private async send(url: string, init: { method: string; headers: Record<string, string>; body?: string }, limitMs: number, outer?: AbortSignal): Promise<HttpResponse> {
    const ctl = new AbortController();
    const onAbort = () => ctl.abort();
    outer?.addEventListener('abort', onAbort);
    const timer = setTimeout(() => ctl.abort(), limitMs);
    try {
      return await this.deps.fetch(url, { ...init, signal: ctl.signal, credentials: 'omit' });
    } finally {
      clearTimeout(timer);
      outer?.removeEventListener('abort', onAbort);
    }
  }

  private authHeaders(accessToken: string, hardwareId: string): Record<string, string> {
    return { Authorization: `Bearer ${accessToken}`, hardware_id: hardwareId, 'User-Agent': USER_AGENT };
  }

  // --- OAuth -------------------------------------------------------------------------------------------------------------------
  private async oauth(body: Obj, hardwareId: string, code: string | null): Promise<{ status: number; data: Obj } | { failed: string }> {
    try {
      const res = await this.send(
        `${this.ep.oauth}/oauth/token`,
        {
          method: 'POST',
          headers: { '2fa-support': 'true', '2fa-code': code ?? '', hardware_id: hardwareId, 'User-Agent': USER_AGENT, Accept: 'application/json', 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        },
        this.timeoutMs,
      );
      let data: Obj = {};
      try {
        data = asObj(JSON.parse(await res.text())) ?? {};
      } catch {
        // not JSON: an empty answer
      }
      return { status: res.status, data };
    } catch {
      return { failed: UNREACHABLE };
    }
  }

  private tokens(d: Obj): RingTokens | null {
    const access = str(d, 'access_token');
    if (!access) return null;
    const exp = typeof d.expires_in === 'number' ? d.expires_in : 3600;
    return { accessToken: access, refreshToken: str(d, 'refresh_token'), expiresInSeconds: exp };
  }

  /** Step one and two of a sign-in: email and password, plus `code` once Ring has asked for it. */
  async signIn(email: string, password: string, code: string | null, hardwareId: string): Promise<RingLogin> {
    const raw = await this.oauth({ client_id: CLIENT_ID, scope: 'client', grant_type: 'password', username: email, password }, hardwareId, code);
    if ('failed' in raw) return { kind: 'failed', why: raw.failed };
    const { status, data } = raw;
    if (status === 200) {
      const tokens = this.tokens(data);
      return tokens ? { kind: 'ok', tokens } : { kind: 'failed', why: "Ring's answer wasn't what Edith expected" };
    }
    if (status === 412) return { kind: 'needs_code', prompt: promptFor(data) };
    if (status === 400 && (str(data, 'error') ?? '').startsWith('Verification Code')) return { kind: 'wrong_code' };
    if (str(data, 'error_description') === 'too many requests from dependency service') return { kind: 'too_many_codes' };
    if (status === 400 || status === 401) return { kind: 'bad_login' };
    return { kind: 'failed', why: `Ring answered HTTP ${status}` };
  }

  /** The refresh-token grant. The reply's `refresh_token` replaces the one sent. */
  async refresh(refreshToken: string, hardwareId: string): Promise<RingRefresh> {
    const raw = await this.oauth({ client_id: CLIENT_ID, scope: 'client', grant_type: 'refresh_token', refresh_token: refreshToken }, hardwareId, null);
    if ('failed' in raw) return { kind: 'failed', why: raw.failed };
    if (raw.status === 200) {
      const tokens = this.tokens(raw.data);
      return tokens ? { kind: 'ok', tokens } : { kind: 'failed', why: "Ring's answer wasn't what Edith expected" };
    }
    if (raw.status === 400 || raw.status === 401) return { kind: 'rejected' };
    return { kind: 'failed', why: `Ring answered HTTP ${raw.status}` };
  }

  // --- calls with an access token ----------------------------------------------------------------------------------------------
  private async call<T>(url: string, init: { method: string; headers: Record<string, string>; body?: string }, hardwareId: string, what: string, parse: (body: unknown) => T | null): Promise<RingReply<T>> {
    let res: HttpResponse;
    let raw: string;
    try {
      res = await this.send(url, init, this.timeoutMs);
      raw = await res.text();
    } catch {
      return { kind: 'failed', why: UNREACHABLE };
    }
    if (res.status === 401) return { kind: 'unauthorized' };
    if (res.status === 404 && raw.includes(hardwareId)) return { kind: 'no_session' };
    if (res.status === 429) return { kind: 'failed', why: 'Ring says to slow down; try again in a few minutes' };
    if (res.status < 200 || res.status >= 300) return { kind: 'failed', why: `Ring answered HTTP ${res.status} for ${what}` };
    try {
      const value = parse(JSON.parse(raw));
      return value === null ? { kind: 'failed', why: `Ring's answer for ${what} wasn't what Edith expected` } : { kind: 'ok', value };
    } catch {
      return { kind: 'failed', why: `Ring's answer for ${what} wasn't what Edith expected` };
    }
  }

  /** Starts this phone's session: Ring wants one before it answers the other calls. */
  startSession(accessToken: string, hardwareId: string): Promise<RingReply<true>> {
    const body = JSON.stringify({ device: { hardware_id: hardwareId, metadata: { api_version: API_VERSION, device_model: 'Edith' }, os: 'android' } });
    return this.call(
      `${this.ep.clients}/session`,
      { method: 'POST', headers: { ...this.authHeaders(accessToken, hardwareId), Accept: 'application/json', 'Content-Type': 'application/json' }, body },
      hardwareId,
      'the session',
      () => true,
    );
  }

  cameras(accessToken: string, hardwareId: string): Promise<RingReply<RingCamera[]>> {
    return this.call(`${this.ep.clients}/ring_devices`, { method: 'GET', headers: { ...this.authHeaders(accessToken, hardwareId), Accept: 'application/json' } }, hardwareId, 'the device list', parseCameras);
  }

  events(accessToken: string, hardwareId: string, cam: RingCamera, sinceMs: number): Promise<RingReply<DeviceEvent[]>> {
    if (!cam.locationId) return Promise.resolve({ kind: 'failed', why: "Ring didn't say where this camera is" });
    const url = `${this.ep.clients}/locations/${cam.locationId}/devices/${cam.id}/events?limit=${EVENT_LIMIT}`;
    return this.call(url, { method: 'GET', headers: { ...this.authHeaders(accessToken, hardwareId), Accept: 'application/json' } }, hardwareId, 'the events', (j) => parseEvents(j, cam, sinceMs));
  }

  /** A fresh picture: Ring has the camera take one now (`extras=force`) and returns the JPEG. */
  async snapshot(accessToken: string, hardwareId: string, cameraId: string, afterMs: number, signal?: AbortSignal): Promise<RingReply<Uint8Array>> {
    const url = `${this.ep.snapshots}/snapshots/next/${cameraId}?after-ms=${afterMs}&extras=force`;
    try {
      const res = await this.send(url, { method: 'GET', headers: { ...this.authHeaders(accessToken, hardwareId), Accept: 'image/jpeg' } }, this.snapshotTimeoutMs, signal);
      if (res.status === 401) return { kind: 'unauthorized' };
      if (res.status < 200 || res.status >= 300) return { kind: 'failed', why: `Ring answered HTTP ${res.status} for the picture` };
      const bytes = await readBytes(res.body, MAX_PICTURE_BYTES);
      return bytes.length === 0 ? { kind: 'failed', why: 'Ring sent an empty picture' } : { kind: 'ok', value: bytes };
    } catch {
      return { kind: 'failed', why: UNREACHABLE };
    }
  }
}

function promptFor(d: Obj): string {
  const state = str(d, 'tsv_state');
  if (state === 'totp') return 'Ring wants the code from your authenticator app.';
  if (state) return `Ring sent a code to ${str(d, 'phone') ?? 'you'} by ${state}.`;
  return 'Ring sent a code to your phone or email.';
}
