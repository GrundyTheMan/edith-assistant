import type { DeviceEvent } from '../home/model';

/** One camera or doorbell on the account, as Ring lists it (ported from C27's `RingCamera`). */
export interface RingCamera {
  id: string;
  name: string;
  doorbell: boolean;
  locationId: string | null;
  offline: boolean;
  battery: number | null;
  /** Ring's "Record Motion" is off: it then gives no pictures (the library's `snapshotsAreBlocked`). */
  motionDetection: boolean;
}

type Obj = Record<string, unknown>;
const obj = (v: unknown): Obj | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Obj) : null);
const text = (o: Obj | null, key: string): string | null => (typeof o?.[key] === 'string' ? (o[key] as string) : null);
const idText = (v: unknown): string | null => (typeof v === 'string' && v ? v : typeof v === 'number' ? String(v) : null);
const list = (o: Obj | null, key: string): unknown[] => (Array.isArray(o?.[key]) ? (o![key] as unknown[]) : []);
const num = (v: unknown): number | null => {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v))) return Number(v);
  return null;
};

/** The library's `getBatteryLevel`: the lower of the two batteries; null for a wired camera. */
export function batteryOf(d: Obj): number | null {
  const levels = [num(d.battery_life), num(d.battery_life_2)].filter((n): n is number => n !== null);
  const health = obj(d.health);
  const none =
    health !== null &&
    ['battery_percentage', 'battery_present', 'second_battery_percentage'].every((k) => {
      const v = health[k];
      return !(v === true || (typeof v === 'number' && v !== 0));
    });
  if (levels.length === 0 || none) return null;
  return Math.max(0, Math.min(100, Math.trunc(Math.min(...levels))));
}

function camera(e: unknown, doorbell: boolean): RingCamera | null {
  const d = obj(e);
  if (!d) return null;
  const id = idText(d.id);
  if (!id) return null;
  const kind = text(d, 'kind') ?? '';
  const settings = obj(d.settings);
  return {
    id,
    name: text(d, 'description') ?? 'Ring camera',
    doorbell: doorbell || kind.startsWith('doorbell'),
    locationId: idText(d.location_id),
    offline: text(obj(d.alerts), 'connection') === 'offline',
    battery: batteryOf(d),
    motionDetection: settings?.motion_detection_enabled !== false,
  };
}

/** The cameras and doorbells from `ring_devices` (not the chimes, base stations or sensors). `null`: the answer wasn't a device list. */
export function parseCameras(json: unknown): RingCamera[] | null {
  const root = obj(json);
  if (!root) return null;
  const all = [
    ...list(root, 'doorbots').map((e) => camera(e, true)),
    ...list(root, 'stickup_cams').map((e) => camera(e, false)),
    ...list(root, 'authorized_doorbots').map((e) => camera(e, true)),
    ...list(root, 'other')
      .filter((e) => text(obj(e), 'kind') === 'onvif_camera')
      .map((e) => camera(e, false)),
  ].filter((c): c is RingCamera => c !== null);
  const seen = new Set<string>();
  return all.filter((c) => (seen.has(c.id) ? false : (seen.add(c.id), true)));
}

/** The kind of an event: a person beats the rest; `live` is a live view starting; anything else is dropped (null). */
export function eventKind(d: Obj): DeviceEvent['kind'] | null {
  const cv = obj(d.cv_properties);
  if (cv?.person_detected === true || text(cv, 'detection_type') === 'human') return 'person';
  switch (text(d, 'kind')) {
    case 'ding':
      return 'ring';
    case 'motion':
      return 'motion';
    case 'on_demand':
      return 'live';
    default:
      return null;
  }
}

/** The events of one camera (`created_at` is ISO text), kept when at or after `sinceMs`, newest first. `null`: not an event list. */
export function parseEvents(json: unknown, cam: RingCamera, sinceMs: number): DeviceEvent[] | null {
  const root = obj(json);
  if (!root) return null;
  const out: DeviceEvent[] = [];
  for (const e of list(root, 'events')) {
    const d = obj(e);
    if (!d) continue;
    const at = Date.parse(text(d, 'created_at') ?? '');
    const kind = eventKind(d);
    if (!Number.isFinite(at) || kind === null || at < sinceMs) continue;
    out.push({ id: idText(d.id) ?? `${cam.id}:${at}`, deviceId: `ring:${cam.id}`, deviceName: cam.name, kind, at });
  }
  return out.sort((a, b) => b.at - a.at);
}

/** One clip as Ring's video search lists it. `hadPlan` false: it was recorded without a Ring Protect plan, so nothing was kept. */
export interface RingClipRow {
  id: string;
  cameraId: string;
  cameraName: string;
  at: number;
  kind: DeviceEvent['kind'];
  seconds: number | null;
  hadPlan: boolean;
  /** An address the search listed (signed, expires): kept only as a last resort for playing. */
  url: string | null;
}

/** `video_search/history`: `created_at` is ms or ISO text depending on the account. `null`: not a clip list. */
export function parseClipRows(json: unknown, cam: RingCamera): RingClipRow[] | null {
  const root = obj(json);
  if (!root) return null;
  const out: RingClipRow[] = [];
  for (const e of list(root, 'video_search')) {
    const d = obj(e);
    if (!d) continue;
    const id = idText(d.ding_id);
    const raw = d.created_at;
    const at = typeof raw === 'number' ? raw : Date.parse(typeof raw === 'string' ? raw : '');
    if (!id || !Number.isFinite(at)) continue;
    const url = ['lq_url', 'hq_url', 'untranscoded_url'].map((k) => text(d, k)).find((u) => u !== null && u.startsWith('https://')) ?? null;
    const seconds = num(d.duration);
    out.push({ id, cameraId: cam.id, cameraName: cam.name, at, kind: eventKind(d) ?? 'other', seconds: seconds === null ? null : Math.trunc(seconds), hadPlan: d.had_subscription !== false, url });
  }
  return out;
}
