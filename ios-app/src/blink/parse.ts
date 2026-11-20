import type { DeviceEvent } from '../home/model';

/** Blink's three kinds of camera; each has its own addresses for a snapshot. */
export type BlinkKind = 'camera' | 'mini' | 'doorbell';

/** One camera on the account, as Blink's homescreen lists it (C29's `BlinkCamera`). */
export interface BlinkCamera {
  id: string;
  networkId: string;
  name: string;
  kind: BlinkKind;
  /** Blink's product name ("catalina" is the Outdoor, "owl" the Mini): part of some picture addresses. */
  product: string | null;
  /** Where the latest snapshot is, as Blink gives it. */
  thumbnail: string | null;
  /** Blink says only "ok" or "low": nothing is invented from it. */
  lowBattery: boolean | null;
  online: boolean | null;
}

/** One recorded clip from Blink's media list. `mediaPath` is a path on the account's server (C53 plays it). */
export interface BlinkMedia {
  id: string;
  cameraId: string;
  cameraName: string;
  at: number;
  kind: DeviceEvent['kind'];
  mediaPath: string;
}

type Obj = Record<string, unknown>;
const obj = (v: unknown): Obj | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Obj) : null);
/** Text of a string or a number field (Blink's ids are numbers on some accounts). */
const text = (o: Obj | null, key: string): string | null => {
  const v = o?.[key];
  if (typeof v === 'string') return v === '' ? null : v;
  return typeof v === 'number' ? String(v) : null;
};
const list = (o: Obj | null, key: string): Obj[] => (Array.isArray(o?.[key]) ? (o![key] as unknown[]).map(obj).filter((x): x is Obj => x !== null) : []);

/**
 * The cameras in the homescreen answer: `cameras` (Outdoor, Indoor…), `owls` (Mini) and `doorbells`. A camera is offline when its sync module is,
 * or when its own status says so. Every field is optional except the ids.
 */
export function parseHomescreen(body: unknown): BlinkCamera[] | null {
  const h = obj(body);
  if (!h) return null;
  const offlineNetworks = new Set(
    list(h, 'sync_modules')
      .filter((m) => text(m, 'status') === 'offline')
      .map((m) => text(m, 'network_id'))
      .filter((n): n is string => n !== null),
  );
  const out: BlinkCamera[] = [];
  const kinds: [string, BlinkKind][] = [
    ['cameras', 'camera'],
    ['owls', 'mini'],
    ['doorbells', 'doorbell'],
  ];
  for (const [key, kind] of kinds) {
    for (const c of list(h, key)) {
      const id = text(c, 'id');
      const network = text(c, 'network_id');
      if (!id || !network) continue;
      const status = text(c, 'status');
      const battery = text(c, 'battery');
      out.push({
        id,
        networkId: network,
        name: text(c, 'name') ?? `Blink camera ${id}`,
        kind,
        product: text(c, 'type'),
        thumbnail: text(c, 'thumbnail'),
        lowBattery: battery === null ? null : battery === 'low',
        online: offlineNetworks.has(network) || status === 'offline' ? false : status !== null ? true : null,
      });
    }
  }
  return out.sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
}

/**
 * The address of a camera's latest snapshot. Blink gives it three ways: a full path (".jpg" goes on the end), a path that already ends in `&ext=`,
 * or only the time it was taken, from which the path is built.
 */
export function thumbnailPath(accountId: string, camera: BlinkCamera): string | null {
  const t = camera.thumbnail;
  if (!t) return null;
  if (/^\d+$/.test(t)) return `/api/v3/media/accounts/${accountId}/networks/${camera.networkId}/${camera.product ?? 'catalina'}/${camera.id}/thumbnail/thumbnail.jpg?ts=${t}&ext=`;
  if (t.endsWith('&ext=')) return t;
  return `${t}.jpg`;
}

/** Where to ask a camera for a new snapshot. */
export function snapshotPath(accountId: string, camera: BlinkCamera): string {
  switch (camera.kind) {
    case 'mini':
      return `/api/v1/accounts/${accountId}/networks/${camera.networkId}/owls/${camera.id}/thumbnail`;
    case 'doorbell':
      return `/api/v1/accounts/${accountId}/networks/${camera.networkId}/doorbells/${camera.id}/thumbnail`;
    case 'camera':
      return `/network/${camera.networkId}/camera/${camera.id}/thumbnail`;
  }
}

/** The command a snapshot request started: poll with these. Null when Blink gave none. */
export function parseCommand(body: unknown): { id: string; networkId: string | null } | null {
  const o = obj(body);
  const id = text(o, 'id');
  return id ? { id, networkId: text(o, 'network_id') } : null;
}

/** "2026-10-29T08:34:19+00:00", "…+0000" or "…Z", as ms since 1970; null when it isn't a time. */
export function parseTime(t: string | null): number | null {
  if (!t) return null;
  const m = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(\.\d+)?(Z|[+-]\d{2}:?\d{2})$/.exec(t.trim());
  if (!m) return null;
  const zone = m[3] === 'Z' ? 'Z' : m[3].length === 5 ? `${m[3].slice(0, 3)}:${m[3].slice(3)}` : m[3];
  const ms = Date.parse(`${m[1]}${m[2] ?? ''}${zone}`);
  return Number.isFinite(ms) ? ms : null;
}

/** The time as Blink's media list wants it: "2026-10-29T08:34:19+0000", in UTC, without milliseconds. */
export function sinceText(ms: number): string {
  return `${new Date(ms).toISOString().slice(0, 19)}+0000`;
}

function mediaKind(source: string | null): DeviceEvent['kind'] {
  switch (source) {
    case 'doorbell':
    case 'ding':
      return 'ring';
    case 'liveview':
    case 'on_demand':
      return 'live';
    default:
      return 'motion'; // Blink records on motion; an unnamed source is that
  }
}

/** Blink's media list as clips, newest first; deleted ones and snapshots are skipped. */
export function parseMedia(body: unknown): BlinkMedia[] | null {
  const root = obj(body);
  if (!root) return null;
  const out: BlinkMedia[] = [];
  for (const m of list(root, 'media')) {
    const id = text(m, 'id');
    const path = text(m, 'media');
    const at = parseTime(text(m, 'created_at'));
    if (!id || !path || at === null) continue;
    if (m.deleted === true || text(m, 'source') === 'snapshot') continue;
    out.push({ id, cameraId: text(m, 'device_id') ?? '', cameraName: text(m, 'device_name') ?? 'Blink camera', at, kind: mediaKind(text(m, 'source')), mediaPath: path });
  }
  return out.sort((a, b) => b.at - a.at);
}

/** The `code` in the address Blink's sign-in ends on; null when there is none. */
export function codeFrom(location: string | null): string | null {
  if (!location) return null;
  const query = location.includes('?') ? location.slice(location.indexOf('?') + 1).split('#')[0] : '';
  for (const part of query.split('&')) {
    const i = part.indexOf('=');
    if (i > 0 && part.slice(0, i) === 'code') {
      const v = part.slice(i + 1);
      return v ? decodeURIComponent(v) : null;
    }
  }
  return null;
}

/** The CSRF token in the sign-in page: the JSON in `<script id="oauth-args" type="application/json">` has a `csrf-token`. */
export function csrfToken(html: string): string | null {
  const m = /<script[^>]*id=["']oauth-args["'][^>]*>([\s\S]*?)<\/script>/i.exec(html);
  if (!m) return null;
  try {
    const t = obj(JSON.parse(m[1]))?.['csrf-token'];
    return typeof t === 'string' && t ? t : null;
  } catch {
    return null;
  }
}
