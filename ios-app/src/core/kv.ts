import Storage from 'expo-sqlite/kv-store';
import type { KeyValueStore } from '../settings/settings';

/** Settings live in SQLite's key-value store (C40 §3); secrets, when there are any, go in expo-secure-store. */
export const kvStore: KeyValueStore = {
  getItem: (k) => Storage.getItemAsync(k),
  setItem: (k, v) => Storage.setItemAsync(k, v),
  removeItem: async (k) => void (await Storage.removeItemAsync(k)),
};
