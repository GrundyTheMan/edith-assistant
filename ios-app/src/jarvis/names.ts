/** The default port of Jarvis's Tailscale listener (`jarvis-tailscale-listener-spec.md` §2). */
export const DEFAULT_PORT = 47822;

export type JarvisAddress = { ok: true; host: string; port: number } | { ok: false; message: string };

const NOT_TS = "Use the PC's Tailscale name, which ends in .ts.net. Jarvis shows it next to the pairing code.";

/**
 * What the owner typed for the PC: `ben-pc.tail1234.ts.net`, with an optional `:port`; a pasted `https://…/` is cut down. Lower-cased. Only a **`.ts.net` name**: the
 * certificate on the Tailscale listener is for that name, so an IP address, a LAN name or plain `http://` could never be trusted (and would be refused by the secure client anyway).
 */
export function parseJarvisAddress(input: string): JarvisAddress {
  let text = input.trim();
  if (!text) return { ok: false, message: "Type the PC's Tailscale name, like ben-pc.tail1234.ts.net." };
  if (/^http:\/\//i.test(text)) return { ok: false, message: 'Jarvis is reached over HTTPS only. ' + NOT_TS };
  text = text.replace(/^https:\/\//i, '').replace(/[/?#].*$/, '');
  const m = /^([a-z0-9][a-z0-9.-]*[a-z0-9]|[a-z0-9])(?::(\d{1,5}))?$/i.exec(text);
  if (!m) return { ok: false, message: `"${input.trim().slice(0, 60)}" isn't a name Edith can use. ${NOT_TS}` };
  const host = m[1].toLowerCase().replace(/\.$/, '');
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host) || !/\.ts\.net$/.test(host) || host === '.ts.net' || host.split('.').length < 4) return { ok: false, message: NOT_TS };
  const port = m[2] ? Number(m[2]) : DEFAULT_PORT;
  if (port < 1024 || port > 65535) return { ok: false, message: 'The port has to be between 1024 and 65535.' };
  return { ok: true, host, port };
}

/** The pairing code as Jarvis made it: 10 characters from its alphabet, typed with or without the dash, in any case, with spaces. Returns the form the proof uses (no dash, upper case) or null. */
export function normalizeCode(input: string): string | null {
  const code = input.replace(/[\s-]/g, '').toUpperCase();
  return /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{10}$/.test(code) ? code : null;
}
