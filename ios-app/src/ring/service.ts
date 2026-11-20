import { bytesToBase64 } from '../core/bytes';
import { log } from '../core/log';
import type { SecretStore } from '../core/secrets';
import { Store } from '../core/store';
import type { ClipSource, ClipsPage, DeviceCommand, DeviceEvent, DeviceState, HomeBackend, HomeDevice } from '../home/model';
import type { HomeService, ServiceState, SignInForm, SignInResult } from '../home/service';
import { RingApi, type RingReply, type RingTokens } from './api';
import type { RingCamera, RingClipRow } from './parse';

const SECRET = 'home.ring';
const NEEDS_SIGN_IN = 'Ring asks you to sign in again (Settings → Home).';
const PREFIX = 'ring:';

interface Saved {
  refreshToken: string;
  hardwareId: string;
}

export interface RingServiceDeps {
  secrets: SecretStore;
  api: RingApi;
  now?: () => number;
  uuid: () => string;
}

/** Thrown by `authed` with words for the owner; the home screen shows them as the service's problem. */
class RingFailure extends Error {}

/**
 * Ring doorbells and cameras on iOS (C51, ported from C27): the owner's Ring account, signed in with an email, a password and a
 * 2FA code that are typed once, used once and never kept. Only Ring's refresh token and this phone's hardware id are saved in the
 * Keychain (`home.ring`).
 *
 * **The refresh token rotates**: every refresh returns a new one and the old one stops working soon after. So each new one is saved
 * *before* the access token it came with is used, and refreshes never overlap (one in-flight promise; several tool calls at once
 * would otherwise spend the same old token and lose the sign-in). A token Ring no longer accepts turns the service to
 * `needs_sign_in`; Ring being unreachable never signs anyone out.
 */
export class RingService implements HomeService {
  readonly id = 'ring';
  readonly name = 'Ring';
  readonly state = new Store<ServiceState>({ kind: 'signed_out' });
  private saved: Saved | null = null;
  private access: { token: string; expiresAt: number } | null = null;
  private sessionReady = false;
  /** The phone id of a sign-in still waiting for its 2FA code: Ring ties the code to it, so each try sends the same one. */
  private pendingHardwareId: string | null = null;
  private refreshing: Promise<{ token: string; hardwareId: string } | RingFailure> | null = null;
  private cameras: RingCamera[] = [];
  /** The addresses the clip search listed clips with, by clip id: a last resort when Ring gives no fresh one. */
  private listedUrls = new Map<string, string>();
  private readonly now: () => number;

  constructor(private readonly deps: RingServiceDeps) {
    this.now = deps.now ?? Date.now;
  }

  signInForm = (): SignInForm => ({
    title: 'Sign in to Ring',
    intro:
      'Your Ring email and password. Ring then sends a code by text or email: type it on the next step. Edith uses what you type once and does not keep it; only the sign-in Ring gives back is saved in the iPhone Keychain. Ring has no official way for apps like Edith, so this can stop working if Ring changes. Disconnecting forgets the sign-in on this phone; Edith may stay listed under Control Center in the Ring app until you remove it there.',
    fields: [
      { key: 'email', label: 'Ring email', keyboard: 'email' },
      { key: 'password', label: 'Ring password', secret: true },
    ],
  });

  async restore(): Promise<void> {
    try {
      const raw = await this.deps.secrets.get(SECRET);
      if (!raw) return;
      const parsed = JSON.parse(raw) as Partial<Saved>;
      if (typeof parsed.refreshToken !== 'string' || typeof parsed.hardwareId !== 'string') throw new Error('shape');
      this.saved = { refreshToken: parsed.refreshToken, hardwareId: parsed.hardwareId };
      this.state.set({ kind: 'connected' });
    } catch {
      log.warn('Ring', "the saved sign-in couldn't be read");
      this.saved = null;
    }
  }

  // --- sign-in -----------------------------------------------------------------------------------------------------------------
  /** `code` arrives from the second step: the first answers (email, password) are kept in memory only until it works. */
  private pendingLogin: { email: string; password: string } | null = null;

