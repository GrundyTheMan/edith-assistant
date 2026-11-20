import { utf8Bytes } from './md5';
import { sha256Bytes } from './sha256';

const BLOCK = 64;

/** HMAC-SHA256 (RFC 2104) over the plain TypeScript SHA-256, so it is synchronous. Tested against RFC 4231's vectors and Node's `crypto`. */
export function hmacSha256(key: Uint8Array, message: Uint8Array): Uint8Array {
  const k = new Uint8Array(BLOCK);
  k.set(key.length > BLOCK ? sha256Bytes(key) : key);
  const inner = new Uint8Array(BLOCK + message.length);
  const outer = new Uint8Array(BLOCK + 32);
  for (let i = 0; i < BLOCK; i++) {
    inner[i] = k[i] ^ 0x36;
    outer[i] = k[i] ^ 0x5c;
  }
  inner.set(message, BLOCK);
  outer.set(sha256Bytes(inner), BLOCK);
  return sha256Bytes(outer);
}

export const toHex = (bytes: Uint8Array): string => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');

/** Lower-case hex of HMAC-SHA256 with a text key and a text message (both UTF-8). */
export const hmacSha256Hex = (key: string, message: string): string => toHex(hmacSha256(utf8Bytes(key), utf8Bytes(message)));
