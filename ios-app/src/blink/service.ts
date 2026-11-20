import { bytesToBase64 } from '../core/bytes';
import { log } from '../core/log';
import type { SecretStore } from '../core/secrets';
import { Store } from '../core/store';
import type { ClipSource, ClipsPage, DeviceCommand, DeviceEvent, DeviceState, HomeBackend, HomeDevice } from '../home/model';
import type { HomeService, ServiceState, SignInForm, SignInResult } from '../home/service';
import { BlinkApi, restOf, type BlinkReply, type BlinkSignInSession, type BlinkTier, type BlinkTokens } from './api';
import { thumbnailPath, type BlinkCamera, type BlinkMedia } from './parse';

const SECRET = 'home.blink';
const PREFIX = 'blink:';
const NEEDS_SIGN_IN = 'Blink asks you to sign in again (Settings → Home).';
const MAX_TRIES = 6;
const BUSY_TRIES = 4;
const BUSY_WAIT_MS = 3_000;
const COMMAND_POLLS = 20;
const POLL_MS = 1_000;
const THUMBNAIL_POLLS = 5;
const THUMBNAIL_WAIT_MS = 2_000;
const MAX_MEDIA_PAGES = 5;

interface Saved {
  refreshToken: string;
  hardwareId: string;
  tier: string;
  accountId: string;
}

export interface BlinkServiceDeps {
  secrets: SecretStore;
  api: BlinkApi;
  now?: () => number;
  /** A fresh random UUID; the service makes it upper case, as Blink's own app does. */
  uuid: () => string;
  /** Waits between polls; tests pass one that does not wait. */
  pause?: (ms: number) => Promise<void>;
}

class BlinkFailure extends Error {}

/**
 * Blink cameras on iOS (C52, ported from C29): the owner's Blink account, signed in with an email, a password and a code that are typed once, used once and
 * never kept. Only Blink's refresh token, this phone's hardware id (a UUID, made once: Blink rejects any other) and the account's server are saved in the
 * Keychain (`home.blink`). **Fragile**: when Blink changes something, Edith says Blink can't be reached or wants a new sign-in; the other services keep
 * working. Like Ring's, the refresh token is treated as rotating: each new one is saved *before* the access token it came with is used, and refreshes
 * never overlap.
 */
export class BlinkService implements HomeService {
  readonly id = 'blink';
  readonly name = 'Blink';
  readonly state = new Store<ServiceState>({ kind: 'signed_out' });
  private saved: Saved | null = null;
  private access: { token: string; expiresAt: number } | null = null;
  /** A sign-in waiting for its code: Blink's cookies and CSRF token are tied to it, so the code must go to this one. */
  private pending: BlinkSignInSession | null = null;
  private refreshing: Promise<{ token: string; where: BlinkTier } | BlinkFailure> | null = null;
  private cameras: BlinkCamera[] = [];
  /** Where each listed clip video is on the account server, by clip id. */
  private mediaPaths = new Map<string, string>();
  private readonly now: () => number;
  private readonly pause: (ms: number) => Promise<void>;

  constructor(private readonly deps: BlinkServiceDeps) {
    this.now = deps.now ?? Date.now;
    this.pause = deps.pause ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
  }

  signInForm = (): SignInForm => ({
    title: 'Sign in to Blink',
    intro:
      'Your Blink email and password. If Blink sends a code (a text, an email or your authenticator app), type it on the next step. Turn any VPN off for the first sign-in: Blink refuses many VPN addresses. Edith uses what you type once and does not keep it; only the sign-in Blink gives back is saved in the iPhone Keychain. Blink has no official way for apps like Edith, so this can stop working whenever Blink changes something. Disconnecting forgets the sign-in on this phone; Edith may stay in the Blink app’s list of devices until you remove it there.',
    fields: [
      { key: 'email', label: 'Blink email', keyboard: 'email' },
      { key: 'password', label: 'Blink password', secret: true },
    ],
  });

  async restore(): Promise<void> {
    try {
      const raw = await this.deps.secrets.get(SECRET);
      if (!raw) return;
      const p = JSON.parse(raw) as Partial<Saved>;
      if (typeof p.refreshToken !== 'string' || typeof p.hardwareId !== 'string' || typeof p.tier !== 'string' || typeof p.accountId !== 'string') throw new Error('shape');
      this.saved = { refreshToken: p.refreshToken, hardwareId: p.hardwareId, tier: p.tier, accountId: p.accountId };
      this.state.set({ kind: 'connected' });
    } catch {
      log.warn('Blink', "the saved sign-in couldn't be read");
      this.saved = null;
    }
  }