  async signIn(values: Record<string, string>): Promise<SignInResult> {
    const code = (values.code ?? '').trim() || null;
    const email = (values.email ?? this.pendingLogin?.email ?? '').trim();
    const password = values.password ?? this.pendingLogin?.password ?? '';
    if (!email || !password) return { kind: 'failed', message: 'Type your Ring email and password.' };
    // The same hardware id on every request of a sign-in (and on a later one), so Ring sees one phone, not a new one each time.
    const hardwareId = this.saved?.hardwareId ?? this.pendingHardwareId ?? (this.pendingHardwareId = this.deps.uuid());
    const before = this.state.get();
    this.state.set({ kind: 'connecting' });
    const back = () => this.state.set(this.saved ? (before.kind === 'connecting' ? { kind: 'connected' } : before) : { kind: 'signed_out' });
    const result = await this.deps.api.signIn(email, password, code, hardwareId);
    switch (result.kind) {
      case 'ok': {
        const token = result.tokens.refreshToken;
        if (!token) {
          back();
          return { kind: 'failed', message: 'Ring signed in but gave no sign-in token to keep.' };
        }
        // Save first, then use.
        await this.deps.secrets.set(SECRET, JSON.stringify({ refreshToken: token, hardwareId } satisfies Saved));
        this.saved = { refreshToken: token, hardwareId };
        this.pendingHardwareId = null;
        this.pendingLogin = null;
        this.access = this.accessFrom(result.tokens);
        this.sessionReady = false;
        this.state.set({ kind: 'connected' });
        log.info('Ring', 'signed in');
        return { kind: 'ok' };
      }
      case 'needs_code':
        this.pendingLogin = { email, password };
        back();
        return {
          kind: 'needs_code',
          form: { title: 'Ring sent you a code', intro: `${result.prompt} Type it below.`, fields: [{ key: 'code', label: 'Code from Ring', keyboard: 'number', hint: 'Six digits' }], submitLabel: 'Verify' },
        };
      case 'wrong_code':
        // Keeps the answers in memory: the next try sends the same ones with the new code.
        this.pendingLogin = { email, password };
        back();
        return { kind: 'failed', message: "That code didn't work. Check it, or wait for a new one, and try again." };
      case 'bad_login':
        this.pendingLogin = null;
        back();
        return { kind: 'failed', message: "Ring says the email or password isn't right." };
      case 'too_many_codes':
        this.pendingLogin = null;
        back();
        return { kind: 'failed', message: "You've asked Ring for too many codes. Ring allows 10 in 10 minutes: wait a bit and try again." };
      case 'failed':
        back();
        return { kind: 'failed', message: `${result.why}.` };
    }
  }

  async disconnect(): Promise<void> {
    this.saved = null;
    this.access = null;
    this.sessionReady = false;
    this.cameras = [];
    this.listedUrls.clear();
    this.pendingLogin = null;
    this.pendingHardwareId = null;
    await this.deps.secrets.delete(SECRET);
    this.state.set({ kind: 'signed_out' });
  }

  // --- tokens ------------------------------------------------------------------------------------------------------------------
  private accessFrom(t: RingTokens) {
    return { token: t.accessToken, expiresAt: this.now() + Math.max(30, t.expiresInSeconds - 60) * 1000 };
  }

