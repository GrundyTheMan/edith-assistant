import { toAppError } from '../core/errors';
import { Store } from '../core/store';
import type { KeyValueStore } from '../settings/settings';
import { stateWords } from './actions';
import type { ClipSource, DeviceClip, DeviceCommand, DeviceEvent, HomeBackend, HomeDevice } from './model';
import { isConnected, type HomeService } from './service';

export const READ_TIMEOUT_MS = 10_000;
/** The device is asked again after a command; clouds lag, so thermostats and locks are asked again later too (C25 §6). */
export const RECHECK_MS = [3_000];
export const RECHECK_SLOW_MS = [3_000, 10_000, 30_000];
export const LOG_LIMIT = 500;

export interface LogEntry {
  at: number;
  device: string;
  action: string;
  ok: boolean;
  result: string;
  source: 'chat' | 'home';
  /** The service (backend id) the command went to. */
  service?: string;
}

export interface HomeSnapshot {
  devices: HomeDevice[];
  /** One line per backend that didn't answer. */
  problems: string[];
}

const KEYS = { favourites: 'home_favourites', hidden: 'home_hidden', chip: 'home_chip', demo: 'home_demo', log: 'home_command_log' };

const sameDevice = (a: HomeDevice, b: HomeDevice) => a.name.trim().toLowerCase() === b.name.trim().toLowerCase() && a.type === b.type;

interface Deps {
  store: KeyValueStore;
  now?: () => number;
  setTimer?: (fn: () => void, ms: number) => unknown;
  readTimeoutMs?: number;
}

/**
 * Every connected service in one place (C25 §2): reads them in parallel (one slow or broken service is a problem line,
 * never an empty home), merges a hub's copy of a device away when a direct service answers, keeps favourites and hidden
 * devices, runs commands, and logs them.
 */
export class Home {
  /** The backends in use: `setBackends` ones (the Demo home, tests) plus every connected service's. */
  private backends: HomeBackend[] = [];
  private fixedBackends: HomeBackend[] = [];
  private serviceList: HomeService[] = [];
  /** The registered services (C48), for Settings → Home. */
  readonly services = new Store<HomeService[]>([]);
  private last = new Map<string, HomeDevice>();
  private lastProblems: string[] = [];
  /** Bumps whenever devices, preferences or the log change, so screens redraw. */
  readonly version = new Store<number>(0);
  private favourites = new Set<string>();
  private hidden = new Set<string>();
  private chip = 'favorites';
  private demo = false;
  private log: LogEntry[] = [];
  private loaded = false;
  private readonly now: () => number;
  private readonly setTimer: (fn: () => void, ms: number) => unknown;

  constructor(private readonly deps: Deps) {
    this.now = deps.now ?? Date.now;
    this.setTimer = deps.setTimer ?? ((fn, ms) => setTimeout(fn, ms));
  }

  private bump() {
    this.version.set(this.version.get() + 1);
  }

  async load(): Promise<void> {
    if (this.loaded) return;
    const read = async (k: string) => {
      try {
        return JSON.parse((await this.deps.store.getItem(k)) ?? 'null') as unknown;
      } catch {
        return null;
      }
    };
    this.favourites = new Set(((await read(KEYS.favourites)) as string[] | null) ?? []);
    this.hidden = new Set(((await read(KEYS.hidden)) as string[] | null) ?? []);
    this.chip = ((await read(KEYS.chip)) as string | null) ?? 'favorites';
    this.demo = ((await read(KEYS.demo)) as boolean | null) ?? false;
    this.log = ((await read(KEYS.log)) as LogEntry[] | null) ?? [];
    this.loaded = true;
    this.bump();
  }
  private save(key: string, value: unknown) {
    void this.deps.store.setItem(key, JSON.stringify(value)).catch(() => {});
  }

  // --- backends ---------------------------------------------------------------------------------------------
  setBackends(backends: HomeBackend[]) {
    this.fixedBackends = backends;
    this.last.clear();
    this.recompute();
  }
  /** Adds a real service (C48). While it is connected its backend is read with the others; its state changes re-run this. */
  registerService(service: HomeService) {
    if (this.serviceList.some((s) => s.id === service.id)) return;
    this.serviceList = [...this.serviceList, service];
    this.services.set(this.serviceList);
    service.state.subscribe(() => this.recompute());
    this.recompute();
  }
  /** At startup: every service loads its secrets and sets its state. One failing never stops the others (or the app). */
  async restoreServices(): Promise<void> {
    await Promise.all(this.serviceList.map((s) => s.restore().catch(() => {})));
  }
  private recompute() {
    this.backends = [...this.fixedBackends, ...this.serviceList.filter(isConnected).map((s) => s.backend())];
    const ids = new Set(this.backends.map((b) => b.id));
    for (const [id, d] of this.last) if (!ids.has(d.backend)) this.last.delete(id); // a disconnected service's devices go
    this.bump();
  }
  /** Changes when a different set of services is being read: screens refresh on it. */
  backendKey(): string {
    return this.backends.map((b) => b.id).join(',');
  }
  get connected(): boolean {
    return this.backends.length > 0;
  }
  get demoOn(): boolean {
    return this.demo;
  }
  setDemo(on: boolean) {
    this.demo = on;
    this.save(KEYS.demo, on);
    this.bump();
  }

