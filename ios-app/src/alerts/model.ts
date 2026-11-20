import type { DeviceEvent } from '../home/model';

/** What an alert is about: the three kinds the owner can switch on per service (C33, C72). */
export type AlertKind = 'doorbell' | 'person' | 'motion';
export const ALERT_KINDS: AlertKind[] = ['doorbell', 'person', 'motion'];
export const KIND_LABELS: Record<AlertKind, string> = { doorbell: 'Doorbell rings', person: 'A person is seen', motion: 'Motion' };

/** The alert kind a camera event counts as; null for events that are never alerts (the owner's own live view). */
export function alertKindOf(kind: DeviceEvent['kind']): AlertKind | null {
  switch (kind) {
    case 'ring':
      return 'doorbell';
    case 'person':
      return 'person';
    case 'motion':
    case 'other':
      return 'motion';
    case 'live':
      return null;
  }
}

/** Which kinds each service can alert on (Ring tells doorbell, person and motion apart; Blink's recordings are motion). The Demo home's doorbell is for trying the path. */
export const ALERT_CATALOG: Record<string, AlertKind[]> = {
  ring: ['doorbell', 'person', 'motion'],
  blink: ['motion'],
};
export const DEMO_ALERT_KINDS: AlertKind[] = ['doorbell'];
export const alertKindsOf = (backendId: string, demoAllowed = false): AlertKind[] => ALERT_CATALOG[backendId] ?? (demoAllowed && backendId === 'demo' ? DEMO_ALERT_KINDS : []);

const SEP = ':';
/** The owner's alert switches: a set of `service:kind`. Everything is off until the owner turns it on. */
export class AlertChoices {
  constructor(readonly on: ReadonlySet<string> = new Set()) {}
  static key = (backendId: string, kind: AlertKind) => `${backendId}${SEP}${kind}`;
  enabled(backendId: string, kind: AlertKind): boolean {
    return this.on.has(AlertChoices.key(backendId, kind));
  }
  /** The services with at least one kind on. */
  get backends(): string[] {
    return [...new Set([...this.on].map((k) => k.slice(0, k.indexOf(SEP))))];
  }
  get any(): boolean {
    return this.on.size > 0;
  }
  with(backendId: string, kind: AlertKind, on: boolean): AlertChoices {
    const next = new Set(this.on);
    if (on) next.add(AlertChoices.key(backendId, kind));
    else next.delete(AlertChoices.key(backendId, kind));
    return new AlertChoices(next);
  }
}

/** One thing a camera or doorbell did, as a service reports it. `key` is unique per service (a clip's id, or device and time). */
export interface Activity {
  key: string;
  backend: string;
  device: string;
  at: number;
  kind: DeviceEvent['kind'];
}

/** One notification to show. */
export interface Alert {
  backend: string;
  device: string;
  kind: AlertKind;
  at: number;
  key: string;
}

/** Where the watcher asks what happened (implemented by `Home`). Throws words for the owner when a service can't answer. */
export interface ActivitySource {
  activity(backendId: string, sinceMs: number): Promise<Activity[]>;
}
