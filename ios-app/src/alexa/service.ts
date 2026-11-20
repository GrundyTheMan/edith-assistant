import { log } from '../core/log';
import type { SecretStore } from '../core/secrets';
import { Store } from '../core/store';
import type { DeviceCommand, DeviceState, HomeBackend, HomeDevice } from '../home/model';
import type { HomeService, PageSignIn, ServiceState, SignInForm, SignInResult } from '../home/service';
import { AlexaApi, type AlexaSession, type AlexaSignInStart } from './api';
import {
  DEFAULT_SITE,
  capabilitiesOf,
  controlParameters,
  controlProblem,
  modesOf,
  parseEndpoints,
  parseStates,
  readingOf,
  scaleOf,
  shouldAskState,
  siteOf,
  stateBatches,
  stateRefusal,
  stateRequests,
  typeOf,
  type AlexaDevice,
  type AlexaReading,
  type AmazonSite,
} from './parse';

const SECRET = 'home.alexa';
export const NEEDS_SIGN_IN = 'it needs signing in again: Settings → Home';
/** Devices per state request: of the sizes Jarvis tried on a 78-device home, 5 got the most answers. */
export const STATE_BATCH = 5;
/** How long a batch may take before the lookup goes on without it (Jarvis: 10 s cut off answers 20 s got). */
export const STATE_DEADLINE_MS = 20_000;
export const LIST_CACHE_MS = 5 * 60_000;
/** How long a command may take to be answered. */
export const CONTROL_DEADLINE_MS = 20_000;

interface Saved {
  refreshToken: string;
  site: string;
  deviceSerial: string;
  signedInAt: number;
}

type Outcome<T> = { ok: true; value: T } | { ok: false; why: string };
const fail = (why: string): { ok: false; why: string } => ({ ok: false, why });

export interface AlexaServiceDeps {
  secrets: SecretStore;
  api: AlexaApi;
  now?: () => number;
}

/**
 * Alexa devices (C30, C54, D10, D19): everything linked to the owner's Amazon account, through the unofficial connection the Alexa app uses.
 *
 * Signing in happens on Amazon's own page (`pageSignIn`, shown in a WebView): the password goes to Amazon, never through Edith's code. What Amazon
 * hands back is a refresh token, kept in the Keychain (`home.alexa`) with the Amazon site and nothing else; Alexa's cookies and csrf token are got from
 * it when needed and held only in memory. A refresh token Amazon no longer accepts turns the service to "Sign in again". `isHub`: a device a direct
 * service also reports is shown through that one (D19).
 */
export class AlexaService implements HomeService {
  readonly id = 'alexa';
  readonly name = 'Alexa';
  readonly state = new Store<ServiceState>({ kind: 'signed_out' });
  private saved: Saved | null = null;
  private session: AlexaSession | null = null;
  private sessionPromise: Promise<AlexaSession | null> | null = null;
  private pending: AlexaSignInStart | null = null;
  /** The device list (without states), kept briefly so reading one device's state doesn't list them all again. */
  private listed: { at: number; list: AlexaDevice[] } | null = null;
  /** The last reading each device gave, shown with its age when it doesn't answer (Jarvis's `lastKnown`). */
  private readonly lastKnown = new Map<string, { reading: AlexaReading; at: number }>();
  private lastFailure: string | null = null;
  private readonly now: () => number;

  constructor(private readonly deps: AlexaServiceDeps) {
    this.now = deps.now ?? Date.now;
  }

  signInForm = (): SignInForm => ({ title: 'Sign in to Alexa', intro: "Alexa signs in on Amazon's own page.", fields: [] });
  signIn = async (): Promise<SignInResult> => ({ kind: 'failed', message: "Alexa signs in on Amazon's own page." });

  readonly pageSignIn: PageSignIn = {
    startUrl: () => {
      const start = this.deps.api.start(DEFAULT_SITE);
      this.pending = start;
      return start.url;
    },
    isFinish: (url) => {
      const code = this.deps.api.authorizationCode(url);
      return code ? { code } : null;
    },
    complete: (code) => this.finishSignIn(code),
  };

  async restore(): Promise<void> {
    try {
      const raw = await this.deps.secrets.get(SECRET);
      if (!raw) return;
      this.saved = JSON.parse(raw) as Saved;
      this.state.set({ kind: 'connected' });
    } catch {
      this.saved = null; // a damaged value is the same as none
      log.warn('Alexa', "the saved sign-in couldn't be read");
    }
  }