  // --- sign-in -----------------------------------------------------------------------------------------------------------------
  async signIn(values: Record<string, string>): Promise<SignInResult> {
    const email = (values.email ?? '').trim();
    const password = values.password ?? '';
    const code = (values.code ?? '').trim() || null;
    // A code goes to the sign-in that asked for it. Without one waiting (the app was closed) a new sign-in starts; Blink sends a new code, and the one typed is tried anyway.
    const waiting = code ? this.pending : null;
    if (!waiting && (!email || !password)) return { kind: 'failed', message: 'Type your Blink email and password.' };
    const before = this.state.get();
    this.state.set({ kind: 'connecting' });
    const back = () => this.state.set(this.saved ? (before.kind === 'connecting' ? { kind: 'connected' } : before) : { kind: 'signed_out' });
    let login;
    if (waiting) login = await this.deps.api.verify(waiting, code!);
    else {
      const hardwareId = this.saved?.hardwareId ?? this.deps.uuid().toUpperCase();
      const start = await this.deps.api.begin(hardwareId);
      if (start.kind === 'failed') {
        back();
        return { kind: 'failed', message: `${start.why}.` };
      }
      this.pending = start.session;
      const first = await this.deps.api.signIn(start.session, email, password);
      login = first.kind === 'needs_code' && code ? await this.deps.api.verify(start.session, code) : first;
    }
    switch (login.kind) {
      case 'ok':
        return this.finishSignIn(login.tokens, back);
      case 'needs_code':
        back();
        return { kind: 'needs_code', form: { title: 'Blink sent you a code', intro: 'Type the code Blink sent (a text, an email or your authenticator app).', fields: [{ key: 'code', label: 'Code from Blink', keyboard: 'number' }], submitLabel: 'Verify' } };
      case 'wrong_code':
        back();
        return { kind: 'failed', message: "That code didn't work. Check it, or wait for a new one, and try again." };
      case 'bad_login':
        this.pending = null;
        back();
        return { kind: 'failed', message: "Blink says the email or password isn't right." };
      case 'failed':
        this.pending = null;
        back();
        return { kind: 'failed', message: `${login.why}.` };
    }
  }

  private async finishSignIn(tokens: BlinkTokens, back: () => void): Promise<SignInResult> {
    const session = this.pending;
    const tier = await this.deps.api.tier(tokens.accessToken);
    if (tier.kind !== 'ok') {
      this.pending = null;
      back();
      return { kind: 'failed', message: tier.kind === 'failed' ? `Blink signed in but ${tier.why.charAt(0).toLowerCase()}${tier.why.slice(1)}.` : "Blink signed in but wouldn't say which account this is." };
    }
    const next: Saved = { refreshToken: tokens.refreshToken, hardwareId: session?.hardwareId ?? this.saved?.hardwareId ?? this.deps.uuid().toUpperCase(), tier: tier.value.tier, accountId: tier.value.accountId };
    // Save first, then use.
    await this.deps.secrets.set(SECRET, JSON.stringify(next));
    this.saved = next;
    this.access = this.accessFrom(tokens);
    this.pending = null;
    this.state.set({ kind: 'connected' });
    log.info('Blink', 'signed in');
    return { kind: 'ok' };
  }

  async disconnect(): Promise<void> {
    this.saved = null;
    this.access = null;
    this.pending = null;
    this.cameras = [];
    this.mediaPaths.clear();
    await this.deps.secrets.delete(SECRET);
    this.state.set({ kind: 'signed_out' });
  }

  // --- tokens ------------------------------------------------------------------------------------------------------------------
  private accessFrom(t: BlinkTokens) {
    return { token: t.accessToken, expiresAt: this.now() + Math.max(30, t.expiresInSeconds - 60) * 1000 };
  }

