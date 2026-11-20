import { AppError } from './errors';
import type { HttpFetch, HttpResponse } from '../ollama/client';

export interface SecureFetchOptions {
  /**
   * Host suffixes a request and its redirects may go to (`meross.com` allows `iot.meross.com`). When missing, a request may
   * only stay on the host it started on: a redirect elsewhere could carry a credential to another site (C37's two bugs).
   */
  allowHosts?: string[];
  /** Redirects followed by hand when the platform lets us (default 3). */
  maxRedirects?: number;
}

const hostOf = (url: string): string | null => /^https?:\/\/(?:[^/@?#]*@)?([^/:?#]+)/i.exec(url)?.[1]?.toLowerCase() ?? null;
const isHttps = (url: string) => /^https:\/\//i.test(url);

export function hostAllowed(host: string, allow: string[]): boolean {
  const h = host.toLowerCase();
  return allow.some((s) => h === s.toLowerCase() || h.endsWith(`.${s.toLowerCase()}`));
}

/** Resolves a Location header against the current URL without `new URL` (Hermes' URL is partial). */
export function resolveLocation(location: string, current: string): string {
  if (/^https?:\/\//i.test(location)) return location;
  const origin = /^(https?:\/\/[^/?#]+)/i.exec(current)?.[1] ?? '';
  if (location.startsWith('//')) return `${current.startsWith('https') ? 'https:' : 'http:'}${location}`;
  if (location.startsWith('/')) return origin + location;
  const base = current.split(/[?#]/)[0];
  return base.slice(0, base.lastIndexOf('/') + 1) + location;
}

/**
 * C37 on iOS: HTTPS is a property of the client, not something each service must remember. A request to anything but
 * `https://` is refused before it is sent; a redirect that leaves `https` or the allowed hosts stops the request (and,
 * where the platform won't let us look at redirects first, the answer from the wrong host is thrown away). Errors name the
 * host only: never the path, query or credentials.
 */
export function secureFetch(base: HttpFetch, options: SecureFetchOptions = {}): HttpFetch {
  const maxRedirects = options.maxRedirects ?? 3;
  return async (url, init) => {
    const start = hostOf(url);
    if (!isHttps(url) || !start) throw AppError.insecureUrl(start ?? 'that address');
    const allow = options.allowHosts ?? [start];
    if (!hostAllowed(start, allow)) throw AppError.insecureUrl(start);

    let current = url;
    for (let hop = 0; ; hop++) {
      const res: HttpResponse = await base(current, { ...init, redirect: 'manual' });
      // A platform that follows redirects itself tells us where it ended up.
      const landed = res.url && hostOf(res.url);
      if (res.url && (!isHttps(res.url) || !landed || !hostAllowed(landed, allow))) {
        await res.body?.getReader().cancel?.();
        throw AppError.insecureUrl(landed ?? 'another address');
      }
      const redirect = res.status >= 300 && res.status < 400 && res.status !== 304;
      const location = redirect ? res.headers?.get('location') : null;
      if (!location) return res;
      if (hop >= maxRedirects) throw AppError.insecureUrl(hostOf(current) ?? 'that address');
      const next = resolveLocation(location, current);
      const nextHost = hostOf(next);
      if (!isHttps(next) || !nextHost || !hostAllowed(nextHost, allow)) throw AppError.insecureUrl(nextHost ?? 'another address');
      current = next;
    }
  };
}
