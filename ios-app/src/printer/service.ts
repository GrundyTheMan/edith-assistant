import { bytesToBase64 } from '../core/bytes';
import { Store } from '../core/store';
import type { SecretStore } from '../core/secrets';
import type { DeviceCommand, DeviceState, HomeBackend, HomeDevice } from '../home/model';
import type { HomeService, ServiceState, SignInForm, SignInResult } from '../home/service';
import type { HttpFetch } from '../ollama/client';
import { MoonrakerApi, PrinterError } from './api';
import { normalizePrinterAddress, parseStatus, parseWebcams, resolveWebcamUrl, type PrinterReading, type Webcam } from './parse';

const SECRET = 'home.printer';
const DEVICE_ID = 'printer';

interface Saved {
  address: string;
  key?: string;
}

export interface PrinterServiceDeps {
  secrets: SecretStore;
  /** Plain HTTP on the home network: the 3D printer is one of the allowed exceptions (AGENTS.md; Moonraker has no TLS and sits at a LAN IP). */
  fetch: HttpFetch;
  now?: () => number;
}

/**
 * The 3D printer (Klipper with Moonraker) on the home Wi-Fi (C55, ported from C34). It is one device on the Home tab. Away from home
 * (or with the printer off) it is shown as not reachable with the last report kept: that is never an error banner. A wrong key, or an
 * address that isn't Moonraker, is an error in words.
 */
export class PrinterService implements HomeService {
  readonly id = 'printer';
  readonly name = '3D printer';
  readonly state = new Store<ServiceState>({ kind: 'signed_out' });
  private saved: Saved | null = null;
  private api: MoonrakerApi | null = null;
  private webcam: Webcam | null | undefined; // undefined: not asked yet
  private estimates = new Map<string, number | undefined>();
  private last: HomeDevice | null = null;
  private readonly now: () => number;

  constructor(private readonly deps: PrinterServiceDeps) {
    this.now = deps.now ?? Date.now;
  }

  signInForm = (): SignInForm => ({
    title: 'Connect your 3D printer',
    intro:
      "Type the printer's address on your home Wi-Fi, like 192.168.4.86 (use the numbers, not a name: iPhone only allows plain connections to numbered addresses). iPhone will ask to find devices on your network.",
    fields: [
      { key: 'address', label: 'Address', keyboard: 'url', hint: 'Port 7125 is added if you leave it out.' },
      { key: 'key', label: 'API key (only if your printer wants one)', secret: true, optional: true },
    ],
    submitLabel: 'Connect',
  });

  private connect(saved: Saved) {
    this.saved = saved;
    this.api = new MoonrakerApi(this.deps.fetch, saved.address, saved.key);
    this.webcam = undefined;
    this.estimates.clear();
    this.last = null;
  }

