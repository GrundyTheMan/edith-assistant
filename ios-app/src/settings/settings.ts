import { DEFAULT_SERVER_URL } from './serverUrl';

/** Key names exactly as the registry in 01-architecture §5.3 (the ones this chunk uses). */
export interface Settings {
  serverUrl: string;
  model: string | null;
  speakReplies: boolean;
  speechRate: number;
  currentConversationId: number | null;
}

export const DEFAULT_SETTINGS: Settings = {
  serverUrl: DEFAULT_SERVER_URL,
  model: null,
  speakReplies: true,
  speechRate: 1.0,
  currentConversationId: null,
};

/** Async string key-value storage. The app uses `expo-sqlite/kv-store`; tests use a Map. */
export interface KeyValueStore {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
  removeItem(key: string): Promise<void>;
}

const KEYS: Record<keyof Settings, string> = {
  serverUrl: 'server_url',
  model: 'model',
  speakReplies: 'speak_replies',
  speechRate: 'speech_rate',
  currentConversationId: 'current_conversation_id',
};

export class SettingsRepository {
  private listeners = new Set<(s: Settings) => void>();
  private cache: Settings | null = null;

  constructor(private readonly store: KeyValueStore) {}

  /** The settings as last read (defaults before the first read): lets synchronous code such as speech use them. */
  snapshot(): Settings {
    return this.cache ?? DEFAULT_SETTINGS;
  }

  async current(): Promise<Settings> {
    if (this.cache) return this.cache;
    const read = async (k: keyof Settings) => this.store.getItem(KEYS[k]);
    const [serverUrl, model, speak, rate, conv] = await Promise.all([
      read('serverUrl'),
      read('model'),
      read('speakReplies'),
      read('speechRate'),
      read('currentConversationId'),
    ]);
    const rateNum = rate === null ? NaN : Number(rate);
    const convNum = conv === null ? NaN : Number(conv);
    this.cache = {
      serverUrl: serverUrl ?? DEFAULT_SETTINGS.serverUrl,
      model: model ?? null,
      speakReplies: speak === null ? DEFAULT_SETTINGS.speakReplies : speak === 'true',
      speechRate: Number.isFinite(rateNum) ? rateNum : DEFAULT_SETTINGS.speechRate,
      currentConversationId: Number.isFinite(convNum) ? convNum : null,
    };
    return this.cache;
  }

  async update(transform: (s: Settings) => Settings): Promise<Settings> {
    const before = await this.current();
    const after = transform(before);
    for (const k of Object.keys(KEYS) as (keyof Settings)[]) {
      if (before[k] === after[k]) continue;
      const v = after[k];
      if (v === null) await this.store.removeItem(KEYS[k]);
      else await this.store.setItem(KEYS[k], String(v));
    }
    this.cache = after;
    this.listeners.forEach((l) => l(after));
    return after;
  }

  subscribe(listener: (s: Settings) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }
}

export function memoryStore(initial: Record<string, string> = {}): KeyValueStore {
  const map = new Map(Object.entries(initial));
  return {
    getItem: async (k) => map.get(k) ?? null,
    setItem: async (k, v) => void map.set(k, v),
    removeItem: async (k) => void map.delete(k),
  };
}