  /** An access token that works, refreshed when it is about to run out. Refreshes never overlap. `stale` just got a 401, so it isn't reused. */
  private accessToken(stale?: string): Promise<{ token: string; where: BlinkTier } | BlinkFailure> {
    const s = this.saved;
    if (!s) return Promise.resolve(new BlinkFailure("it isn't signed in (Settings → Home)"));
    const where = { tier: s.tier, accountId: s.accountId };
    const a = this.access;
    if (a && a.token !== stale && this.now() < a.expiresAt) return Promise.resolve({ token: a.token, where });
    if (this.refreshing) return this.refreshing;
    const run = (async () => {
      const r = await this.deps.api.refresh(s.refreshToken, s.hardwareId);
      if (r.kind === 'rejected') {
        if (this.saved === s) {
          this.access = null;
          this.state.set({ kind: 'needs_sign_in', reason: 'Blink no longer accepts the saved sign-in.' });
          log.warn('Blink', 'no longer accepts the saved sign-in');
        }
        return new BlinkFailure(NEEDS_SIGN_IN);
      }
      if (r.kind === 'failed') return new BlinkFailure(r.why);
      if (r.tokens.refreshToken !== s.refreshToken) {
        // Save first. A token that wasn't saved must never be the one in use.
        const next = { ...s, refreshToken: r.tokens.refreshToken };
        await this.deps.secrets.set(SECRET, JSON.stringify(next));
        if (this.saved === s) this.saved = next;
      }
      this.access = this.accessFrom(r.tokens);
      return { token: r.tokens.accessToken, where };
    })().finally(() => {
      this.refreshing = null;
    });
    this.refreshing = run;
    return run;
  }

  /** Runs `call` with a working access token. A 401 refreshes once and asks again; "busy" is asked again a few times, three seconds apart. */
  private async authed<T>(call: (token: string, where: BlinkTier) => Promise<BlinkReply<T>>): Promise<T> {
    let stale: string | undefined;
    let busy = 0;
    for (let attempt = 0; attempt < MAX_TRIES; attempt++) {
      const got = await this.accessToken(stale);
      if (got instanceof BlinkFailure) throw got;
      const r = await call(got.token, got.where);
      switch (r.kind) {
        case 'ok':
          if (this.state.get().kind !== 'connected') this.state.set({ kind: 'connected' });
          return r.value;
        case 'unauthorized':
          stale = got.token;
          break;
        case 'busy':
          if (++busy >= BUSY_TRIES) throw new BlinkFailure(`${r.why}. Try again in a minute`);
          await this.pause(BUSY_WAIT_MS);
          break;
        case 'failed':
          throw new BlinkFailure(r.why);
      }
    }
    throw new BlinkFailure("Blink didn't accept Edith's sign-in just now; try again");
  }

  // --- backend -----------------------------------------------------------------------------------------------------------------
  private async listCameras(): Promise<BlinkCamera[]> {
    const list = await this.authed((t, w) => this.deps.api.cameras(t, w));
    this.cameras = list;
    return list;
  }

  private async cameraFor(deviceId: string, fresh = false): Promise<BlinkCamera> {
    const raw = deviceId.startsWith(PREFIX) ? deviceId.slice(PREFIX.length) : deviceId;
    if (!fresh) {
      const known = this.cameras.find((c) => c.id === raw);
      if (known) return known;
    }
    const found = (await this.listCameras()).find((c) => c.id === raw);
    if (!found) throw new BlinkFailure('that camera is no longer on the account');
    return found;
  }

  private device(c: BlinkCamera, at: number): HomeDevice {
    return {
      id: `${PREFIX}${c.id}`,
      name: c.name,
      room: '',
      type: c.kind === 'doorbell' ? 'doorbell' : 'camera',
      capabilities: ['snapshot', 'events'],
      backend: this.id,
      online: c.online !== false,
      // No battery percentage: Blink says only "ok" or "low", and nothing is invented. No arming: nothing in Edith's model for it.
      state: {},
      stateAt: at,
    };
  }

  /** The media list from `sinceMs`, page by page while the pages are full of clips inside the period. */
  private async mediaSince(sinceMs: number): Promise<BlinkMedia[]> {
    const all: BlinkMedia[] = [];
    for (let page = 1; page <= MAX_MEDIA_PAGES; page++) {
      let got: BlinkMedia[];
      try {
        got = await this.authed((t, w) => this.deps.api.media(t, w, sinceMs, page));
      } catch (e) {
        if (all.length === 0) throw e;
        return all;
      }
      all.push(...got);
      for (const m of got) this.mediaPaths.set(m.id, m.mediaPath);
      if (got.length === 0 || got.some((m) => m.at <= sinceMs)) break;
    }
    return all;
  }

