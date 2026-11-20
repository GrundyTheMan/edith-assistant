import { log } from '../../core/log';
import type { HttpFetch } from '../../ollama/client';
import { collapse, tokenize } from './html';

export interface SearchHit {
  title: string;
  url: string;
  snippet: string;
}

/** A search that failed in a way the owner or model can act on; the message is plain and model-facing. */
export class SearchError extends Error {}

/** A result link may be wrapped in DuckDuckGo's redirect (`//duckduckgo.com/l/?uddg=<encoded url>`): unwrap it. */
export function unwrapDuckDuckGoLink(href: string): string | null {
  let link = href.trim();
  if (link.startsWith('//')) link = `https:${link}`;
  const marker = link.indexOf('uddg=');
  if (link.indexOf('duckduckgo.com/l/') >= 0 && marker >= 0) {
    const rest = link.slice(marker + 5);
    const amp = rest.indexOf('&');
    try {
      return decodeURIComponent(amp < 0 ? rest : rest.slice(0, amp));
    } catch {
      return null;
    }
  }
  if (!link.startsWith('http://') && !link.startsWith('https://')) return null;
  // Ads and DuckDuckGo's own pages are not results.
  if (link.indexOf('duckduckgo.com/y.js') >= 0 || link.indexOf('//duckduckgo.com') >= 0 || link.indexOf('//www.duckduckgo.com') >= 0) return null;
  return link;
}

/** Reads the results out of DuckDuckGo's HTML results page (captured 2026-11-20; a layout change breaks this on purpose). */
export function parseResults(html: string, max: number): SearchHit[] {
  const hits: SearchHit[] = [];
  const tokens = tokenize(html);
  let current: SearchHit | null = null;
  let collecting: 'title' | 'snippet' | null = null;
  let text = '';
  let ad = false;
  const commit = () => {
    if (current && current.title && current.url) hits.push(current);
    current = null;
  };
  for (const t of tokens) {
    if (t.type === 'open') {
      const cls = ` ${t.attrs.class ?? ''} `;
      if (t.name === 'div' && cls.indexOf(' result ') >= 0) {
        commit();
        ad = cls.indexOf('result--ad') >= 0;
      }
      if (t.name === 'a' && !ad) {
        if (cls.indexOf(' result__a ') >= 0) {
          const url = unwrapDuckDuckGoLink(t.attrs.href ?? '');
          commit();
          if (url) current = { title: '', url, snippet: '' };
          collecting = current ? 'title' : null;
          text = '';
        } else if (cls.indexOf(' result__snippet ') >= 0 && current) {
          collecting = 'snippet';
          text = '';
        }
      }
    } else if (t.type === 'text') {
      if (collecting) text += t.text;
    } else if (t.type === 'close' && t.name === 'a' && collecting && current) {
      if (collecting === 'title') current.title = collapse(text);
      else current.snippet = collapse(text);
      collecting = null;
    }
    if (hits.length >= max) break;
  }
  commit();
  return hits.slice(0, max);
}

/** DuckDuckGo shows a human check instead of results when it thinks a script is asking. */
export function isBotCheck(status: number, html: string): boolean {
  return status === 202 || html.indexOf('anomaly') >= 0 || html.indexOf('g-recaptcha') >= 0;
}

/** No key, no account. The search is POSTed like the site's own form: plain GETs were flagged as bots on Android (L20). */
export class DuckDuckGoSearch {
  constructor(
    private readonly fetch: HttpFetch,
    private readonly timeoutMs = 10_000,
  ) {}

  async search(query: string, maxResults: number, signal?: AbortSignal): Promise<SearchHit[]> {
    const timeout = new AbortController();
    const timer = setTimeout(() => timeout.abort(), this.timeoutMs);
    const onAbort = () => timeout.abort();
    signal?.addEventListener('abort', onAbort);
    try {
      const res = await this.fetch('https://html.duckduckgo.com/html/', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148',
        },
        body: `q=${encodeURIComponent(query)}`,
        signal: timeout.signal,
      });
      const html = await res.text();
      if (isBotCheck(res.status, html)) throw new SearchError('DuckDuckGo is asking for a human check, so web search is unavailable right now. Try again later.');
      if (!res.ok) throw new SearchError("The search service didn't respond — try again shortly.");
      return parseResults(html, maxResults);
    } catch (e) {
      if (signal?.aborted) throw e;
      if (e instanceof SearchError) throw e;
      log.warn('Web', 'The search request failed');
      throw new SearchError("The search service didn't respond — try again shortly.");
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', onAbort);
    }
  }
}
