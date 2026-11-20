import { log } from '../core/log';
import type { SecretStore } from '../core/secrets';
import { Store } from '../core/store';
import type { DeviceCommand, DeviceState, HomeBackend, HomeDevice } from '../home/model';
import type { HomeService, ServiceAction, ServiceState, SignInForm, SignInResult } from '../home/service';
import type { HttpFetch } from '../ollama/client';
import type { KeyValueStore } from '../settings/settings';
import { MerossApi, type MerossCreds } from './api';
import { LanError, MerossLan, scanForDevices, type LanDoor } from './lan';
import { isGarageOpener } from './protocol';

const SECRET = 'home.meross';
const IPS_KEY = 'meross_lan_ips';
/** Looking for the doors on the Wi-Fi is some 250 requests: at most this often on its own. */
const RESCAN_MS = 5 * 60_000;
const AWAY = 'Not reachable. It works on your home Wi-Fi.';

export interface MerossServiceDeps {
  secrets: SecretStore;
  /** The key-value store: the doors' addresses on the Wi-Fi are kept here (not secret). */
  store: KeyValueStore;
  /** HTTPS to Meross only: `secureFetch(httpFetch, { allowHosts: ['meross.com'] })`. */
  cloudFetch: HttpFetch;
  /** Plain HTTP to the doors on the LAN (Meross devices have no TLS): `httpFetch`, deliberately (AGENTS.md). */
  lanFetch: HttpFetch;
  now?: () => number;
  /** The phone's Wi-Fi address (`expo-network`), or null. */
  phoneIp: () => Promise<string | null>;
  uuid: () => string;
  random?: () => number;
  baseUrl?: string;
  trusted?: (domain: string) => boolean;
}

/**
 * Meross garage door openers (C50, ported from C26): sign in to Meross's cloud for the list and the key, then **read and move the doors over the home
 * Wi-Fi**. Cloud control away from home needs raw sockets and comes with C70, so away from home a door says "works on your home Wi-Fi" and keeps its last
 * report (no error banner).
 */
export class MerossService implements HomeService {
  readonly id = 'meross';
  readonly name = 'Meross';
  readonly state = new Store<ServiceState>({ kind: 'signed_out' });
  private creds: MerossCreds | null = null;
  private ips: Record<string, string> = {};
  private lastDoors = new Map<string, { doors: LanDoor[]; at: number }>();
  private lastScan = 0;
  private readonly api: MerossApi;
  private readonly lan: MerossLan;
  private readonly now: () => number;

  constructor(private readonly deps: MerossServiceDeps) {
    this.now = deps.now ?? Date.now;
    this.api = new MerossApi({ fetch: deps.cloudFetch, now: this.now, random: deps.random, baseUrl: deps.baseUrl, trusted: deps.trusted });
    this.lan = new MerossLan(deps.lanFetch, this.now, deps.random);
  }

  signInForm = (): SignInForm => ({
    title: 'Sign in to Meross',
    intro: 'Your Meross account. Edith keeps only the sign-in Meross gives back, never your password. Doors are controlled over your home Wi-Fi; iPhone will ask to find devices on your network.',
    fields: [
      { key: 'email', label: 'Email', keyboard: 'email' },
      { key: 'password', label: 'Password', secret: true },
    ],
  });

  actions = (): ServiceAction[] =>
    this.creds
      ? [
          {
            id: 'find',
            label: 'Find on my Wi-Fi',
            run: async () => {
              const wanted = await this.openerIds();
              if (wanted.kind !== 'ok') return wanted.why;
              const found = await this.findOnWifi(wanted.uuids, true);
              return found === wanted.uuids.length
                ? `Found ${found === 1 ? 'the door' : `all ${found} doors`} on your Wi-Fi.`
                : `Found ${found} of ${wanted.uuids.length} on your Wi-Fi. Are you on your home Wi-Fi?`;
            },
          },
        ]
      : [];

