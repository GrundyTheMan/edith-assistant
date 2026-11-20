import { log } from '../../core/log';
import type { HttpFetch } from '../../ollama/client';
import { readableText } from './html';
import { isPrivateHost } from './privateNetwork';

export const MAX_PAGE_BYTES = 2 * 1024 * 1024;
export const MAX_PAGE_CHARS = 6000;

export class PageError extends Error {}

export interface Page {
  url: string;
  title: string;
  text: string;
  truncated: boolean;
}

function hostOf(url: string): string | null {
  try {
    const u = new URL(url);
    return u.protocol === 'http:' || u.protocol === 'https:' ? u.hostname : null;
  } catch {
    return null;
  }
}

/** Why this address may not be read, or null. */
export function refusal(url: string): string | null {
  const host = hostOf(url);
  if (host === null) return 'Only http and https pages can be read.';
  if (isPrivateHost(host)) return "That address is on a private network, and I don't read those.";
  return null;
}

/** C10 `read_web_page`: downloads at most 2 MB, keeps the readable text, at most 6,000 characters. */
export class PageReader {
  constructor(
    private readonly fetch: HttpFetch,
    private readonly timeoutMs = 10_000,
  ) {}

  async read(url: string, signal?: AbortSignal): Promise<Page> {
    const why = refusal(url);
    if (why) throw new PageError(why);
    const timeout = new AbortController();
    const timer = setTimeout(() => timeout.abort(), this.timeoutMs);
    const onAbort = () => timeout.abort();
    signal?.addEventListener('abort', onAbort);
    try {
      const res = await this.fetch(url, {
        method: 'GET',
        headers: { 'User-Agent': 'EdithAssistant/2 (iOS)', Accept: 'text/html,text/plain;q=0.9,*/*;q=0.5' },
        signal: timeout.signal,
      });
      // Redirects are followed by the system; if one ended on a private address, nothing from it is used.
      const finalUrl = res.url || url;
      const finalWhy = refusal(finalUrl);
      if (finalWhy) throw new PageError(finalWhy);
      if (res.status === 403) throw new PageError('That site blocked me from reading the page.');
      if (res.status === 404) throw new PageError("That page doesn't exist (404).");
      if (!res.ok) throw new PageError(`The site answered with an error (${res.status}).`);
      const type = (res.headers?.get('content-type') ?? 'text/html').split(';')[0].trim().toLowerCase();
      const isHtml = type === 'text/html' || type === 'application/xhtml+xml';
      if (!isHtml && type !== 'text/plain') throw new PageError(`That's a ${type} file, not a web page I can read.`);
      const raw = await this.readCapped(res, timeout.signal);
      const { title, text } = isHtml ? readableText(raw) : { title: '', text: raw.split('\n').map((l) => l.trim()).filter(Boolean).join('\n') };
      if (!text) throw new PageError('The page has no readable text (it may need a browser to show its content).');
      const truncated = text.length > MAX_PAGE_CHARS;
      return { url: finalUrl, title, text: truncated ? text.slice(0, MAX_PAGE_CHARS) : text, truncated };
    } catch (e) {
      if (signal?.aborted) throw e;
      if (e instanceof PageError) throw e;
      log.warn('Web', 'Reading a page failed');
      throw new PageError(timeout.signal.aborted ? 'The page took too long to load.' : "I couldn't load that page.");
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', onAbort);
    }
  }

  /** At most MAX_PAGE_BYTES, decoded as UTF-8; a longer page is cut, not refused. */
  private async readCapped(res: Awaited<ReturnType<HttpFetch>>, signal: AbortSignal): Promise<string> {
    const reader = res.body?.getReader();
    if (!reader) return (await res.text()).slice(0, MAX_PAGE_BYTES);
    const chunks: Uint8Array[] = [];
    let total = 0;
    while (total < MAX_PAGE_BYTES) {
      if (signal.aborted) throw new Error('aborted');
      const { done, value } = await reader.read();
      if (done || !value) break;
      chunks.push(value);
      total += value.byteLength;
    }
    void reader.cancel?.();
    const all = new Uint8Array(Math.min(total, MAX_PAGE_BYTES));
    let at = 0;
    for (const c of chunks) {
      const room = all.length - at;
      if (room <= 0) break;
      all.set(c.byteLength > room ? c.subarray(0, room) : c, at);
      at += Math.min(c.byteLength, room);
    }
    return new TextDecoder('utf-8').decode(all);
  }
}
