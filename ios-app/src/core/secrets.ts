import * as SecureStore from 'expo-secure-store';

/**
 * Where tokens, keys and passwords live (C48; the iOS Keychain through expo-secure-store). Keys use only letters, digits,
 * `.`, `-` and `_` (SecureStore's rule): `home.meross`, `account.<id>.refresh_token`. Values are strings: services store
 * JSON. A secret is never logged, never put in an error message, and never goes in the key-value settings store.
 */
export interface SecretStore {
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<void>;
  delete(key: string): Promise<void>;
}

/** The Keychain. Not backed up and not moved to another phone. In Expo Go the items belong to Expo Go, so deleting Expo Go forgets them. */
export const keychainSecrets: SecretStore = {
  get: (key) => SecureStore.getItemAsync(key),
  set: (key, value) => SecureStore.setItemAsync(key, value, { keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY }),
  delete: (key) => SecureStore.deleteItemAsync(key),
};

/** A secret store in memory, for tests. */
export function memorySecrets(initial: Record<string, string> = {}): SecretStore & { dump(): Record<string, string> } {
  const map = new Map(Object.entries(initial));
  return {
    get: async (k) => map.get(k) ?? null,
    set: async (k, v) => void map.set(k, v),
    delete: async (k) => void map.delete(k),
    dump: () => Object.fromEntries(map),
  };
}

const KEY_RE = /^[A-Za-z0-9._-]+$/;
/** Throws for a key SecureStore would refuse, so a bad key fails in tests rather than on the phone. */
export function checkSecretKey(key: string): string {
  if (!KEY_RE.test(key)) throw new Error('A secret key may contain only letters, digits, ".", "-" and "_".');
  return key;
}
