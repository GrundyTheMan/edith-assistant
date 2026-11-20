export type ServerUrlResult = { valid: true; url: string } | { valid: false; message: string };

export const DEFAULT_SERVER_URL = 'http://192.168.4.82:11434';

/**
 * Trims, adds `http://` when there is no scheme, strips trailing slashes, and adds `:11434` when there is no port
 * (C04 §1). The default address is only a starting value the owner can change in Settings.
 */
export function normalizeServerUrl(input: string): ServerUrlResult {
  let text = input.trim();
  if (!text) return { valid: false, message: 'Enter your server address, e.g. http://192.168.4.82:11434' };
  if (/\s/.test(text)) return { valid: false, message: "The address can't contain spaces." };
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(text)) text = `http://${text}`;
  const m = /^(https?):\/\/([^/:?#]+|\[[0-9a-f:]+\])(?::(\d{1,5}))?(\/.*)?$/i.exec(text);
  if (!m) return { valid: false, message: "That doesn't look like a server address." };
  const scheme = m[1].toLowerCase();
  const host = m[2];
  if (!/^(\[[0-9a-f:]+\]|[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*)$/i.test(host)) {
    return { valid: false, message: "That doesn't look like a server address." };
  }
  const port = m[3] ? Number(m[3]) : 11434;
  if (port < 1 || port > 65535) return { valid: false, message: 'The port must be between 1 and 65535.' };
  return { valid: true, url: `${scheme}://${host}:${port}` };
}
