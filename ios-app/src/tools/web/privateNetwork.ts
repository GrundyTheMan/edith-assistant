/**
 * Keeps the page reader off the owner's own network (C10): the model must not be able to probe the LAN by asking for a page.
 * Works on the host as written; React Native gives no hook into DNS, so a public name that resolves to a private address
 * is not caught here (C42 "As built"). No regexes (L25).
 */

function ipv4Parts(host: string): number[] | null {
  const parts = host.split('.');
  if (parts.length !== 4) return null;
  const nums = parts.map((p) => (p !== '' && p.length <= 3 && p.split('').every((c) => c >= '0' && c <= '9') ? Number(p) : NaN));
  return nums.every((n) => n >= 0 && n <= 255) ? nums : null;
}

function isPrivateV4([a, b]: number[]): boolean {
  return (
    a === 0 ||
    a === 10 ||
    a === 127 ||
    (a === 100 && b >= 64 && b <= 127) ||
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    a >= 224
  );
}

export function isPrivateHost(rawHost: string): boolean {
  let host = rawHost.trim().toLowerCase();
  if (host.startsWith('[') && host.endsWith(']')) host = host.slice(1, -1);
  if (host.endsWith('.')) host = host.slice(0, -1);
  if (host === '') return true;
  if (host.indexOf(':') >= 0) {
    // IPv6 literal: loopback, unspecified, unique-local, link-local, multicast and IPv4-mapped are all refused.
    if (host === '::' || host === '::1') return true;
    if (host.startsWith('fc') || host.startsWith('fd') || host.startsWith('fe8') || host.startsWith('fe9') || host.startsWith('fea') || host.startsWith('feb') || host.startsWith('ff')) return true;
    if (host.startsWith('::ffff:') || host.startsWith('0:0:0:0:0:ffff:')) return true;
    return false;
  }
  const v4 = ipv4Parts(host);
  if (v4) return isPrivateV4(v4);
  if (host.split('.').every((p) => p !== '' && p.split('').every((c) => c >= '0' && c <= '9'))) return true; // 2130706433 and other odd forms
  if (host.indexOf('.') < 0) return true; // single-label names are the local network's
  for (const suffix of ['.localhost', '.local', '.lan', '.internal', '.home.arpa', '.intranet', '.corp']) {
    if (host.endsWith(suffix)) return true;
  }
  return host === 'localhost';
}