  backend(): HomeBackend {
    return {
      id: this.id,
      name: this.name,
      /** A token refresh and the list can both happen before the answer. */
      timeoutMs: 25_000,
      /** A battery camera takes several seconds to wake and take a picture (Jarvis waits 30 s). */
      snapshotTimeoutMs: 45_000,
      devices: async () => {
        const at = this.now();
        return (await this.listCameras()).map((c) => this.device(c, at));
      },
      state: async (id): Promise<DeviceState> => {
        await this.cameraFor(id, true);
        return {};
      },
      command: async (_id: string, _command: DeviceCommand) => {
        throw new Error("Blink cameras can't be controlled from Edith (pictures and motion only).");
      },
      snapshot: async (id, signal) => {
        const camera = await this.cameraFor(id);
        if (camera.online === false) throw new BlinkFailure(`${camera.name} is offline.`);
        const command = await this.authed((t, w) => this.deps.api.requestSnapshot(t, w, camera));
        if (command !== 'none') {
          // The camera wakes, takes the picture and uploads it; Blink says when that is done.
          const network = command.networkId ?? camera.networkId;
          for (let i = 0; i < COMMAND_POLLS; i++) {
            await this.pause(POLL_MS);
            if (signal?.aborted) throw new BlinkFailure('Stopped.');
            try {
              if (await this.authed((t, w) => this.deps.api.commandDone(t, w, network, command.id))) break;
            } catch (e) {
              if (this.state.get().kind === 'needs_sign_in') throw e;
            }
          }
        }
        // The new picture's address shows up in the list a moment after the command finishes.
        for (let i = 0; i < THUMBNAIL_POLLS; i++) {
          const now = await this.cameraFor(id, true);
          if (now.thumbnail && now.thumbnail !== camera.thumbnail) return this.picture(now);
          await this.pause(THUMBNAIL_WAIT_MS);
          if (signal?.aborted) throw new BlinkFailure('Stopped.');
        }
        throw new BlinkFailure(`${camera.name} didn't send a new picture in time. It may be offline or its battery low.`);
      },
      hasClips: true,
      clips: async (sinceMs, untilMs): Promise<ClipsPage> => {
        const media = await this.mediaSince(sinceMs);
        return { clips: media.filter((m) => m.at >= sinceMs && m.at <= untilMs).map((m) => ({ id: m.id, deviceId: `${PREFIX}${m.cameraId}`, deviceName: m.cameraName, backend: this.id, kind: m.kind, at: m.at })) };
      },
      /** The video's address on the account's server and the sign-in header the player must send with it: Blink's clips are not public. */
      clipSource: async (clipId): Promise<ClipSource> => {
        const path = this.mediaPaths.get(clipId);
        if (!path) throw new BlinkFailure("that clip isn't in the list any more; refresh Clips.");
        const got = await this.accessToken();
        if (got instanceof BlinkFailure) throw got;
        const uri = restOf(got.where.tier) + path;
        if (!uri.startsWith('https://')) throw new BlinkFailure("Blink gave an address Edith can't use.");
        return { uri, headers: { Authorization: `Bearer ${got.token}` } };
      },
      /** What the cameras recorded since `sinceMs`, newest first: Blink's recordings are its motion events. */
      events: async (deviceId, sinceMs): Promise<DeviceEvent[]> => {
        const raw = deviceId === null ? null : deviceId.slice(PREFIX.length);
        const media = await this.mediaSince(sinceMs);
        return media
          .filter((m) => m.at >= sinceMs && (raw === null || m.cameraId === raw))
          .map((m) => ({ id: m.id, deviceId: `${PREFIX}${m.cameraId}`, deviceName: m.cameraName, kind: m.kind, at: m.at }));
      },
    };
  }

  private async picture(camera: BlinkCamera): Promise<{ jpegBase64: string; takenAt: number }> {
    const path = thumbnailPath(this.saved?.accountId ?? '', camera);
    if (!path) throw new BlinkFailure(`${camera.name} has no picture yet.`);
    const bytes = await this.authed((t, w) => this.deps.api.picture(t, w, path));
    return { jpegBase64: bytesToBase64(bytes), takenAt: this.now() };
  }
}
