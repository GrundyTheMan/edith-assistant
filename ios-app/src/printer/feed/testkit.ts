/**
 * The pretend Jarvis printer feed (C67 E1) for tests of everything above it: the client, the session, the page and the chat tool. It answers every route of the contract from the
 * Android fixtures, records every request, and can hold the long poll, so the rules ("not requested until opened", "stops polling", the confirmation flow) are checked by what
 * it was asked. **Test code only: nothing in the app imports this.** (The real Jarvis check is the owner's.)
 */
import * as fs from 'fs';
import * as path from 'path';
import { memorySecrets } from '../../core/secrets';
import { JarvisApi } from '../../jarvis/api';
import { JarvisLink } from '../../jarvis/link';
import { JarvisService } from '../../jarvis/service';
import type { HttpFetch, HttpResponse } from '../../ollama/client';
import { jsonResponse, streamResponse } from '../../testing/fakeHttp';

export const FEED_HOST = 'ben-pc.tail1234.ts.net';
export const FEED_PORT = 47822;
export const FEED_TOKEN = 'FEED-TOKEN-0123456789';
export const JPEG = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4]);

export const fixture = (name: string): Record<string, unknown> =>
  JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures', `${name}.json`), 'utf8')) as Record<string, unknown>;

export interface FeedRequest {
  method: string;
  path: string;
  query: Record<string, string>;
  body: Record<string, unknown> | null;
  auth: string | undefined;
}

type Reply = HttpResponse | Promise<HttpResponse>;

export class FakeFeed {
  requests: FeedRequest[] = [];
  /** The answer of the state route; change it with `setState`. */
  answer: Record<string, unknown> = fixture('state-printing');
  tokens = new Set<string>([FEED_TOKEN]);
  down = false;
  /** When true (as the real Jarvis does), a state request that already has the current `rev` waits until `release()` or `setState()`. */
  hold = true;
  private held: ((r: HttpResponse) => void)[] = [];
  shared: Record<string, unknown> = fixture('shared-with-printer');
  /** `command` answers; return undefined to use the default behaviour. */
  onCommand: ((body: Record<string, unknown>) => HttpResponse | undefined) | null = null;
  /** Controls: the phone's arming (what `POST controls` sets). */
  armed = false;
  /** The kinds whose check says "confirm" or "danger" (dry runs show it; a real send without `confirmed` is refused). */
  risks: Record<string, 'confirm' | 'danger'> = { cancel: 'danger', home: 'confirm', estop: 'danger' };
  /** When set, the state route answers this instead (an error body, a 429, ...). */
  stateReply: (() => HttpResponse) | null = null;
  /** What `POST settings` last saved. */
  savedPresets: unknown[] | null = null;
  savedHidden: string[] | null = null;
  snapshotBytes: Uint8Array = JPEG;
  snapshotStatus = 200;

  private rev(): number {
    return Number(this.answer.rev ?? 0);
  }

  /** Publishes a new state (a new `rev`) and answers any held long poll with it. */
  setState(next: Record<string, unknown>) {
    this.answer = { ...next, rev: this.rev() + 1 };
    const held = this.held;
    this.held = [];
    for (const resolve of held) resolve(jsonResponse(this.answer));
  }
  /** Answers held long polls with the unchanged state (Jarvis's own timeout). */
  release() {
    const held = this.held;
    this.held = [];
    for (const resolve of held) resolve(jsonResponse(this.answer));
  }
  heldPolls = () => this.held.length;
  count = (p: string) => this.requests.filter((r) => r.path === p).length;

  fetch: HttpFetch = async (url, init) => {
    const u = url.replace(`https://${FEED_HOST}:${FEED_PORT}`, '');
    const [p, q = ''] = u.split('?');
    const query = Object.fromEntries(q.split('&').filter(Boolean).map((kv) => kv.split('=').map(decodeURIComponent) as [string, string]));
    const body = init.body ? (JSON.parse(init.body) as Record<string, unknown>) : null;
    const req: FeedRequest = { method: init.method, path: p, query, body, auth: init.headers?.Authorization };
    this.requests.push(req);
    if (this.down) throw new TypeError('Network request failed');
    const token = (req.auth ?? '').replace('Bearer ', '');
    if (!this.tokens.has(token)) return jsonResponse({ error: { code: 'UNAUTHORIZED', message: 'Not paired.' } }, 401);
    return this.route(req, init.signal);
  };

