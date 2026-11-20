import { KIND_LABELS, type Alert, type AlertKind } from './model';

/** The words of a notification (C72 §5). */
export function alertTitle(kind: AlertKind): string {
  switch (kind) {
    case 'doorbell':
      return 'Someone rang the doorbell';
    case 'person':
      return 'A person was seen';
    case 'motion':
      return 'Motion';
  }
}

/** What goes into one local notification. `identifier` is the alert's key, so showing it again with a picture replaces it quietly. */
export interface AlertContent {
  identifier: string;
  title: string;
  body: string;
  /** iOS has no channels: the category tells the kinds apart (Doorbell, Person, Motion) so the owner can see them in the app's notification settings. */
  categoryIdentifier: string;
  /** Doorbells ask to be delivered at once; never Critical (that is an Apple special entitlement). */
  interruptionLevel: 'timeSensitive' | 'active';
  /** What a tap does: open the Clips list. Events carry no clip id; the newest is at the top. */
  data: { open: 'clips'; backend: string; key: string };
}

export function alertContent(alert: Alert): AlertContent {
  return {
    identifier: alert.key,
    title: alertTitle(alert.kind),
    body: alert.device,
    categoryIdentifier: `alerts_${alert.kind}`,
    interruptionLevel: alert.kind === 'doorbell' ? 'timeSensitive' : 'active',
    data: { open: 'clips', backend: alert.backend, key: alert.key },
  };
}

export type NotificationPermission = 'granted' | 'denied' | 'undetermined';

/** The phone's local notifications (expo-notifications in the app, a fake in tests). */
export interface Notifier {
  permission(): Promise<NotificationPermission>;
  /** Asks the owner (the first time only); true when allowed. */
  request(): Promise<boolean>;
  /** Shows the alert now; with `pictureBase64` (a JPEG) it replaces the earlier one with a picture and no second sound. */
  show(alert: Alert, pictureBase64?: string): Promise<void>;
  /** Calls `open` when the owner taps a notification (also if it was tapped before the app was running). Returns how to stop listening. */
  onOpen(open: () => void): () => void;
}

const VERBS: Record<AlertKind, string> = { doorbell: 'rang', person: 'saw a person', motion: 'saw motion' };
const pad = (n: number) => String(n).padStart(2, '0');
export const clockText = (at: number) => {
  const d = new Date(at);
  return `${d.getHours() % 12 || 12}:${pad(d.getMinutes())} ${d.getHours() < 12 ? 'AM' : 'PM'}`;
};

/** "Since 3:10 PM: Front door rang (2), Backyard saw a person" (C72 §3). Null when there is nothing to say. */
export function summaryText(alerts: Alert[], since: number): string | null {
  if (alerts.length === 0) return null;
  const counts = new Map<string, { device: string; kind: AlertKind; n: number; last: number }>();
  for (const a of alerts) {
    const k = `${a.device}|${a.kind}`;
    const c = counts.get(k);
    if (c) {
      c.n++;
      c.last = Math.max(c.last, a.at);
    } else counts.set(k, { device: a.device, kind: a.kind, n: 1, last: a.at });
  }
  const parts = [...counts.values()].sort((a, b) => b.last - a.last).map((c) => `${c.device} ${VERBS[c.kind]}${c.n > 1 ? ` (${c.n})` : ''}`);
  return `Since ${clockText(since)}: ${parts.join(', ')}`;
}

export { KIND_LABELS };