  /**
   * An access token that works. A refresh spends the saved refresh token and gets a new one: the new one is saved before the access
   * token is handed out. Refreshes never overlap: callers wait for the one in flight. `stale` is a token that just got a 401.
   */
  private accessToken(stale?: string): Promise<{ token: string; hardwareId: string } | RingFailure> {
    const s = this.saved;
    if (!s) return Promise.resolve(new RingFailure("it isn't signed in (Settings → Home)"));
    const a = this.access;
    if (a && a.token !== stale && this.now() < a.expiresAt) return Promise.resolve({ token: a.token, hardwareId: s.hardwareId });
    if (this.refreshing) return this.refreshing;
    const run = (async () => {
      const r = await this.deps.api.refresh(s.refreshToken, s.hardwareId);
      if (r.kind === 'rejected') {
        // Only if this sign-in is still the one in use (the owner may have signed in again meanwhile).
        if (this.saved === s) {
          this.access = null;
          this.state.set({ kind: 'needs_sign_in', reason: 'Ring no longer accepts the saved sign-in.' });
          log.warn('Ring', 'no longer accepts the saved sign-in');
        }
        return new RingFailure(NEEDS_SIGN_IN);
      }
      if (r.kind === 'failed') return new RingFailure(r.why);
      const next = r.tokens.refreshToken;
      if (next && next !== s.refreshToken) {
        // Save first. A token that wasn't saved must never be the one in use.
        await this.deps.secrets.set(SECRET, JSON.stringify({ refreshToken: next, hardwareId: s.hardwareId } satisfies Saved));
        if (this.saved === s) this.saved = { refreshToken: next, hardwareId: s.hardwareId };
      }
      this.access = this.accessFrom(r.tokens);
      return { token: r.tokens.accessToken, hardwareId: s.hardwareId };
    })().finally(() => {
      this.refreshing = null;
    });
    this.refreshing = run;
    return run;
  }

  /** Runs `call` with a working access token and this phone's session. A 401 refreshes once and asks again; a forgotten session is started again once. */
  private async authed<T>(call: (token: string, hardwareId: string) => Promise<RingReply<T>>): Promise<T> {
    let stale: string | undefined;
    for (let attempt = 0; attempt < 3; attempt++) {
      const got = await this.accessToken(stale);
      if (got instanceof RingFailure) throw got;
      const { token, hardwareId } = got;
      if (!this.sessionReady) {
        const s = await this.deps.api.startSession(token, hardwareId);
        if (s.kind === 'ok') this.sessionReady = true;
        else if (s.kind === 'unauthorized') {
          stale = token;
          continue;
        } else if (s.kind === 'failed') throw new RingFailure(s.why);
      }
      const r = await call(token, hardwareId);
      if (r.kind === 'ok') {
        if (this.state.get().kind !== 'connected') this.state.set({ kind: 'connected' });
        return r.value;
      }
      if (r.kind === 'unauthorized') stale = token;
      else if (r.kind === 'no_session') this.sessionReady = false;
      else throw new RingFailure(r.why);
    }
    throw new RingFailure("Ring didn't accept Edith's sign-in just now; try again");
  }

  // --- backend -----------------------------------------------------------------------------------------------------------------
  private async listCameras(): Promise<RingCamera[]> {
    const list = await this.authed((t, h) => this.deps.api.cameras(t, h));
    this.cameras = list;
    return list;
  }

  private async cameraFor(deviceId: string): Promise<RingCamera> {
    const raw = deviceId.startsWith(PREFIX) ? deviceId.slice(PREFIX.length) : deviceId;
    const known = this.cameras.find((c) => c.id === raw);
    if (known) return known;
    const found = (await this.listCameras()).find((c) => c.id === raw);
    if (!found) throw new RingFailure('that camera is no longer on the account');
    return found;
  }

  /**
   * The clips of every camera between the two times. Without a Ring Protect plan Ring's search still lists the events but with `had_subscription` false:
   * that is "no plan" (not an error, nothing listed). A camera Ring fails on is skipped; only if every camera fails is it an error.
   */
  private async clips(sinceMs: number, untilMs: number): Promise<ClipsPage> {
    const cameras = await this.listCameras();
    const rows: RingClipRow[] = [];
    let firstFailure: Error | null = null;
    let answered = 0;
    for (const c of cameras) {
      try {
        rows.push(...(await this.authed((t, h) => this.deps.api.clips(t, h, c, sinceMs, untilMs))));
        answered++;
      } catch (e) {
        firstFailure ??= e instanceof Error ? e : new Error('Ring did not answer');
        if (this.state.get().kind === 'needs_sign_in') break;
      }
    }
    if (answered === 0 && firstFailure) throw firstFailure;
    for (const r of rows) if (r.url) this.listedUrls.set(r.id, r.url);
    const noPlan = rows.length > 0 && rows.every((r) => !r.hadPlan);
    const clips = noPlan ? [] : rows.filter((r) => r.hadPlan).map((r) => ({ id: r.id, deviceId: `${PREFIX}${r.cameraId}`, deviceName: r.cameraName, backend: this.id, kind: r.kind, at: r.at, ...(r.seconds === null ? {} : { durationSec: r.seconds }) }));
    return { clips: clips.sort((a, b) => b.at - a.at), noPlan };
  }

