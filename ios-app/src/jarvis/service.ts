import { log } from '../core/log';
import type { SecretStore } from '../core/secrets';
import { Store } from '../core/store';
import type { HomeBackend } from '../home/model';
import type { HomeService, ServiceState, SignInForm, SignInResult } from '../home/service';
import { JarvisApi, parseShared, type CallOptions, type FeedResult, type Shared } from './api';
import { JarvisLink } from './link';
import { normalizeCode, parseJarvisAddress } from './names';

const SECRET = 'home.jarvis';
/** `GET /v1/shared` is asked at most this often by the Home tab's refreshes. */
const SHARED_EVERY_MS = 60_000;
export const NEEDS_PAIR = 'Jarvis removed this phone: pair it again.';
export const UNREACHABLE = "Edith can't reach Jarvis. Is the PC on and Jarvis running?";

export interface JarvisServiceDeps {
  secrets: SecretStore;
  api: JarvisApi;
  now?: () => number;
}

/** What later chunks (the printer, the work account) use to talk to Jarvis: authenticated calls that turn a 401 into "pair it again". */
export interface JarvisClient {
  call<T>(method: 'GET' | 'POST', path: string, parse: (json: unknown) => T | null, opts?: CallOptions & { body?: unknown }): Promise<FeedResult<T>>;
  /** A GET that answers bytes (a camera picture). */
  bytes(path: string, opts?: CallOptions): Promise<FeedResult<Uint8Array>>;
  /** What Jarvis shares with this phone, as last read (null before the first answer). */
  sharedNow(): Shared | null;
  /** Asks Jarvis what it shares right now (not throttled) and remembers the answer. */
  readShared(): Promise<FeedResult<Shared>>;
}

/**
 * The connection to Jarvis, the owner's always-on PC (C66a): pairs once with the PC's Tailscale name and a code, keeps the device token in the Keychain, and
 * gives other features an authenticated client. It shows nothing from Jarvis yet and has no devices (C67 adds the printer). An unreachable Jarvis is **not** an
 * error state and not a sign-out (the PC is often off): it only says so in the row's detail. A 401 means Jarvis removed this phone: "pair it again", once, never a loop.
 */
export class JarvisService implements HomeService, JarvisClient {
  readonly id = 'jarvis';
  readonly name = 'Jarvis';
  readonly state = new Store<ServiceState>({ kind: 'signed_out' });
  private link: JarvisLink | null = null;
  private shared: Shared | null = null;
  private reachable = true;
  private sharedAt = 0;
  private readonly now: () => number;

  constructor(private readonly deps: JarvisServiceDeps) {
    this.now = deps.now ?? Date.now;
  }

  signInForm = (): SignInForm => ({
    title: 'Pair with Jarvis',
    intro:
      "On your PC open Jarvis → Settings → Edith and press Pair a phone: it shows the PC's Tailscale name (it ends in .ts.net) and a code that lasts five minutes. Type both here. Tailscale has to be on for this iPhone. Edith keeps only the sign-in Jarvis gives back, in the iPhone Keychain; to forget this phone completely, also remove it in Jarvis's list.",
    fields: [
      { key: 'host', label: "PC's Tailscale name", keyboard: 'url', hint: 'like ben-pc.tail1234.ts.net' },
      { key: 'code', label: 'Pairing code', hint: 'like ABCDE-FGHJK' },
    ],
    submitLabel: 'Pair',
  });

  private detail(): string {
    const name = this.link?.pcName ?? '';
    if (!this.reachable) return `${name} · can't reach it right now`;
    const s = this.shared;
    if (!s) return name;
    const parts = [s.mail > 0 && 'mail', s.calendars > 0 && 'calendar', s.tasks > 0 && 'to-dos', s.printer && 'printer'].filter(Boolean);
    return parts.length > 0 ? `${name} · shares ${parts.join(', ')}` : `${name} · nothing shared yet`;
  }
  private connected() {
    this.state.set({ kind: 'connected', detail: this.detail() });
  }

  async restore(): Promise<void> {
    try {
      const raw = await this.deps.secrets.get(SECRET);
      const link = raw ? JarvisLink.parse(raw) : null;
      if (!link) {
        if (raw) log.warn('jarvis', "the saved pairing couldn't be read");
        return;
      }
      this.link = link;
      this.connected();
    } catch {
      this.link = null;
    }
  }