  // --- reading ----------------------------------------------------------------------------------------------
  /** Asks every service; a service that fails or takes over 10 s keeps its last known devices and adds a problem line. */
  async refresh(): Promise<HomeSnapshot> {
    const results = await Promise.all(
      this.backends.map(async (b) => {
        const timeout = this.deps.readTimeoutMs ?? b.timeoutMs ?? READ_TIMEOUT_MS;
        let timer: ReturnType<typeof setTimeout> | undefined;
        try {
          const devices = await Promise.race([
            b.devices(),
            new Promise<never>((_, reject) => {
              timer = setTimeout(() => reject(new Error('took too long')), timeout);
            }),
          ]);
          return { b, devices, error: null as string | null };
        } catch (e) {
          return { b, devices: null, error: toAppError(e).message.replace(/^Something went wrong: /, '') };
        } finally {
          if (timer) clearTimeout(timer);
        }
      }),
    );
    const problems: string[] = [];
    const answered: HomeDevice[][] = [];
    for (const r of results) {
      if (r.devices) answered.push(r.devices.map((d) => ({ ...d, backend: r.b.id })));
      else {
        problems.push(`${r.b.name}: ${r.error}`);
        answered.push([...this.last.values()].filter((d) => d.backend === r.b.id));
      }
    }
    // A hub's copy of a device a direct service also reports is dropped (D19).
    const direct = results.flatMap((r, i) => (r.b.isHub ? [] : answered[i]));
    const merged = results.flatMap((r, i) => (r.b.isHub ? answered[i].filter((d) => !direct.some((x) => sameDevice(x, d))) : answered[i]));
    this.last = new Map(merged.map((d) => [d.id, d]));
    this.lastProblems = problems;
    this.bump();
    return { devices: merged, problems };
  }

  /** The devices as last read, without a network call. */
  cached(): HomeDevice[] {
    return [...this.last.values()];
  }
  get problems(): string[] {
    return this.lastProblems;
  }
  /** What tools and the chat see: last read, with hidden devices left out. */
  visible(): HomeDevice[] {
    return this.cached().filter((d) => !this.hidden.has(d.id));
  }
  hiddenDevices(): HomeDevice[] {
    return this.cached().filter((d) => this.hidden.has(d.id));
  }

  // --- preferences ------------------------------------------------------------------------------------------
  isFavourite(id: string) {
    return this.favourites.has(id);
  }
  toggleFavourite(id: string) {
    if (!this.favourites.delete(id)) this.favourites.add(id);
    this.save(KEYS.favourites, [...this.favourites]);
    this.bump();
  }
  isHidden(id: string) {
    return this.hidden.has(id);
  }
  setHidden(id: string, hide: boolean) {
    if (hide) this.hidden.add(id);
    else this.hidden.delete(id);
    this.save(KEYS.hidden, [...this.hidden]);
    this.bump();
  }
  get selectedChip() {
    return this.chip;
  }
  setChip(chip: string) {
    this.chip = chip;
    this.save(KEYS.chip, chip);
    this.bump();
  }

  // --- commands ---------------------------------------------------------------------------------------------
  commandLog(): LogEntry[] {
    return this.log;
  }
  private record(entry: LogEntry) {
    this.log = [entry, ...this.log].slice(0, LOG_LIMIT);
    this.save(KEYS.log, this.log);
    this.bump();
  }
  private label(c: DeviceCommand): string {
    return 'value' in c ? `${c.type} ${c.value}` : c.type;
  }

  /** Sends a command and says what the device reports afterwards; asks again later for slow devices. */
  async command(device: HomeDevice, command: DeviceCommand, source: 'chat' | 'home'): Promise<{ ok: boolean; text: string }> {
    const backend = this.backends.find((b) => b.id === device.backend);
    if (!backend) return { ok: false, text: `${device.name}'s service isn't connected.` };
    try {
      await backend.command(device.id, command);
    } catch (e) {
      const text = `Couldn't ${this.label(command).replace(/_/g, ' ')} ${device.name}: ${toAppError(e).message.replace(/^Something went wrong: /, '')}`;
      this.record({ at: this.now(), device: device.name, action: this.label(command), ok: false, result: text, source, service: device.backend });
      return { ok: false, text };
    }
    const updated = await this.recheck(backend, device);
    const slow = device.type === 'thermostat' || device.type === 'lock' || device.type === 'garage_door';
    const text = `${device.name}: ${stateWords(updated)}${slow ? ' (this device can take a few seconds to report the change)' : ''}.`;
    this.record({ at: this.now(), device: device.name, action: this.label(command), ok: true, result: stateWords(updated), source, service: device.backend });
    for (const ms of slow ? RECHECK_SLOW_MS : RECHECK_MS) this.setTimer(() => void this.recheck(backend, device), ms);
    return { ok: true, text };
  }

