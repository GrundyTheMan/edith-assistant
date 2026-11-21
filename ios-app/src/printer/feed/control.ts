import { Store } from '../../core/store';
import type { CommandAnswer } from './api';
import type { PrinterCommand } from './commands';
import { settled, settleTimeoutMs } from './settle';
import type { PrinterScreenState, PrinterSession } from './session';
import { Connections, Risks, type ArmState, type Check, type PrinterState, type StateAnswer } from './wire';
import { PrinterWords, wordsForFailure } from './words';

/** The owner's yes is being asked for: `question` and `confirm` are Jarvis's own words (C39 §7.2). */
export interface ConfirmUi {
  question: string;
  confirm: string;
  danger: boolean;
  command: PrinterCommand;
  requestId: string;
  /** The other button: never the same word as the confirm button, never "OK" or "Yes". */
  keepLabel: string;
}

export interface ControlUi {
  /** The owner allows this phone to control the printer (Jarvis's switch). When false nothing that sends is drawn. */
  mayControl: boolean;
  /** Controls are on (the server's answer, or what we were just told while waiting for the next state). */
  armed: boolean;
  armUntilMs: number | null;
  /** Jarvis's clock minus the phone's, for the countdown. */
  offsetMs: number;
  sheet: ConfirmUi | null;
  /** The control keys (see `controlKey`) whose command was sent and has not shown yet. */
  pending: ReadonlySet<string>;
  /** A line for the owner: the server's refusal, or why Controls went off. Shown once. */
  message: string | null;
  /** The server's refusal under the control that asked (a jog before homing: "Home the printer first."). */
  refused: Readonly<Record<string, string>>;
  /** Whether a send is in flight. */
  busy: boolean;
}

export const initialControl = (): ControlUi => ({ mayControl: false, armed: false, armUntilMs: null, offsetMs: 0, sheet: null, pending: new Set(), message: null, refused: {}, busy: false });

/** Which control a command belongs to: its pending spinner and its inline refusal are kept under this key. */
export function controlKey(c: PrinterCommand): string {
  switch (c.kind) {
    case 'home':
      return `home:${c.axes}`;
    case 'setTemp':
      return `setTemp:${c.heater}`;
    case 'preset':
      return `preset:${c.name}`;
    case 'fan':
      return `fan:${c.fan}`;
    case 'macro':
      return `macro:${c.name}`;
    default:
      return c.kind;
  }
}

export const SHEET_MS = 10_000;

export interface ControlDeps {
  session: Pick<PrinterSession, 'state' | 'shared' | 'routes' | 'refreshNow'>;
  now?: () => number;
  uuid: () => string;
  /** Runs `fn` after `ms`; returns a function that cancels it. Tests give their own clock. */
  schedule?: (fn: () => void, ms: number) => () => void;
}

const realSchedule = (fn: () => void, ms: number) => {
  const t = setTimeout(fn, ms);
  return () => clearTimeout(t);
};

interface Pending {
  command: PrinterCommand;
  before: PrinterState;
  sentAtMs: number;
  sawMotion: boolean;
}

/**
 * Controls, the confirm flow and the pending buttons of the Printer page (C39 §7.1-7.3, C67 E7). The server decides everything: what is allowed, what needs the owner's yes.
 * This only asks, shows the server's words, and never sends `confirmed` unless the owner pressed the confirm button of a sheet that showed the server's question.
 */
export class PrinterControl {
  readonly ui = new Store<ControlUi>(initialControl());
  /** Told of every command Jarvis accepted (E8: the Files sheet closes on Print, Add to queue and Delete). */
  onSent: (command: PrinterCommand) => void = () => {};
  private readonly pending = new Map<string, Pending>();
  private readonly timeouts = new Map<string, () => void>();
  private cancelSheet: (() => void) | null = null;
  private armLocal: ArmState | null = null;
  private lastRev = -1;
  private readonly now: () => number;
  private readonly schedule: (fn: () => void, ms: number) => () => void;

  constructor(private readonly deps: ControlDeps) {
    this.now = deps.now ?? Date.now;
    this.schedule = deps.schedule ?? realSchedule;
    deps.session.state.subscribe(() => this.onState(deps.session.state.get()));
    deps.session.shared.subscribe(() => this.patch({ mayControl: deps.session.shared.get()?.control === true }));
  }

  private patch(p: Partial<ControlUi>) {
    this.ui.set({ ...this.ui.get(), ...p });
  }

  // --- Controls ---

  /** The Controls switch. On: Jarvis starts a timer for this phone. Off: Jarvis ends it. Either way the page follows Jarvis's answer. */
  async setControls(on: boolean): Promise<void> {
    const routes = this.deps.session.routes();
    if (!routes) return;
    const r = await routes.controls(on);
    if (r.kind === 'ok') this.setArm(r.value);
    else this.patch({ message: wordsForFailure(r) });
    this.deps.session.refreshNow();
  }

  private setArm(arm: ArmState) {
    this.armLocal = arm;
    this.patch({ armed: arm.armed, armUntilMs: arm.until });
  }

  /** Controls end with the page, a printer that is not ready, or Jarvis being away; Jarvis's timer is the guarantee, this is the courtesy. */
  private disarmQuietly() {
    const routes = this.deps.session.routes();
    if (!routes) return;
    this.armLocal = { armed: false, until: null };
    this.patch({ armed: false, armUntilMs: null });
    void routes.controls(false).catch(() => {});
  }

  // --- Sending ---