  private route(req: FeedRequest, signal?: AbortSignal): Reply {
    const ok = (b: unknown) => jsonResponse(b);
    switch (`${req.method} ${req.path}`) {
      case 'GET /v1/shared':
        return ok(this.shared);
      case 'GET /v1/printer/state': {
        if (this.stateReply) return this.stateReply();
        if (this.hold && req.query.after !== undefined && Number(req.query.after) === this.rev()) {
          return new Promise<HttpResponse>((resolve, reject) => {
            this.held.push(resolve);
            signal?.addEventListener('abort', () => {
              this.held = this.held.filter((h) => h !== resolve);
              reject(Object.assign(new Error('Aborted'), { name: 'AbortError' }));
            });
          });
        }
        return ok(this.answer);
      }
      case 'GET /v1/printer/temperatures':
        return ok(fixture('temperatures'));
      case 'GET /v1/printer/macros':
        return ok(fixture('macros'));
      case 'GET /v1/printer/console':
        return ok(fixture('console'));
      case 'GET /v1/printer/files':
        return ok(fixture('files'));
      case 'GET /v1/printer/files/detail':
        return ok({ path: req.query.path, estimatedTime: 3600, filamentTotal: 5200, layerCount: 120 });
      case 'GET /v1/printer/history':
        return ok(fixture('history'));
      case 'POST /v1/printer/controls':
        this.armed = !!req.body?.on;
        return ok({ arm: { armed: this.armed, until: this.armed ? 1763400160000 : null } });
      case 'POST /v1/printer/settings': {
        if (!this.armed) return jsonResponse({ error: { code: 'CONTROLS_OFF', message: 'Controls are off.' } }, 403);
        const b = req.body ?? {};
        if (Array.isArray(b.presets)) this.savedPresets = b.presets;
        if (Array.isArray(b.hiddenMacros)) this.savedHidden = b.hiddenMacros as string[];
        return ok({ presets: this.savedPresets ?? [], hiddenMacros: this.savedHidden ?? [] });
      }
      case 'POST /v1/printer/command':
        return this.command(req.body ?? {});
    }
    if (req.method === 'GET' && /^\/v1\/printer\/camera\/[^/]+\/snapshot$/.test(req.path)) {
      return this.snapshotStatus === 200 ? streamResponse([this.snapshotBytes]) : jsonResponse({ error: { code: 'PRINTER_UNREACHABLE', message: 'No picture.' } }, this.snapshotStatus);
    }
    return jsonResponse({ error: { code: 'NOT_FOUND', message: 'No such route.' } }, 404);
  }

  private command(body: Record<string, unknown>): HttpResponse {
    const custom = this.onCommand?.(body);
    if (custom) return custom;
    const kind = String((body.command as { kind?: string } | undefined)?.kind ?? '');
    const risk: string = this.risks[kind] ?? 'none';
    const check = { allowed: true, risk, ...(risk !== 'none' ? { question: `Really ${kind}?`, confirm: kind } : {}) };
    if (body.dryRun) return jsonResponse(check);
    if (!this.armed && kind !== 'estop') return jsonResponse({ error: { code: 'CONTROLS_OFF', message: 'Controls are off.' } }, 403);
    if (risk !== 'none' && !body.confirmed) return jsonResponse({ error: { code: 'CONFIRM_REQUIRED', message: 'Ask the owner to confirm this first.' }, check }, 409);
    return jsonResponse({ sent: kind.toUpperCase(), risk });
  }
}

/** A connected `JarvisService` talking to a fake feed (paired already), for tests of anything that needs the client. */
export async function connectedFeed(over: (feed: FakeFeed) => void = () => {}) {
  const feed = new FakeFeed();
  over(feed);
  const secrets = memorySecrets({ 'home.jarvis': new JarvisLink(FEED_HOST, FEED_PORT, 'BEN-PC', FEED_TOKEN).serialize() });
  const service = new JarvisService({ secrets, api: new JarvisApi({ fetch: feed.fetch }) });
  await service.restore();
  return { feed, service, secrets };
}
