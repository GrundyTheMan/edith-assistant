import type { HttpFetch } from '../ollama/client';
import { GARAGE_STATE, SYSTEM_ALL, deviceMessage, verify } from './protocol';

/** A garage door as the device reports it: `all.digest.garageDoor[{channel, open, lmTime}]`. */
export interface LanDoor {
  channel: number;
  open: boolean;
  /** Seconds since the epoch of the last move, when the device knows. */
  lastMovedSec?: number;
}
export interface LanReading {
  uuid: string;
  doors: LanDoor[];
}

export class LanError extends Error {
  constructor(
    readonly kind: 'unreachable' | 'untrusted' | 'bad',
    message: string,
  ) {
    super(message);
    this.name = 'LanError';
  }
}

const obj = (v: unknown): Record<string, unknown> | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null);

/**
 * A Meross device on the home network: signed HTTP to `http://<ip>/config` with the same envelope the cloud's MQTT carries. **The local
 * protocol is not verified on the owner's doors** (C50 "Not verified"): the signing rule, the `from` value and the path follow the
 * community's reading of the devices; the first live use is the owner's. Plain HTTP on a LAN IP is an AGENTS.md exception (Meross devices
 * have no TLS). Every answer's `sign` is checked: a wrong one is never believed.
 */
export class MerossLan {
  constructor(
    private readonly fetch: HttpFetch,
    private readonly now: () => number,
    private readonly random?: () => number,
  ) {}

  /** One signed call; returns the answer's `payload`. */
  async call(ip: string, key: string, method: 'GET' | 'SET', namespace: string, payload: Record<string, unknown>, timeoutMs = 2_500): Promise<Record<string, unknown>> {
    const from = `http://${ip}/config`;
    const { text, messageId } = deviceMessage(method, namespace, payload, key, from, Math.floor(this.now() / 1000), this.random);
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), timeoutMs);
    try {
      const res = await this.fetch(from, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: text, signal: ctl.signal });
      if (!res.ok) throw new LanError('bad', `The device answered HTTP ${res.status}.`);
      let json: unknown;
      try {
        json = JSON.parse(await res.text());
      } catch {
        throw new LanError('bad', "The device's answer wasn't what Edith expected.");
      }
      const body = obj(json);
      if (!body || !verify(body.header, key)) throw new LanError('untrusted', "The device answered something Edith couldn't trust.");
      const header = obj(body.header);
      if (header?.messageId !== messageId) throw new LanError('untrusted', "The device answered something Edith couldn't trust.");
      if (header?.method === 'ERROR') throw new LanError('bad', 'The device refused that.');
      return obj(body.payload) ?? {};
    } catch (e) {
      if (e instanceof LanError) throw e;
      throw new LanError('unreachable', 'Not reachable. It works on your home Wi-Fi.');
    } finally {
      clearTimeout(timer);
    }
  }

  /** The device's identity and its doors. */
  async read(ip: string, key: string, timeoutMs?: number): Promise<LanReading> {
    const payload = await this.call(ip, key, 'GET', SYSTEM_ALL, {}, timeoutMs);
    const all = obj(payload.all);
    const uuid = obj(obj(all?.system)?.hardware)?.uuid;
    if (typeof uuid !== 'string') throw new LanError('bad', "The device didn't say what it is.");
    const list = obj(all?.digest)?.garageDoor;
    const doors: LanDoor[] = [];
    if (Array.isArray(list)) {
      for (const e of list) {
        const d = obj(e);
        if (!d || typeof d.channel !== 'number') continue;
        doors.push({ channel: d.channel, open: d.open === 1 || d.open === true, lastMovedSec: typeof d.lmTime === 'number' && d.lmTime > 0 ? d.lmTime : undefined });
      }
    }
    return { uuid, doors };
  }

  async setDoor(ip: string, key: string, uuid: string, channel: number, open: boolean): Promise<void> {
    await this.call(ip, key, 'SET', GARAGE_STATE, { state: { channel, open: open ? 1 : 0, uuid } }, 5_000);
  }
}

/** The /24 around the phone's address, without the phone itself: `192.168.4.7` → `192.168.4.1` … `.254`. Empty for anything that isn't a plain IPv4 address. */
export function subnetHosts(phoneIp: string | null): string[] {
  const m = phoneIp ? /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(phoneIp) : null;
  if (!m || [m[1], m[2], m[3], m[4]].some((p) => Number(p) > 255)) return [];
  const prefix = `${m[1]}.${m[2]}.${m[3]}`;
  const out: string[] = [];
  for (let i = 1; i <= 254; i++) if (String(i) !== m[4]) out.push(`${prefix}.${i}`);
  return out;
}

/**
 * Finds the wanted devices on the phone's /24 by asking every address who it is, `concurrency` at a time, stopping once all are found.
 * `probe` returns a device's uuid or null.
 */
export async function scanForDevices(
  phoneIp: string | null,
  wanted: string[],
  probe: (ip: string) => Promise<string | null>,
  concurrency = 20,
): Promise<Map<string, string>> {
  const found = new Map<string, string>();
  const hosts = subnetHosts(phoneIp);
  let next = 0;
  const worker = async () => {
    while (next < hosts.length && found.size < wanted.length) {
      const ip = hosts[next++];
      const uuid = await probe(ip).catch(() => null);
      if (uuid && wanted.includes(uuid)) found.set(uuid, ip);
    }
  };
  await Promise.all(Array.from({ length: concurrency }, worker));
  return found;
}