  /**
   * A fresh address for a clip, asked for at play time (Ring's are signed and expire): `share/play`, then `recording` (Ring answers 404 to the one it doesn't have;
   * the owner's account answered 404 to `recording` on 2026-11-14), then the address the search listed it with. Only https addresses are played.
   */
  private async clipSource(clipId: string): Promise<ClipSource> {
    let failure: Error | null = null;
    for (const transcoded of [false, true]) {
      try {
        return { uri: await this.authed((t, h) => this.deps.api.clipUrl(t, h, clipId, transcoded)) };
      } catch (e) {
        if (this.state.get().kind === 'needs_sign_in') throw e;
        failure ??= e instanceof Error ? e : new Error('Ring did not answer');
      }
    }
    const listed = this.listedUrls.get(clipId);
    if (listed && listed.startsWith('https://')) return { uri: listed };
    throw failure ?? new RingFailure('Ring has no address for that clip');
  }

  private device(c: RingCamera, at: number): HomeDevice {
    return {
      id: `${PREFIX}${c.id}`,
      name: c.name,
      room: '',
      type: c.doorbell ? 'doorbell' : 'camera',
      capabilities: ['snapshot', 'events'],
      backend: this.id,
      online: !c.offline,
      state: c.battery === null ? {} : { battery: c.battery },
      stateAt: at,
    };
  }

  backend(): HomeBackend {
    return {
      id: this.id,
      name: this.name,
      /** A token refresh, a session and the list can all happen before the answer. */
      timeoutMs: 20_000,
      /** A sleeping battery camera has to wake for a new picture (Jarvis waits 30 s too). */
      snapshotTimeoutMs: 35_000,
      devices: async () => {
        const at = this.now();
        return (await this.listCameras()).map((c) => this.device(c, at)).sort((a, b) => a.name.localeCompare(b.name));
      },
      state: async (id): Promise<DeviceState> => {
        const c = (await this.listCameras()).find((x) => `${PREFIX}${x.id}` === id);
        if (!c) throw new RingFailure('that camera is no longer on the account');
        return c.battery === null ? {} : { battery: c.battery };
      },
      command: async (_id: string, _command: DeviceCommand) => {
        throw new Error("Ring cameras and doorbells can't be controlled from Edith (pictures and events only).");
      },
      snapshot: async (id, signal) => {
        const camera = await this.cameraFor(id);
        if (camera.offline) throw new RingFailure(`${camera.name} is offline.`);
        if (!camera.motionDetection) throw new RingFailure(`Motion detection is off for ${camera.name}, which stops Ring taking pictures from it.`);
        const bytes = await this.authed((t, h) => this.deps.api.snapshot(t, h, camera.id, this.now() - 1000, signal));
        return { jpegBase64: bytesToBase64(bytes), takenAt: this.now() };
      },
      hasClips: true,
      clips: (sinceMs, untilMs) => this.clips(sinceMs, untilMs),
      clipSource: (clipId) => this.clipSource(clipId),
      events: async (deviceId, sinceMs): Promise<DeviceEvent[]> => {
        const wanted = deviceId === null ? await this.listCameras() : [await this.cameraFor(deviceId)];
        const all: DeviceEvent[] = [];
        let firstFailure: Error | null = null;
        let answered = 0;
        for (const c of wanted) {
          try {
            all.push(...(await this.authed((t, h) => this.deps.api.events(t, h, c, sinceMs))));
            answered++;
          } catch (e) {
            firstFailure ??= e instanceof Error ? e : new Error('Ring did not answer');
            if (this.state.get().kind === 'needs_sign_in') break;
          }
        }
        if (answered === 0 && firstFailure) throw firstFailure;
        return all.sort((a, b) => b.at - a.at);
      },
    };
  }
}