  private async finishSignIn(code: string): Promise<SignInResult> {
    const start = this.pending;
    if (!start) return { kind: 'failed', message: 'Start the sign-in again: that one has expired.' };
    // Whatever Amazon answered, finishing ends in a message. Only the failure's kind is logged, never its text (it can hold cookies).
    let registered;
    try {
      registered = await this.deps.api.register(start, code, '');
    } catch {
      log.warn('Alexa', 'registration failed unexpectedly');
      return { kind: 'failed', message: 'Edith hit an unexpected problem finishing the sign-in. Try again.' };
    }
    if (registered.kind === 'ok') {
      const keep: Saved = { refreshToken: registered.value.refreshToken, site: start.site.site, deviceSerial: registered.value.deviceSerial, signedInAt: this.now() };
      await this.deps.secrets.set(SECRET, JSON.stringify(keep));
      this.saved = keep;
      this.session = null;
      this.sessionPromise = null;
      this.listed = null;
      this.lastKnown.clear();
      this.pending = null;
      this.state.set({ kind: 'connected' });
      log.info('Alexa', `signed in through ${start.site.site}`);
      return { kind: 'ok' };
    }
    if (registered.kind === 'rejected') return { kind: 'failed', message: "Amazon didn't accept the sign-in. Start again, and finish signing in to Amazon on its page." };
    return { kind: 'failed', message: `${registered.why}.` };
  }

  async disconnect(): Promise<void> {
    await this.deps.secrets.delete(SECRET);
    this.saved = null;
    this.session = null;
    this.sessionPromise = null;
    this.pending = null;
    this.listed = null;
    this.lastKnown.clear();
    this.state.set({ kind: 'signed_out' });
  }

  private needsSignIn() {
    if (this.state.get().kind === 'connected') log.warn('Alexa', 'Amazon no longer accepts the saved sign-in');
    this.state.set({ kind: 'needs_sign_in', reason: 'Amazon asks you to sign in again.' });
  }

  private site(): AmazonSite {
    return siteOf(this.saved?.site ?? '') ?? DEFAULT_SITE;
  }

  /** Alexa's cookies, got from the refresh token the first time and again when Amazon refuses them. One request at a time. */
  private sessionFor(site: AmazonSite, refused?: AlexaSession): Promise<AlexaSession | null> {
    const have = this.session;
    if (have && have !== refused) return Promise.resolve(have);
    if (this.sessionPromise) return this.sessionPromise;
    const saved = this.saved;
    if (!saved) return Promise.resolve(null);
    const p = (async () => {
      const r = await this.deps.api.session(site, saved.refreshToken);
      if (r.kind === 'ok') {
        this.session = r.value;
        return r.value;
      }
      if (r.kind === 'rejected') this.needsSignIn();
      else this.lastFailure = r.why;
      return null;
    })().finally(() => {
      this.sessionPromise = null;
    });
    this.sessionPromise = p;
    return p;
  }

  /** Runs `call` with working cookies; a refusal gets new cookies once and asks again. */
  private async authed<T>(call: (site: AmazonSite, s: AlexaSession) => Promise<{ kind: 'ok'; value: T } | { kind: 'rejected' } | { kind: 'failed'; why: string }>): Promise<Outcome<T>> {
    if (!this.saved) return fail(`it isn't signed in: Settings → Home`);
    const site = this.site();
    let stale: AlexaSession | undefined;
    for (let attempt = 0; attempt < 2; attempt++) {
      this.lastFailure = null;
      const have = await this.sessionFor(site, stale);
      if (!have) return fail(this.state.get().kind === 'needs_sign_in' ? NEEDS_SIGN_IN : (this.lastFailure ?? "Amazon can't be reached right now"));
      const r = await call(site, have);
      if (r.kind === 'ok') {
        if (this.state.get().kind !== 'connected') this.state.set({ kind: 'connected' });
        return { ok: true, value: r.value };
      }
      if (r.kind === 'failed') return fail(r.why);
      stale = have; // refused: get new cookies and ask once more
    }
    return fail("Amazon didn't accept Edith's session just now; try again");
  }

  // ---- Devices ----

  private async deviceList(fresh: boolean): Promise<Outcome<AlexaDevice[]>> {
    if (!fresh && this.listed && this.now() - this.listed.at < LIST_CACHE_MS) return { ok: true, value: this.listed.list };
    const r = await this.authed((site, s) => this.deps.api.endpoints(site, s));
    if (!r.ok) return r;
    const list = parseEndpoints(r.value);
    this.listed = { at: this.now(), list };
    return { ok: true, value: list };
  }

