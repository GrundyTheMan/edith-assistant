import { DEFAULT_SERVER_URL } from './serverUrl';

/** Key names exactly as the registry in 01-architecture §5.3 (the ones this chunk uses). */
export interface Settings {
  serverUrl: string;
  model: string | null;
  speakReplies: boolean;
  speechRate: number;
  currentConversationId: number | null;
  /** How many of the owner's recent attachments are kept on the phone (C16 §2; 0 to 50). */
  keepRecentAttachments: number;
  /** Conversation mode (C57): how long Edith listens for a follow-up after speaking, 0 to 15 seconds (0: the conversation ends after each reply). */
  followUpSeconds: number;
  /** 0..1: higher hears quieter speech. */
  conversationSensitivity: number;
  /** Silence that ends what the owner said, 700 to 2000 ms. */
  conversationPauseMs: number;
  /** Turn speaking on for the length of a conversation when it is off. */
  speakDuringConversation: boolean;
}

export const DEFAULT_SETTINGS: Settings = {
  serverUrl: DEFAULT_SERVER_URL,
  model: null,
  speakReplies: true,
  speechRate: 1.0,
  currentConversationId: null,
  keepRecentAttachments: 10,
  followUpSeconds: 8,
  conversationSensitivity: 0.5,
  conversationPauseMs: 1100,
  speakDuringConversation: true,
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
  keepRecentAttachments: 'keep_recent_attachments',
  followUpSeconds: 'follow_up_seconds',
  conversationSensitivity: 'conversation_sensitivity',
  conversationPauseMs: 'conversation_pause_ms',
  speakDuringConversation: 'speak_during_conversation',
};

const clampNumber = (raw: string | null, min: number, max: number, fallback: number): number => {
  const n = raw === null || raw.trim() === '' ? NaN : Number(raw);
  return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : fallback;
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
    const [serverUrl, model, speak, rate, conv, keep, followUp, sensitivity, pause, speakDuring] = await Promise.all([
      read('serverUrl'),
      read('model'),
      read('speakReplies'),
      read('speechRate'),
      read('currentConversationId'),
      read('keepRecentAttachments'),
      read('followUpSeconds'),
      read('conversationSensitivity'),
      read('conversationPauseMs'),
      read('speakDuringConversation'),
    ]);
    const keepNum = keep === null ? NaN : Number(keep);
    const rateNum = rate === null ? NaN : Number(rate);
    const convNum = conv === null ? NaN : Number(conv);
    this.cache = {
      serverUrl: serverUrl ?? DEFAULT_SETTINGS.serverUrl,
      model: model ?? null,
      speakReplies: speak === null ? DEFAULT_SETTINGS.speakReplies : speak === 'true',
      speechRate: Number.isFinite(rateNum) ? rateNum : DEFAULT_SETTINGS.speechRate,
      currentConversationId: Number.isFinite(convNum) ? convNum : null,
      keepRecentAttachments: Number.isFinite(keepNum) ? Math.min(50, Math.max(0, Math.round(keepNum))) : DEFAULT_SETTINGS.keepRecentAttachments,
      followUpSeconds: Math.round(clampNumber(followUp, 0, 15, DEFAULT_SETTINGS.followUpSeconds)),
      conversationSensitivity: clampNumber(sensitivity, 0, 1, DEFAULT_SETTINGS.conversationSensitivity),
      conversationPauseMs: Math.round(clampNumber(pause, 700, 2000, DEFAULT_SETTINGS.conversationPauseMs)),
      speakDuringConversation: speakDuring === null ? DEFAULT_SETTINGS.speakDuringConversation : speakDuring === 'true',
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
