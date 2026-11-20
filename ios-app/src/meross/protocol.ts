import { bytesToBase64 } from '../core/bytes';
import { md5, utf8Bytes } from '../core/md5';

/**
 * Meross's cloud protocol as the maintained `meross-iot` library (albertogeniola/MerossIot, 0.4.x) does it, checked against its source for
 * Android's C26 on 2026-11-14 and ported here. Pure functions: signing, message building, trust checks.
 *
 * SECRET is the salt Meross's own app signs sign-in requests with. It is published in that open-source library and is the same for
 * everyone: it isn't a credential of the owner's.
 */
export const SECRET = '23x17ahWarFH6w29';
export const BASE_URL = 'https://iotx-us.meross.com';
export const SYSTEM_ALL = 'Appliance.System.All';
export const GARAGE_STATE = 'Appliance.GarageDoor.State';

/** The `apiStatus` codes acted on (meross-iot's `ErrorCodes`). */
export const Status = {
  OK: 0,
  UNKNOWN_ACCOUNT: 1002,
  WRONG_CREDENTIALS: 1004,
  TOKEN_INVALID: 1019,
  TOKEN_ERROR: 1022,
  REDIRECT_REGION: 1030,
  WRONG_MFA: 1032,
  MFA_REQUIRED: 1033,
  TOKEN_EXPIRED: 1200,
  TOO_MANY_TOKENS: 1301,
} as const;
export const TOKEN_REJECTED: number[] = [Status.TOKEN_INVALID, Status.TOKEN_ERROR, Status.TOKEN_EXPIRED];

const NONCE_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
/** 16 random characters from A–Z and 0–9: the sign-in's nonce. `random` returns 0 ≤ x < 1. */
export function nonce(random: () => number = Math.random): string {
  let s = '';
  for (let i = 0; i < 16; i++) s += NONCE_CHARS[Math.floor(random() * NONCE_CHARS.length)];
  return s;
}

/** The request's data as compact JSON, then Base64. */
export const encodeParams = (params: Record<string, unknown>): string => bytesToBase64(utf8Bytes(JSON.stringify(params)));

/** `md5(SECRET + timestamp-in-ms + nonce + encoded params)`. */
export const requestSign = (timestampMs: number, nonceText: string, encoded: string): string => md5(`${SECRET}${timestampMs}${nonceText}${encoded}`);

/** The body of every HTTP call to the cloud: `{params, sign, timestamp, nonce}`. */
export function envelope(params: Record<string, unknown>, timestampMs: number, nonceText: string) {
  const encoded = encodeParams(params);
  return { params: encoded, sign: requestSign(timestampMs, nonceText, encoded), timestamp: timestampMs, nonce: nonceText };
}

export interface Header {
  from: string;
  messageId: string;
  method: string;
  namespace: string;
  payloadVersion: number;
  sign: string;
  timestamp: number;
  triggerSrc: string;
  uuid?: string;
}

/**
 * A message to a device, `{header, payload}`, with `sign = md5(messageId + key + timestamp)`; `key` is the user key the sign-in returns.
 * Returns the JSON text and the message id (to match the answer).
 */
export function deviceMessage(
  method: 'GET' | 'SET',
  namespace: string,
  payload: Record<string, unknown>,
  key: string,
  from: string,
  nowSeconds: number,
  random: () => number = Math.random,
  uuid?: string,
): { text: string; messageId: string } {
  const messageId = md5(nonce(random));
  const header: Header = {
    from,
    messageId,
    method,
    namespace,
    payloadVersion: 1,
    sign: md5(`${messageId}${key}${nowSeconds}`),
    timestamp: nowSeconds,
    triggerSrc: 'Android',
    ...(uuid ? { uuid } : {}),
  };
  return { text: JSON.stringify({ header, payload }), messageId };
}

/** True when `header.sign` is `md5(messageId + key + timestamp)`: only messages that pass are believed. */
export function verify(header: unknown, key: string): boolean {
  if (!header || typeof header !== 'object') return false;
  const h = header as Record<string, unknown>;
  const id = h.messageId;
  const ts = h.timestamp;
  const sign = h.sign;
  if (typeof id !== 'string' || (typeof ts !== 'number' && typeof ts !== 'string') || typeof sign !== 'string') return false;
  return md5(`${id}${key}${ts}`).toLowerCase() === sign.toLowerCase();
}

/**
 * Whether `domain`, as Meross's sign-in names it, is a Meross address Edith may send an account's credentials to: a host under `meross.com`
 * (an optional `https://` and `:port`), never plain `http://` and never another site (C26's review change, C37).
 */
export function isTrustedDomain(domain: string): boolean {
  const bare = domain.replace(/^https:\/\//i, '').replace(/\/+$/, '');
  return /^(?:[A-Za-z0-9-]+\.)+meross\.com(?::\d{1,5})?$/i.test(bare);
}

/** An API address with a scheme (a redirect gives a bare host name). */
export const apiBase = (domain: string): string => (/^https?:\/\//i.test(domain) ? domain.replace(/\/+$/, '') : `https://${domain.replace(/\/+$/, '')}`);

/** Garage door openers are `msg100`, `msg200`, … */
export const isGarageOpener = (deviceType: string): boolean => deviceType.toLowerCase().startsWith('msg');
