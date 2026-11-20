import { Store } from '../../core/store';
import type { KeyValueStore } from '../../settings/settings';

/** The Printer page's cards, in the order the phone shows them (C39 §8.3). The ids are what is remembered. */
export const CARD_IDS = ['status', 'camera', 'temperatures', 'toolhead', 'fans', 'limits', 'sensors', 'slots', 'macros', 'files', 'objects', 'queue', 'history', 'console', 'mesh', 'system'] as const;
export type CardId = (typeof CARD_IDS)[number];

/** Open the first time: the rest start folded. */
export const EXPANDED_AT_FIRST: ReadonlySet<string> = new Set(['status', 'camera', 'temperatures']);
export const COLLAPSED_AT_FIRST: ReadonlySet<string> = new Set(CARD_IDS.filter((id) => !EXPANDED_AT_FIRST.has(id)));

const KEY = 'printer_collapsed';

/** What the owner chose on the Printer page and wants kept (C39 §9.2): which cards are folded. Until they change one, every card except Status, Camera and Temperatures. */
export class PrinterPrefs {
  readonly collapsed = new Store<ReadonlySet<string>>(COLLAPSED_AT_FIRST);
  constructor(private readonly store: KeyValueStore) {}

  async load(): Promise<void> {
    try {
      const raw = JSON.parse((await this.store.getItem(KEY)) ?? 'null') as unknown;
      if (Array.isArray(raw)) this.collapsed.set(new Set(raw.filter((x): x is string => typeof x === 'string')));
    } catch {
      // damaged: the defaults
    }
  }

  async setCollapsed(card: string, collapsed: boolean): Promise<void> {
    const next = new Set(this.collapsed.get());
    if (collapsed) next.add(card);
    else next.delete(card);
    this.collapsed.set(next);
    await this.store.setItem(KEY, JSON.stringify([...next])).catch(() => {});
  }
}