  // --- sign-in -----------------------------------------------------------------------------------------------------------------
  async restore(): Promise<void> {
    try {
      const raw = await this.deps.secrets.get(SECRET);
      if (!raw) return;
      this.creds = JSON.parse(raw) as MerossCreds;
      this.ips = JSON.parse((await this.deps.store.getItem(IPS_KEY)) ?? '{}') as Record<string, string>;
      this.state.set({ kind: 'connected', detail: this.creds.email });
    } catch {
      this.creds = null; // a damaged value is the same as none
    }
  }

  async signIn(values: Record<string, string>): Promise<SignInResult> {
    const email = (values.email ?? '').trim();
    const password = values.password ?? '';
    if (!email || !password) return { kind: 'failed', message: 'Type your Meross email and password.' };
    this.state.set({ kind: 'connecting' });
    const result = await this.api.login(email, password, this.deps.uuid(), (values.code ?? '').trim() || undefined);
    const restoreState = () => this.state.set(this.creds ? { kind: 'connected', detail: this.creds.email } : { kind: 'signed_out' });
    switch (result.kind) {
      case 'needs_code':
        restoreState();
        return { kind: 'needs_code', form: { title: 'Meross sent you a code', intro: 'Type the code from your authenticator or email.', fields: [{ key: 'code', label: 'Code', keyboard: 'number' }], submitLabel: 'Verify' } };
      case 'wrong_code':
        restoreState();
        return { kind: 'failed', message: "That code didn't work. Try again." };
      case 'bad_login':
        restoreState();
        return { kind: 'failed', message: "Meross didn't accept that email and password." };
      case 'too_many_tokens':
        restoreState();
        return { kind: 'failed', message: 'This Meross account has too many sign-ins. Sign out of another device in the Meross app and try again.' };
      case 'failed':
        restoreState();
        return { kind: 'failed', message: result.why };
      case 'ok': {
        // Signing in again returns the token it replaces (an account that holds too many can be disabled).
        const old = this.creds;
        this.creds = result.creds;
        await this.deps.secrets.set(SECRET, JSON.stringify(result.creds));
        if (old) await this.api.logout(old);
        this.state.set({ kind: 'connected', detail: result.creds.email });
        return { kind: 'ok' };
      }
    }
  }

  async disconnect(): Promise<void> {
    const creds = this.creds;
    this.creds = null;
    if (creds) await this.api.logout(creds);
    await this.deps.secrets.delete(SECRET);
    await this.deps.store.removeItem(IPS_KEY);
    this.ips = {};
    this.lastDoors.clear();
    this.state.set({ kind: 'signed_out' });
  }

  backend(): HomeBackend {
    return {
      id: this.id,
      name: this.name,
      timeoutMs: 20_000,
      devices: () => this.devices(),
      state: async (id) => (await this.readDevice(id)).state,
      command: (id, command) => this.command(id, command),
    };
  }

  // --- devices -----------------------------------------------------------------------------------------------------------------
  private async openerIds(): Promise<{ kind: 'ok'; uuids: string[]; names: Map<string, string> } | { kind: 'failed'; why: string }> {
    if (!this.creds) return { kind: 'failed', why: 'Meross is not signed in.' };
    const reply = await this.api.devices(this.creds);
    if (reply.kind === 'rejected') {
      this.state.set({ kind: 'needs_sign_in', reason: 'Meross asks you to sign in again.' });
      return { kind: 'failed', why: 'Meross asks you to sign in again.' };
    }
    if (reply.kind === 'failed') return { kind: 'failed', why: reply.why };
    const openers = reply.value.filter((d) => isGarageOpener(d.type));
    return { kind: 'ok', uuids: openers.map((d) => d.uuid), names: new Map(openers.map((d) => [d.uuid, d.name])) };
  }

  private async saveIps() {
    await this.deps.store.setItem(IPS_KEY, JSON.stringify(this.ips)).catch(() => {});
  }