  /** The devices with their states now: batches of five by maker, all at once, each with its own deadline. */
  private async lookup(list: AlexaDevice[]): Promise<HomeDevice[]> {
    const now = this.now();
    const scale = scaleOf(this.site());
    const asked = list.filter(shouldAskState);
    const readings = new Map<string, AlexaReading>();
    const batches = stateBatches(asked, STATE_BATCH);
    let failed = 0;
    let problem: string | null = null;
    await Promise.all(
      batches.map(async (group) => {
        let timer: ReturnType<typeof setTimeout> | undefined;
        const answer = await Promise.race([
          this.authed((site, s) => this.deps.api.states(site, s, stateRequests(group), STATE_DEADLINE_MS / 1000)),
          new Promise<null>((resolve) => {
            timer = setTimeout(() => resolve(null), STATE_DEADLINE_MS);
          }),
        ]);
        if (timer) clearTimeout(timer);
        if (answer === null) {
          failed++;
          problem = `no answer within ${STATE_DEADLINE_MS / 1000} s`;
          for (const d of group) readings.set(d.entityId, { state: null, noAnswer: true });
        } else if (!answer.ok) {
          failed++;
          problem = answer.why;
          for (const d of group) readings.set(d.entityId, { state: null, noAnswer: true });
        } else {
          const refusal = stateRefusal(answer.value);
          if (refusal) {
            // Amazon refused the whole request: that is "no answer", so each device keeps its last reading (with its age).
            failed++;
            problem = refusal;
            for (const d of group) readings.set(d.entityId, { state: null, noAnswer: true });
          } else {
            const parsed = parseStates(answer.value, scale);
            for (const d of group) readings.set(d.entityId, readingOf(d, parsed));
          }
        }
      }),
    );
    if (batches.length > 0 && failed === batches.length) log.warn('Alexa', `gave no device states: ${problem}`);
    return list.map((d) => this.device(d, readings.get(d.entityId), now));
  }

  private device(d: AlexaDevice, reading: AlexaReading | undefined, now: number): HomeDevice {
    let shown = reading;
    let at: number | null = reading && !reading.noAnswer ? now : null;
    if (reading && !reading.noAnswer && (reading.state || reading.unreachable)) this.lastKnown.set(d.entityId, { reading, at: now });
    // No answer this time: the last reading, with when it was reported.
    if (reading?.noAnswer) {
      const last = this.lastKnown.get(d.entityId);
      if (last) {
        shown = last.reading;
        at = last.at;
      }
    }
    const capabilities = capabilitiesOf(d);
    const state: DeviceState = shown?.state ?? {};
    return {
      id: d.entityId,
      name: d.name,
      room: '',
      type: typeOf(d.type),
      capabilities,
      backend: this.id,
      online: shown?.unreachable !== true,
      state,
      stateAt: at,
      ...(capabilities.includes('thermostat') ? { unit: scaleOf(this.site()) === 'FAHRENHEIT' ? ('F' as const) : ('C' as const), modes: modesOf(d) } : {}),
    };
  }

  backend(): HomeBackend {
    return {
      id: this.id,
      name: this.name,
      isHub: true,
      /** Token exchange, the device list, then the state batches (each up to 20 s). */
      timeoutMs: 60_000,
      devices: async () => {
        const l = await this.deviceList(true);
        if (!l.ok) throw new Error(l.why);
        return this.lookup(l.value);
      },
      state: async (id) => {
        const l = await this.deviceList(false);
        if (!l.ok) throw new Error(l.why);
        const d = l.value.find((x) => x.entityId === id);
        if (!d) throw new Error('that device is no longer on the Alexa account');
        const h = (await this.lookup([d]))[0];
        if (h.state && Object.keys(h.state).length > 0) return h.state;
        throw new Error(h.online ? 'it reported no state' : "it isn't responding");
      },
      command: (id, command) => this.command(id, command),
    };
  }

  /** A command, checked against Amazon's answer: Amazon answers 200 to a refusal, so only its answer says whether the device did it. */
  private async command(id: string, command: DeviceCommand): Promise<void> {
    const l = await this.deviceList(false);
    if (!l.ok) throw new Error(l.why);
    const d = l.value.find((x) => x.entityId === id);
    if (!d) throw new Error('that device is no longer on the Alexa account');
    const parameters = controlParameters(command, d, scaleOf(this.site()));
    if (!parameters) throw new Error(`${d.name} can't do that through Alexa`);
    const r = await this.authed((site, s) => this.deps.api.control(site, s, d.entityId, parameters, CONTROL_DEADLINE_MS / 1000));
    if (!r.ok) throw new Error(r.why);
    const problem = controlProblem(r.value);
    if (problem) throw new Error(`${d.name}: ${problem}`);
    log.info('Alexa', `command sent (${d.type})`);
  }
}