  async signIn(values: Record<string, string>): Promise<SignInResult> {
    const addr = parseJarvisAddress(values.host ?? '');
    if (!addr.ok) return { kind: 'failed', message: addr.message };
    const code = normalizeCode(values.code ?? '');
    if (!code) return { kind: 'failed', message: 'The pairing code is 10 letters and numbers, like ABCDE-FGHJK.' };
    const before = this.state.get();
    this.state.set({ kind: 'connecting' });
    const back = () => this.state.set(this.link ? (before.kind === 'connecting' ? { kind: 'connected', detail: this.detail() } : before) : { kind: 'signed_out' });
    const r = await this.deps.api.pair(addr.host, addr.port, code);
    switch (r.kind) {
      case 'ok': {
        // Save first, then say connected: the token is the only way back in.
        await this.deps.secrets.set(SECRET, r.link.serialize());
        this.link = r.link;
        this.shared = null;
        this.reachable = true;
        this.connected();
        log.info('jarvis', 'paired');
        await this.refreshShared(true);
        return { kind: 'ok' };
      }
      case 'refused':
        back();
        return { kind: 'failed', message: "Jarvis didn't accept that code. Make a new one on the PC (it lasts 5 minutes)." };
      case 'slowDown':
        back();
        return { kind: 'failed', message: 'Jarvis says to slow down; try again in a minute.' };
      case 'notJarvis':
        back();
        return { kind: 'failed', message: r.message };
      case 'unreachable':
        back();
        return { kind: 'failed', message: `Edith can't reach ${addr.host}. Is Tailscale on for this iPhone, and is Jarvis running with its Tailscale listener switched on?` };
      case 'failed':
        back();
        return { kind: 'failed', message: r.message };
    }
  }

  async disconnect(): Promise<void> {
    this.link = null;
    this.shared = null;
    await this.deps.secrets.delete(SECRET);
    this.state.set({ kind: 'signed_out' });
  }

  /** An authenticated call. A 401 turns the service to "pair it again" (once; the link is dropped from use until the owner pairs). */
  async call<T>(method: 'GET' | 'POST', path: string, parse: (json: unknown) => T | null, opts: CallOptions & { body?: unknown } = {}): Promise<FeedResult<T>> {
    const link = this.link;
    if (!link) return { kind: 'unauthorized' };
    const r = await this.deps.api.call(link, method, path, parse, opts);
    if (r.kind === 'unauthorized' && this.link === link) {
      this.link = null; // never asked again with a token Jarvis has refused
      this.state.set({ kind: 'needs_sign_in', reason: NEEDS_PAIR });
      log.warn('jarvis', 'the phone was removed in Jarvis');
    } else if (r.kind === 'unreachable' || r.kind === 'timeout') {
      if (this.reachable && this.link) {
        this.reachable = false;
        this.connected();
      }
    } else if (r.kind === 'ok' && !this.reachable && this.link) {
      this.reachable = true;
      this.connected();
    }
    return r;
  }

  /** The same 401 and reachability handling as `call`, for bytes. */
  async bytes(path: string, opts: CallOptions = {}): Promise<FeedResult<Uint8Array>> {
    const link = this.link;
    if (!link) return { kind: 'unauthorized' };
    const r = await this.deps.api.bytes(link, path, opts);
    if (r.kind === 'unauthorized' && this.link === link) {
      this.link = null;
      this.state.set({ kind: 'needs_sign_in', reason: NEEDS_PAIR });
    }
    return r;
  }

  sharedNow(): Shared | null {
    return this.shared;
  }

  /** `GET /v1/shared`: what Jarvis shares, for the row's detail. Quiet on any failure. */
  private async refreshShared(force = false): Promise<void> {
    if (!this.link) return;
    if (!force && this.now() - this.sharedAt < SHARED_EVERY_MS) return;
    await this.readShared();
  }

  /** `GET /v1/shared` now: the answer is remembered (and shown in the row) and returned, for callers that need it (the printer page). */
  async readShared(): Promise<FeedResult<Shared>> {
    this.sharedAt = this.now();
    const r = await this.call('GET', '/v1/shared', parseShared, { timeoutMs: 10_000 });
    if (r.kind === 'ok') {
      this.shared = r.value;
      this.connected();
    }
    return r;
  }

  /** No devices yet; Home's refresh is the moment to look at what Jarvis shares again (at most once a minute). */
  backend(): HomeBackend {
    return {
      id: this.id,
      name: this.name,
      timeoutMs: 15_000,
      devices: async () => {
        await this.refreshShared();
        return [];
      },
      state: async () => {
        throw new Error('Jarvis has no devices of its own.');
      },
      command: async () => {
        throw new Error('Jarvis has no devices of its own.');
      },
    };
  }
}
