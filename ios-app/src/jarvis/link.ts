/**
 * What pairing leaves on the phone: where Jarvis is, and the device token. **The token never appears in a log, an error, a notice or `JSON.stringify`**: it is
 * held privately and only `bearer()` and `serialize()` (the Keychain text) can read it.
 */
export class JarvisLink {
  readonly host: string;
  readonly port: number;
  readonly pcName: string;
  private readonly token: string;

  constructor(host: string, port: number, pcName: string, token: string) {
    this.host = host;
    this.port = port;
    this.pcName = pcName;
    this.token = token;
  }

  get base(): string {
    return `https://${this.host}:${this.port}`;
  }
  /** The `Authorization` header value. */
  bearer(): string {
    return `Bearer ${this.token}`;
  }
  /** The text kept in the Keychain (`home.jarvis`): the only place the token is written out. */
  serialize(): string {
    return JSON.stringify({ host: this.host, port: this.port, pcName: this.pcName, token: this.token });
  }
  static parse(text: string): JarvisLink | null {
    try {
      const o = JSON.parse(text) as Record<string, unknown>;
      if (typeof o.host === 'string' && typeof o.port === 'number' && typeof o.pcName === 'string' && typeof o.token === 'string' && o.token) return new JarvisLink(o.host, o.port, o.pcName, o.token);
    } catch {
      // damaged: the same as none
    }
    return null;
  }
  toJSON() {
    return { host: this.host, port: this.port, pcName: this.pcName, token: '[hidden]' };
  }
  toString() {
    return `JarvisLink(${this.host}:${this.port})`;
  }
}