  async restore(): Promise<void> {
    try {
      const raw = await this.deps.secrets.get(SECRET);
      if (!raw) return;
      const saved = JSON.parse(raw) as Saved;
      if (!saved.address) return;
      this.connect(saved);
      this.state.set({ kind: 'connected', detail: saved.address.replace(/^https?:\/\//, '') });
    } catch {
      // a damaged value is the same as none
    }
  }

  async signIn(values: Record<string, string>): Promise<SignInResult> {
    const addr = normalizePrinterAddress(values.address ?? '');
    if (!addr.ok) return { kind: 'failed', message: addr.message };
    const key = (values.key ?? '').trim() || undefined;
    const probe = new MoonrakerApi(this.deps.fetch, addr.base, key);
    this.state.set({ kind: 'connecting' });
    try {
      await probe.status();
    } catch (e) {
      this.state.set(this.saved ? { kind: 'connected', detail: this.saved.address.replace(/^https?:\/\//, '') } : { kind: 'signed_out' });
      if (e instanceof PrinterError && e.kind === 'unreachable') {
        return { kind: 'failed', message: `Couldn't reach a printer at ${addr.base.replace(/^https?:\/\//, '')}. Is it on, and is this phone on the home Wi-Fi?` };
      }
      return { kind: 'failed', message: e instanceof Error ? e.message : "That didn't work." };
    }
    await this.deps.secrets.set(SECRET, JSON.stringify({ address: addr.base, key } satisfies Saved));
    this.connect({ address: addr.base, key });
    this.state.set({ kind: 'connected', detail: addr.base.replace(/^https?:\/\//, '') });
    return { kind: 'ok' };
  }

  async disconnect(): Promise<void> {
    await this.deps.secrets.delete(SECRET);
    this.saved = null;
    this.api = null;
    this.last = null;
    this.state.set({ kind: 'signed_out' });
  }

  backend(): HomeBackend {
    return {
      id: this.id,
      name: this.name,
      timeoutMs: 12_000,
      devices: async () => [await this.device()],
      state: async () => (await this.device()).state,
      command: (_id, command) => this.command(command),
      snapshot: (_id, signal) => this.snapshot(signal),
      snapshotTimeoutMs: 15_000,
    };
  }

  // --- reading ------------------------------------------------------------------------------------------------------------------
  private async cam(): Promise<Webcam | null> {
    if (this.webcam !== undefined) return this.webcam;
    const found = parseWebcams(await this.api!.webcams()); // `/server/webcams/list`'s `result` is `{webcams: [...]}`
    this.webcam = found[0] ?? null;
    return this.webcam;
  }

  private async reading(): Promise<PrinterReading> {
    const api = this.api!;
    const raw = await api.status();
    let estimate: number | undefined;
    const file = (raw as { print_stats?: { filename?: unknown } }).print_stats?.filename;
    if (typeof file === 'string' && file) {
      if (!this.estimates.has(file)) this.estimates.set(file, await api.estimatedTime(file));
      estimate = this.estimates.get(file);
    }
    return parseStatus(raw, estimate);
  }

  /** The printer as one device. Unreachable is a device that is "not online" with the last report; only a wrong key or a wrong address throws. */
  private async device(): Promise<HomeDevice> {
    if (!this.api) throw new PrinterError('bad', 'The printer is not connected.');
    const web = await this.cam().catch(() => null);
    const base: Omit<HomeDevice, 'online' | 'state' | 'stateAt'> = {
      id: DEVICE_ID,
      name: '3D printer',
      room: 'Workshop',
      type: 'printer',
      capabilities: web ? ['print_control', 'snapshot'] : ['print_control'],
      backend: this.id,
    };
    try {
      const r = await this.reading();
      const state: DeviceState = {
        print: { ...r.print, klipper: r.connection === 'ready' ? undefined : r.connection === 'klippy-shutdown' ? 'shutdown' : r.connection === 'klippy-startup' ? 'startup' : 'error', message: r.connection === 'ready' ? r.print.message : r.message },
        heaters: r.heaters,
      };
      this.last = { ...base, online: true, state, stateAt: this.now() };
      return this.last;
    } catch (e) {
      if (e instanceof PrinterError && e.kind === 'unreachable') {
        // Away from home: keep the last report, say it isn't reachable, and raise no banner.
        return { ...base, online: false, state: this.last?.state ?? {}, stateAt: this.last?.stateAt ?? null };
      }
      if (e instanceof PrinterError && e.kind === 'unauthorized') this.state.set({ kind: 'needs_sign_in', reason: e.message });
      throw e;
    }
  }

  // --- commands -----------------------------------------------------------------------------------------------------------------
  /** Checked first: pause only while printing, resume only while paused, cancel only with a print going. Asking for what is already so sends nothing. */
  private async command(command: DeviceCommand): Promise<void> {
    if (command.type !== 'pause' && command.type !== 'resume' && command.type !== 'cancel_print') throw new Error("The printer can't do that.");
    const api = this.api;
    if (!api) throw new PrinterError('bad', 'The printer is not connected.');
    const d = await this.device();
    if (!d.online) throw new PrinterError('unreachable', 'Not reachable. It works on your home Wi-Fi.');
    const p = d.state.print;
    if (!p || p.klipper) throw new Error(`The printer isn't ready${p?.message ? `: ${p.message}` : ''}.`);
    if (command.type === 'pause') {
      if (p.state === 'paused') return;
      if (p.state !== 'printing') throw new Error("It isn't printing, so there is nothing to pause.");
      await api.pause();
    } else if (command.type === 'resume') {
      if (p.state === 'printing') return;
      if (p.state !== 'paused') throw new Error("It isn't paused, so there is nothing to resume.");
      await api.resume();
    } else {
      if (!p.busy) throw new Error("There's no print to cancel.");
      await api.cancel();
    }
  }

  private async snapshot(signal?: AbortSignal): Promise<{ jpegBase64: string; takenAt: number }> {
    const api = this.api;
    if (!api) throw new PrinterError('bad', 'The printer is not connected.');
    const web = await this.cam();
    if (!web) throw new Error("The printer's camera isn't set up in Moonraker.");
    const bytes = await api.snapshot(resolveWebcamUrl(api.base, web.snapshotUrl), signal);
    return { jpegBase64: bytesToBase64(bytes), takenAt: this.now() };
  }
}