  /** Looks for the doors on the phone's Wi-Fi. `force` ignores the pause between scans (the owner pressed the button). Returns how many it now knows. */
  private async findOnWifi(wanted: string[], force = false): Promise<number> {
    if (!this.creds) return 0;
    if (!force && this.now() - this.lastScan < RESCAN_MS) return wanted.filter((u) => this.ips[u]).length;
    this.lastScan = this.now();
    const key = this.creds.key;
    const found = await scanForDevices(await this.deps.phoneIp(), wanted, async (ip) => (await this.lan.read(ip, key, 1_500)).uuid);
    for (const [uuid, ip] of found) this.ips[uuid] = ip;
    if (found.size > 0) await this.saveIps();
    return wanted.filter((u) => this.ips[u]).length;
  }

  /** A door's reading over the LAN: asked twice before it is "not responding" (a cold connection can fail once, C26). */
  private async readDoors(uuid: string): Promise<LanDoor[] | null> {
    const ip = this.ips[uuid];
    if (!ip || !this.creds) return null;
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const r = await this.lan.read(ip, this.creds.key);
        if (r.uuid !== uuid) break; // another device has this address now
        this.lastDoors.set(uuid, { doors: r.doors, at: this.now() });
        return r.doors;
      } catch (e) {
        if (e instanceof LanError && e.kind === 'untrusted') break; // never retried
      }
    }
    delete this.ips[uuid]; // look for it again
    return null;
  }

  private doorState(door: LanDoor): DeviceState {
    return { door: door.open ? 'open' : 'closed', changedAt: door.lastMovedSec ? door.lastMovedSec * 1000 : undefined };
  }

  private async devices(): Promise<HomeDevice[]> {
    const list = await this.openerIds();
    if (list.kind !== 'ok') throw new Error(list.why);
    if (list.uuids.some((u) => !this.ips[u])) await this.findOnWifi(list.uuids);
    const out: HomeDevice[] = [];
    for (const uuid of list.uuids) {
      const name = list.names.get(uuid) ?? 'Garage door';
      const doors = await this.readDoors(uuid);
      const last = this.lastDoors.get(uuid);
      const shown = doors ?? last?.doors ?? [{ channel: 0, open: false }];
      for (const door of shown) {
        const state: DeviceState = doors || last ? this.doorState(door) : {};
        out.push({
          id: `${uuid}#${door.channel}`,
          name: shown.length > 1 ? `${name} ${door.channel + 1}` : name,
          room: 'Garage',
          type: 'garage_door',
          capabilities: ['open_close'],
          backend: this.id,
          online: doors !== null,
          state: doors !== null ? state : { ...state, homeOnly: true },
          stateAt: doors !== null ? this.now() : (last?.at ?? null),
        });
      }
    }
    return out;
  }

  private async readDevice(id: string): Promise<HomeDevice> {
    const uuid = id.split('#')[0];
    const doors = await this.readDoors(uuid);
    const channel = Number(id.split('#')[1] ?? 0);
    const door = doors?.find((d) => d.channel === channel);
    if (!door) throw new Error(AWAY);
    return { id, name: 'Garage door', room: 'Garage', type: 'garage_door', capabilities: ['open_close'], backend: this.id, online: true, state: this.doorState(door), stateAt: this.now() };
  }

  // --- commands ----------------------------------------------------------------------------------------------------------------
  private async command(id: string, command: DeviceCommand): Promise<void> {
    if (command.type !== 'open' && command.type !== 'close') throw new Error("A garage door can only be opened or closed.");
    if (!this.creds) throw new Error('Meross is not signed in.');
    const [uuid, ch] = id.split('#');
    const channel = Number(ch ?? 0);
    if (!this.ips[uuid]) await this.findOnWifi([uuid], true);
    const doors = await this.readDoors(uuid);
    const door = doors?.find((d) => d.channel === channel);
    if (!doors || !door) throw new Error(AWAY);
    const wantOpen = command.type === 'open';
    if (door.open === wantOpen) return; // already so: nothing to send
    try {
      await this.lan.setDoor(this.ips[uuid], this.creds.key, uuid, channel, wantOpen);
    } catch (e) {
      log.warn('Meross', `door command failed: ${e instanceof LanError ? e.kind : 'error'}`);
      throw new Error(e instanceof LanError && e.kind === 'unreachable' ? AWAY : e instanceof Error ? e.message : 'The door did not answer.');
    }
  }
}
