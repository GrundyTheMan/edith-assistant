import { AppError } from '../core/errors';
import { readBytes } from '../core/bytes';
import type { HttpFetch } from '../ollama/client';

export const CALL_TIMEOUT_MS = 5_000;
const MAX_SNAPSHOT_BYTES = 4_000_000;

/** What went wrong in words the card and Settings can show. `unreachable` is "away from home" or "printer off": never an error banner. */
export class PrinterError extends Error {
  constructor(
    readonly kind: 'unreachable' | 'unauthorized' | 'bad',
    message: string,
  ) {
    super(message);
    this.name = 'PrinterError';
  }
}

/** The objects one `objects/query` asks for (C34/C55): enough for the card, no more. */
const OBJECTS = ['webhooks', 'print_stats', 'display_status', 'virtual_sdcard', 'pause_resume', 'idle_timeout', 'extruder', 'heater_bed'];

/**
 * Moonraker over plain HTTP on the home network (the 3D printer is one of the four plain-HTTP exceptions, AGENTS.md: it has no TLS and
 * lives at a LAN IP). Every answer is `{"result": …}`, unwrapped here. 5 s per call.
 */
export class MoonrakerApi {
  constructor(
    private readonly fetch: HttpFetch,
    readonly base: string,
    private readonly key?: string,
  ) {}

  private headers(): Record<string, string> | undefined {
    return this.key ? { 'X-Api-Key': this.key } : undefined;
  }

  private async call(method: 'GET' | 'POST', path: string): Promise<unknown> {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), CALL_TIMEOUT_MS);
    try {
      const res = await this.fetch(`${this.base}${path}`, { method, headers: this.headers(), signal: ctl.signal });
      if (res.status === 401 || res.status === 403) throw new PrinterError('unauthorized', 'The printer wants an API key, or the key is wrong.');
      if (!res.ok) throw new PrinterError('bad', `The printer answered HTTP ${res.status}.`);
      let json: unknown;
      try {
        json = JSON.parse(await res.text());
      } catch {
        throw new PrinterError('bad', "That address answered, but it doesn't look like a Moonraker printer.");
      }
      if (!json || typeof json !== 'object' || !('result' in json)) throw new PrinterError('bad', "That address answered, but it doesn't look like a Moonraker printer.");
      return (json as { result: unknown }).result;
    } catch (e) {
      if (e instanceof PrinterError) throw e;
      // A refused, timed-out or unreachable connection: off, or this phone is on another network.
      throw new PrinterError('unreachable', 'Not reachable. It works on your home Wi-Fi.');
    } finally {
      clearTimeout(timer);
    }
  }

  /** One `objects/query` for everything the card shows: Klipper's `status` object. */
  async status(): Promise<unknown> {
    const result = (await this.call('GET', `/printer/objects/query?${OBJECTS.join('&')}`)) as { status?: unknown };
    return result?.status ?? {};
  }
  /** The slicer's estimate for a file, seconds, when Moonraker has one. */
  async estimatedTime(filename: string): Promise<number | undefined> {
    try {
      const r = (await this.call('GET', `/server/files/metadata?filename=${encodeURIComponent(filename)}`)) as { estimated_time?: unknown };
      return typeof r?.estimated_time === 'number' && r.estimated_time > 0 ? r.estimated_time : undefined;
    } catch {
      return undefined; // no estimate is not an error: the pace is used
    }
  }
  async webcams(): Promise<unknown> {
    try {
      return await this.call('GET', '/server/webcams/list');
    } catch {
      return {};
    }
  }
  async pause() {
    await this.call('POST', '/printer/print/pause');
  }
  async resume() {
    await this.call('POST', '/printer/print/resume');
  }
  async cancel() {
    await this.call('POST', '/printer/print/cancel');
  }
  /** A JPEG from a webcam's snapshot address (already resolved against the printer's host). */
  async snapshot(url: string, signal?: AbortSignal): Promise<Uint8Array> {
    const ctl = new AbortController();
    const onAbort = () => ctl.abort();
    signal?.addEventListener('abort', onAbort);
    const timer = setTimeout(() => ctl.abort(), 10_000);
    try {
      const res = await this.fetch(url, { method: 'GET', headers: this.headers(), signal: ctl.signal });
      if (!res.ok) throw new PrinterError('bad', `The camera answered HTTP ${res.status}.`);
      const bytes = await readBytes(res.body, MAX_SNAPSHOT_BYTES);
      if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) throw new PrinterError('bad', "The camera didn't send a picture.");
      return bytes;
    } catch (e) {
      if (e instanceof PrinterError) throw e;
      if (e instanceof AppError) throw e;
      throw new PrinterError('unreachable', "Couldn't get a picture from the printer's camera.");
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', onAbort);
    }
  }
}