  /** Asks Jarvis to send `command`. A command the owner must confirm opens the sheet; nothing is confirmed here. */
  async send(command: PrinterCommand, requestId: string = this.deps.uuid()): Promise<void> {
    const routes = this.deps.session.routes();
    if (!routes || this.ui.get().busy) return;
    const { [controlKey(command)]: _gone, ...refused } = this.ui.get().refused;
    this.patch({ busy: true, message: null, refused });
    try {
      this.handle(command, requestId, await routes.command(command, 'phone', { confirmed: false, dryRun: false, requestId }), false);
    } finally {
      this.patch({ busy: false });
    }
  }

  /** The owner pressed the confirm button of the open sheet. */
  async confirm(): Promise<void> {
    const sheet = this.ui.get().sheet;
    const routes = this.deps.session.routes();
    if (!sheet || !routes) return;
    this.closeSheet();
    this.patch({ busy: true });
    try {
      this.handle(sheet.command, sheet.requestId, await routes.command(sheet.command, 'phone', { confirmed: true, dryRun: false, requestId: sheet.requestId }), true);
    } finally {
      this.patch({ busy: false });
    }
  }

  /** Keep going: the sheet closes and nothing is sent. */
  dismissSheet(): void {
    this.closeSheet();
  }

  private closeSheet() {
    this.cancelSheet?.();
    this.cancelSheet = null;
    if (this.ui.get().sheet) this.patch({ sheet: null });
  }

  private handle(command: PrinterCommand, requestId: string, answer: CommandAnswer, confirmedAlready: boolean) {
    const key = controlKey(command);
    switch (answer.kind) {
      case 'sent':
        this.sent(command);
        break;
      case 'needsConfirm':
        if (confirmedAlready) this.patch({ message: 'Jarvis asked again. Try again.' });
        else this.openSheet(command, requestId, answer.check);
        break;
      case 'controlsOff':
        this.armLocal = { armed: false, until: null };
        this.patch({ armed: false, armUntilMs: null, message: PrinterWords.CONTROLS_OFF });
        break;
      case 'refused':
        this.patch({ message: answer.reason, refused: { ...this.ui.get().refused, [key]: answer.reason } });
        break;
      case 'failed':
        this.patch({ message: answer.words });
        break;
      case 'checked':
        break;
    }
  }

  private openSheet(command: PrinterCommand, requestId: string, check: Check) {
    const confirm = command.kind === 'estop' ? 'Stop now' : check.confirm && check.confirm.trim() ? check.confirm : 'Confirm';
    const question = check.question && check.question.trim() ? check.question : `Send ${command.kind}?`;
    this.cancelSheet?.();
    this.patch({ sheet: { question, confirm, danger: check.risk === Risks.danger, command, requestId, keepLabel: /cancel/i.test(confirm) ? 'Keep printing' : 'Keep going' } });
    // A sheet nobody answers closes itself (10 s).
    this.cancelSheet = this.schedule(() => {
      this.cancelSheet = null;
      this.patch({ sheet: null });
    }, SHEET_MS);
  }

  // --- Pending buttons ---

  private sent(command: PrinterCommand) {
    this.onSent(command);
    const key = controlKey(command);
    const timeout = settleTimeoutMs(command);
    const s = this.deps.session.state.get();
    const before = s.kind === 'live' ? s.data.state : null;
    if (timeout > 0 && before) {
      this.pending.set(key, { command, before, sentAtMs: this.now(), sawMotion: false });
      this.timeouts.get(key)?.();
      this.timeouts.set(key, this.schedule(() => this.finish(key), timeout));
      this.publishPending();
    }
    this.deps.session.refreshNow();
  }

  private finish(key: string) {
    this.pending.delete(key);
    this.timeouts.get(key)?.();
    this.timeouts.delete(key);
    this.publishPending();
  }

  private publishPending() {
    this.patch({ pending: new Set(this.pending.keys()) });
  }

  // --- The live state ---

  private onState(s: PrinterScreenState) {
    if (s.kind === 'live') {
      if (!s.stale) this.onAnswer(s.data, s.offsetMs);
    } else if (s.kind === 'away') {
      if (this.ui.get().armed) {
        this.armLocal = { armed: false, until: null };
        this.patch({ armed: false, armUntilMs: null });
      }
      this.clearPending();
    }
  }

  private onAnswer(a: StateAnswer, offsetMs: number) {
    if (a.rev === this.lastRev && this.armLocal === null) return;
    this.lastRev = a.rev;
    // Jarvis's word beats what we guessed from our own answer.
    this.armLocal = null;
    this.patch({ armed: a.arm.armed, armUntilMs: a.arm.until, offsetMs });
    if (a.arm.armed && a.state.connection !== Connections.ready) this.disarmQuietly();
    const now = this.now();
    for (const [key, p] of [...this.pending]) {
      const saw = p.sawMotion || a.state.toolhead.liveVelocity >= 1;
      p.sawMotion = saw;
      if (settled(p.command, p.before, a.state, { elapsedMs: now - p.sentAtMs, sawMotion: saw, presets: this.deps.session.shared.get()?.presets ?? [] })) this.finish(key);
    }
  }

  private clearPending() {
    for (const cancel of this.timeouts.values()) cancel();
    this.timeouts.clear();
    this.pending.clear();
    this.publishPending();
  }

  /** A line was shown: it is not shown again. */
  messageShown(): void {
    this.patch({ message: null });
  }

  /** The page left the screen: nothing pending carries over, and the sheet closes. */
  detached(): void {
    this.closeSheet();
    this.clearPending();
    this.armLocal = null;
    this.patch({ armed: false, armUntilMs: null, message: null, refused: {}, busy: false });
  }
}
