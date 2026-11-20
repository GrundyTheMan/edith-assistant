import { Store } from '../core/store';
import type { KeyValueStore } from '../settings/settings';
import { AlertChoices, type AlertKind } from './model';

const KEY = 'alerts_on';

/** The owner's alert switches (Settings → Home → Alerts, C72). All off until switched on; kept as a JSON list of `service:kind` in the key-value store. */
export class AlertPrefs {
  readonly choices = new Store<AlertChoices>(new AlertChoices());
  constructor(private readonly store: KeyValueStore) {}

  async load(): Promise<void> {
    try {
      const raw = JSON.parse((await this.store.getItem(KEY)) ?? '[]') as unknown;
      this.choices.set(new AlertChoices(new Set(Array.isArray(raw) ? raw.filter((k): k is string => typeof k === 'string') : [])));
    } catch {
      this.choices.set(new AlertChoices()); // damaged: everything off
    }
  }

  async set(backendId: string, kind: AlertKind, on: boolean): Promise<void> {
    const next = this.choices.get().with(backendId, kind, on);
    this.choices.set(next);
    await this.store.setItem(KEY, JSON.stringify([...next.on])).catch(() => {});
  }
}