  /** A fresh picture from a camera, with the service's own limit (35 s for a Ring battery camera). Throws a plain message on failure. */
  async snapshot(device: HomeDevice, signal?: AbortSignal): Promise<{ jpegBase64: string; takenAt: number }> {
    const backend = this.backends.find((b) => b.id === device.backend);
    if (!backend?.snapshot) throw new Error(`${device.name} can't show a picture.`);
    const limit = backend.snapshotTimeoutMs ?? 20_000;
    const ctl = new AbortController();
    const onAbort = () => ctl.abort();
    signal?.addEventListener('abort', onAbort);
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      return await Promise.race([
        backend.snapshot(device.id, ctl.signal),
        new Promise<never>((_, reject) => {
          timer = setTimeout(() => {
            ctl.abort();
            reject(new Error(`${device.name} didn't answer in ${Math.round(limit / 1000)} seconds.`));
          }, limit);
        }),
      ]);
    } finally {
      if (timer) clearTimeout(timer);
      signal?.removeEventListener('abort', onAbort);
    }
  }

  /**
   * What cameras and doorbells noticed since `sinceMs`, newest first (C51). `device` null: every service that has events, each with its own
   * time limit; one that fails is named in `problems` and never empties the rest.
   */
  async events(device: HomeDevice | null, sinceMs: number): Promise<{ events: DeviceEvent[]; problems: string[] }> {
    const asked = this.backends.filter((b) => b.events && (!device || b.id === device.backend));
    const problems: string[] = [];
    const lists = await Promise.all(
      asked.map(async (b) => {
        let timer: ReturnType<typeof setTimeout> | undefined;
        try {
          return await Promise.race([
            b.events!(device?.id ?? null, sinceMs),
            new Promise<never>((_, reject) => {
              timer = setTimeout(() => reject(new Error("didn't answer in time")), (b.timeoutMs ?? 10_000) * 2);
            }),
          ]);
        } catch (e) {
          problems.push(`${b.name}: ${toAppError(e).message.replace(/^Something went wrong: /, '')}`);
          return [] as DeviceEvent[];
        } finally {
          if (timer) clearTimeout(timer);
        }
      }),
    );
    return { events: lists.flat().sort((a, b) => b.at - a.at), problems };
  }

  /** True while a connected service keeps clips (a signed-out service shows no Clips chip: the C27 bug). */
  get hasClips(): boolean {
    return this.backends.some((b) => b.hasClips && b.clips);
  }

  /**
   * Clips between the two times from every connected service that has them, in parallel, each with its own time limit; newest first. A service that fails is
   * a problem line and never hides the others. `noPlan`: names of services that keep no recordings for this account (not an error).
   */
  async clips(sinceMs: number, untilMs: number): Promise<{ clips: DeviceClip[]; problems: string[]; noPlan: string[] }> {
    const problems: string[] = [];
    const noPlan: string[] = [];
    const lists = await Promise.all(
      this.backends
        .filter((b) => b.hasClips && b.clips)
        .map(async (b) => {
          let timer: ReturnType<typeof setTimeout> | undefined;
          try {
            const page = await Promise.race([
              b.clips!(sinceMs, untilMs),
              new Promise<never>((_, reject) => {
                timer = setTimeout(() => reject(new Error("didn't answer in time")), (this.deps.readTimeoutMs ?? b.timeoutMs ?? READ_TIMEOUT_MS) * 2);
              }),
            ]);
            if (page.noPlan) noPlan.push(b.name);
            return page.clips.map((c) => ({ ...c, backend: b.id }));
          } catch (e) {
            problems.push(`${b.name}: ${toAppError(e).message.replace(/^Something went wrong: /, '')}`);
            return [] as DeviceClip[];
          } finally {
            if (timer) clearTimeout(timer);
          }
        }),
    );
    return { clips: lists.flat().sort((a, b) => b.at - a.at), problems, noPlan };
  }

  /** Where to play a clip and with which header, asked at play time because addresses expire. Throws words for the owner. */
  async clipSource(clip: DeviceClip): Promise<ClipSource> {
    const backend = this.backends.find((b) => b.id === clip.backend);
    if (!backend?.clipSource) throw new Error(`${clip.deviceName}'s service isn't connected.`);
    const source = await backend.clipSource(clip.id);
    if (!source.uri.toLowerCase().startsWith('https://')) throw new Error("Edith won't play a clip from an address that isn't secure.");
    return source;
  }

  private async recheck(backend: HomeBackend, device: HomeDevice): Promise<HomeDevice> {
    try {
      const state = await backend.state(device.id);
      const updated = { ...(this.last.get(device.id) ?? device), state, stateAt: this.now(), online: true };
      this.last.set(device.id, updated);
      this.bump();
      return updated;
    } catch {
      return this.last.get(device.id) ?? device;
    }
  }
}
