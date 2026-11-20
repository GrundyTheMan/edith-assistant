import { hmacSha256Hex } from '../core/hmac';
import { log } from '../core/log';
import type { HttpFetch, HttpResponse } from '../ollama/client';
import { JarvisLink } from './link';

/** What an authenticated call to Jarvis answers (the feed spec's error codes, part 7). Never thrown. */
export type FeedResult<T> =
  | { kind: 'ok'; value: T }
  /** 401: Jarvis no longer knows this phone's token (it was removed there). */
  | { kind: 'unauthorized' }
  | { kind: 'notShared' }
  | { kind: 'busy' }
  /** 503 OUTLOOK_UNAVAILABLE: the PC's Outlook isn't answering (mail, calendar, tasks only). */
  | { kind: 'unavailable'; message: string }
  | { kind: 'timeout' }
  /** Nothing answered: the PC is off, Jarvis isn't running, or Tailscale is off on this phone. */
  | { kind: 'unreachable' }
  | { kind: 'failed'; message: string };

export type PairResult = { kind: 'ok'; link: JarvisLink } | { kind: 'refused' } | { kind: 'slowDown' } | { kind: 'notJarvis'; message: string } | { kind: 'unreachable' } | { kind: 'failed'; message: string };

const MAX_MESSAGE = 200;
export const PHONE_NAME = 'iPhone';
export const HELLO_APP = 'jarvis-feed';

/** The proof of knowing the pairing code, without sending it (`jarvis-tailscale-listener-spec.md` §5): HMAC-SHA256(code, "tailscale:" + the PC's name). */
export const pairingProof = (code: string, host: string): string => hmacSha256Hex(code, `tailscale:${host}`);

const obj = (v: unknown): Record<string, unknown> => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const parseJson = (text: string): unknown => {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
};

export interface CallOptions {
  signal?: AbortSignal;
  /** The whole call (default 15 s). A long poll passes its `wait` plus 10 s. */
  timeoutMs?: number;
}

/**
 * Jarvis's feed over ordinary HTTPS (C66a, D39): the pairing handshake and authenticated calls. `fetch` is the secure client restricted to `.ts.net`. Tokens, codes, proofs
 * and response bodies are never logged: only the route and the status.
 */
export class JarvisApi {
  constructor(private readonly deps: { fetch: HttpFetch }) {}

