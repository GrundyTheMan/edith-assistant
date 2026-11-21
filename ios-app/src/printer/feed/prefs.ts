import { Store } from '../../core/store';
import type { KeyValueStore } from '../../settings/settings';
import { ConsoleHistory } from './consoleHistory';

/** The Printer page's cards, in the order the phone shows them (C39 §8.3). The ids are what is remembered. */
export const CARD_IDS = ['status', 'camera', 'temperatures', 'toolhead', 'fans', 'limits', 'sensors', 'slots', 'macros', 'files', 'objects', 'queue', 'history', 'console', 'mesh', 'system'] as const;
export type CardId = (typeof CARD_IDS)[number];

/** Open the first time: the rest start folded. */
export const EXPANDED_AT_FIRST: ReadonlySet<string> = new Set(['status', 'camera', 'temperatures']);
export const COLLAPSED_AT_FIRST: ReadonlySet<string> = new Set(CARD_IDS.filter((id) => !EXPANDED_AT_FIRST.has(id)));

const KEY = 'printer_collapsed';
const HIDE_KEY = 'printer_console_hide_values';
const HISTORY_KEY = 'printer_console_history';
const CAMERA_KEY = 'printer_camera';

/** What the owner chose on the Printer page and wants kept (C39 §9.2): which cards are folded. Until they change one, every card except Status, Camera and Temperatures. */
export class PrinterPrefs {
  readonly collapsed = new Store<ReadonlySet<string>>(COLLAPSED_AT_FIRST);
  /** The console hides the printer's "// value:" chatter unless the owner switched that off. */
  readonly consoleHideValues = new Store<boolean>(true);
  /** The last 50 lines the owner sent from the console, newest first. */
  readonly consoleHistory = new Store<string[]>([]);
  /** The camera the owner last chose on the Camera card (null: the first one). */
  readonly camera = new Store<string | null>(null);
  constructor(private readonly store: KeyValueStore) {}

  async load(): Promise<void> {
    try {
      const raw = JSON.parse((await this.store.getItem(KEY)) ?? 'null') as unknown;
      if (Array.isArray(raw)) this.collapsed.set(new Set(raw.filter((x): x is string => typeof x === 'string')));
    } catch {
      // damaged: the defaults
    }
    try {
      const hide = JSON.parse((await this.store.getItem(HIDE_KEY)) ?? 'null') as unknown;
      if (typeof hide === 'boolean') this.consoleHideValues.set(hide);
      const history = JSON.parse((await this.store.getItem(HISTORY_KEY)) ?? 'null') as unknown;
      if (Array.isArray(history)) this.consoleHistory.set(history.filter((x): x is string => typeof x === 'string' && x.trim() !== '').slice(0, ConsoleHistory.MAX));
      const camera = await this.store.getItem(CAMERA_KEY);
      if (camera) this.camera.set(camera);
    } catch {
      // damaged: the defaults
    }
  }

  async setConsoleHideValues(on: boolean): Promise<void> {
    this.consoleHideValues.set(on);
    await this.store.setItem(HIDE_KEY, JSON.stringify(on)).catch(() => {});
  }

  async pushConsoleHistory(line: string): Promise<void> {
    const next = ConsoleHistory.push(this.consoleHistory.get(), line);
    this.consoleHistory.set(next);
    await this.store.setItem(HISTORY_KEY, JSON.stringify(next)).catch(() => {});
  }

  async setCamera(id: string): Promise<void> {
    this.camera.set(id);
    await this.store.setItem(CAMERA_KEY, id).catch(() => {});
  }

  async setCollapsed(card: string, collapsed: boolean): Promise<void> {
    const next = new Set(this.collapsed.get());
    if (collapsed) next.add(card);
    else next.delete(card);
    this.collapsed.set(next);
    await this.store.setItem(KEY, JSON.stringify([...next])).catch(() => {});
  }
}