  private async send(url: string, init: { method: string; headers: Record<string, string>; body?: string }, opts: CallOptions = {}): Promise<{ res: HttpResponse; text: string } | 'unreachable' | 'timeout' | 'cancelled'> {
    const ctl = new AbortController();
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      ctl.abort();
    }, opts.timeoutMs ?? 15_000);
    const onAbort = () => ctl.abort();
    opts.signal?.addEventListener('abort', onAbort);
    try {
      const res = await this.deps.fetch(url, { ...init, signal: ctl.signal, credentials: 'omit' });
      return { res, text: await res.text() };
    } catch {
      return timedOut ? 'timeout' : opts.signal?.aborted ? 'cancelled' : 'unreachable';
    } finally {
      clearTimeout(timer);
      opts.signal?.removeEventListener('abort', onAbort);
    }
  }

  /** `GET /v1/hello` over the pairing name: is this Jarvis, and is it the one that was typed? */
  async hello(host: string, port: number): Promise<{ kind: 'ok' } | { kind: 'unreachable' } | { kind: 'notJarvis'; message: string }> {
    const r = await this.send(`https://${host}:${port}/v1/hello`, { method: 'GET', headers: { Accept: 'application/json' } });
    if (r === 'unreachable' || r === 'timeout' || r === 'cancelled') return { kind: 'unreachable' };
    log.info('jarvis', `GET /v1/hello ${r.res.status}`);
    const body = obj(parseJson(r.text));
    if (r.res.status !== 200 || body.app !== HELLO_APP) return { kind: 'notJarvis', message: "That answered, but it isn't Jarvis's feed. Check the name." };
    if (body.version !== 1) return { kind: 'notJarvis', message: "That Jarvis speaks a version Edith doesn't know. Update Edith or Jarvis." };
    if (body.transport !== 'tailscale') return { kind: 'notJarvis', message: "That's Jarvis's other listener. Use the Tailscale name and port Jarvis shows." };
    if (typeof body.host !== 'string' || body.host.toLowerCase() !== host) return { kind: 'notJarvis', message: "This isn't the Jarvis you meant: it answered with a different name." };
    return { kind: 'ok' };
  }

  /** The whole pairing: hello, then `POST /v1/pair` with the proof. `code` is already normalised (no dash, upper case). */
  async pair(host: string, port: number, code: string): Promise<PairResult> {
    const hi = await this.hello(host, port);
    if (hi.kind !== 'ok') return hi;
    const r = await this.send(`https://${host}:${port}/v1/pair`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ name: PHONE_NAME, proof: pairingProof(code, host) }),
    });
    if (r === 'unreachable' || r === 'timeout' || r === 'cancelled') return { kind: 'unreachable' };
    log.info('jarvis', `POST /v1/pair ${r.res.status}`);
    if (r.res.status === 429) return { kind: 'slowDown' };
    const body = obj(parseJson(r.text));
    if (r.res.status === 403) return { kind: 'refused' };
    const token = body.token;
    if (r.res.status !== 200 || typeof token !== 'string' || !token) return { kind: 'failed', message: errorText(body) ?? `Jarvis answered HTTP ${r.res.status}.` };
    const pcName = typeof body.pcName === 'string' && body.pcName ? body.pcName.slice(0, 60) : host;
    return { kind: 'ok', link: new JarvisLink(host, port, pcName, token) };
  }

  /** An authenticated call. `parse` turns the JSON answer into the caller's type (null = not what was expected). */
  async call<T>(link: JarvisLink, method: 'GET' | 'POST', path: string, parse: (json: unknown) => T | null, opts: CallOptions & { body?: unknown } = {}): Promise<FeedResult<T>> {
    const r = await this.send(
      `${link.base}${path}`,
      {
        method,
        headers: { Authorization: link.bearer(), Accept: 'application/json', ...(opts.body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
        ...(opts.body !== undefined ? { body: JSON.stringify(opts.body) } : {}),
      },
      opts,
    );
    if (r === 'timeout') return { kind: 'timeout' };
    if (r === 'unreachable' || r === 'cancelled') return { kind: 'unreachable' };
    log.info('jarvis', `${method} ${path.split('?')[0]} ${r.res.status}`);
    const json = parseJson(r.text);
    if (r.res.status === 401) return { kind: 'unauthorized' };
    if (r.res.status >= 200 && r.res.status < 300) {
      const value = parse(json);
      return value === null ? { kind: 'failed', message: "Jarvis's answer wasn't what Edith expected." } : { kind: 'ok', value };
    }
    const code = obj(obj(json).error).code;
    const message = errorText(obj(json));
    switch (code) {
      case 'UNAUTHORIZED':
        return { kind: 'unauthorized' };
      case 'NOT_SHARED':
        return { kind: 'notShared' };
      case 'BUSY':
        return { kind: 'busy' };
      case 'TIMEOUT':
        return { kind: 'timeout' };
      case 'OUTLOOK_UNAVAILABLE':
        return { kind: 'unavailable', message: message ?? "Outlook isn't answering on the PC." };
      default:
        return r.res.status === 429 ? { kind: 'busy' } : { kind: 'failed', message: message ?? `Jarvis answered HTTP ${r.res.status}.` };
    }
  }
}

/** Jarvis's own one-sentence message, shortened (the feed's messages are generic by design: part 7). */
function errorText(body: Record<string, unknown>): string | null {
  const m = obj(body.error).message;
  return typeof m === 'string' && m ? m.slice(0, MAX_MESSAGE) : null;
}

/** What `GET /v1/shared` says Jarvis shares with this phone (only the parts C66a shows; the printer entry is C67's). */
export interface Shared {
  pcName: string | null;
  mail: number;
  calendars: number;
  tasks: number;
  printer: boolean;
}
export function parseShared(json: unknown): Shared | null {
  const o = obj(json);
  const count = (v: unknown) => (Array.isArray(v) ? v.length : 0);
  if (!('mail' in o) && !('calendars' in o) && !('tasks' in o) && !('printer' in o)) return null;
  return { pcName: typeof o.pcName === 'string' ? o.pcName : null, mail: count(o.mail), calendars: count(o.calendars), tasks: count(o.tasks), printer: !!o.printer };
}
