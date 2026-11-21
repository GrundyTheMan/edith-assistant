import { age } from './format';
import type { PrinterScreenState } from './session';
import { Connections, type PrinterAlert, type Preset, type PrinterState } from './wire';
import { PrinterWords } from './words';

/** How strongly the connection chip should look: from "all well" to "something is wrong". */
export type Tone = 'good' | 'warn' | 'bad' | 'muted';
export interface ConnectionChip {
  text: string;
  tone: Tone;
}

export const NO_JARVIS = 'Pair Edith with Jarvis to use the printer away from home.';
export const NOT_SHARED = "Jarvis isn't sharing the printer with this phone. In Jarvis, open Settings, find Share work data with Edith, and switch on Let paired phones see the printer.";
export const NO_PRINTER = 'No printer is set up in Jarvis.';
export const CONNECTING = 'Connecting to Jarvis…';

const dash = (m: string | null) => (m && m.trim() ? ` – ${m}` : '');

/** Everything the Printer page draws, worked out from the session's state (C39 §6.2 and §8.3; ported from Android's `PrinterUi`). Pure, so it is tested without a screen. */
export interface PrinterUi {
  screen: PrinterScreenState;
  /** The printer's name from Jarvis; "Printer" until Jarvis has said. */
  name: string;
  collapsed: ReadonlySet<string>;
  /** The `alert.time` the owner dismissed; the same alert stays hidden until the page is closed. */
  dismissedAlert: number | null;
  /** The phone's clock, in ms; moves every 30 s so the ages on the page stay right. */
  now: number;
  /** The temperature presets Jarvis offers this phone. */
  presets?: Preset[];
}

/** The printer's state to draw: the live one, or the last one while Jarvis is away. Null when there is none. */
export function stateOf(ui: PrinterUi): PrinterState | null {
  const s = ui.screen;
  if (s.kind === 'live') return s.data.state;
  if (s.kind === 'away') return s.last?.state ?? null;
  return null;
}

/** The server's answer, for each parameterless command, to whether it can be sent now (`pause`, `home:xyz`, ...). */
export const quickOf = (ui: PrinterUi) => (ui.screen.kind === 'live' ? ui.screen.data.quick : {});

/** The page's cards are drawn: a state is available and a printer is set up. */
export const showsCards = (ui: PrinterUi): boolean => {
  const st = stateOf(ui);
  return st !== null && st.connection !== Connections.none;
};

/** Drawn greyed with its controls off: Jarvis is away, or Jarvis cannot reach the printer. */
export const isGreyed = (ui: PrinterUi): boolean => ui.screen.kind === 'away' || stateOf(ui)?.connection === Connections.unreachable;

/** The words that sit in the pinned header, next to the name. */
export function chipOf(ui: PrinterUi): ConnectionChip {
  const st = stateOf(ui);
  const s = ui.screen;
  if (s.kind === 'away') return { text: s.why === PrinterWords.UNAUTHORIZED ? 'Jarvis removed this phone' : 'Jarvis not reachable', tone: 'bad' };
  if (s.kind === 'connecting') return { text: 'Connecting…', tone: 'muted' };
  if (!st) return { text: '', tone: 'muted' };
  switch (st.connection) {
    case Connections.ready:
      return { text: 'Connected', tone: 'good' };
    case Connections.none:
      return { text: 'No printer set up', tone: 'muted' };
    case Connections.connecting:
      return { text: 'Connecting…', tone: 'muted' };
    case Connections.klippyStartup:
      return { text: 'Klipper is starting…', tone: 'warn' };
    case Connections.klippyShutdown:
      return { text: `Klipper: shutdown${dash(st.message)}`, tone: 'bad' };
    case Connections.klippyError:
      return { text: `Klipper: error${dash(st.message)}`, tone: 'bad' };
    case Connections.unreachable:
      return { text: 'Printer not answering' + (st.lastSeen !== null ? ` (last seen ${age(st.lastSeen, ui.now)})` : ''), tone: 'bad' };
    default:
      return { text: st.connection, tone: 'muted' };
  }
}

/** The line above the cards that says why they may be old or limited; null when all is well. */
export function bannerOf(ui: PrinterUi): string | null {
  const st = stateOf(ui);
  const s = ui.screen;
  if (s.kind === 'away') return s.why;
  if (s.kind !== 'live' || !st) return null;
  switch (st.connection) {
    case Connections.unreachable:
      return "Jarvis can't reach the printer (is it on?). Showing what it last saw" + (st.lastSeen !== null ? ` ${age(st.lastSeen, ui.now)}` : '') + '.';
    case Connections.klippyStartup:
    case Connections.klippyShutdown:
    case Connections.klippyError:
      return st.message && st.message.trim() ? st.message : chipOf(ui).text;
    default:
      return null;
  }
}

/** The full-page message when there are no cards to show (no Jarvis, not shared, no printer, waiting), else null. */
export function messageOf(ui: PrinterUi): string | null {
  const s = ui.screen;
  if (s.kind === 'noJarvis') return NO_JARVIS;
  if (s.kind === 'notShared') return NOT_SHARED;
  if (s.kind === 'connecting') return null;
  const st = stateOf(ui);
  if (st?.connection === Connections.none) return NO_PRINTER;
  if (st === null) return s.kind === 'away' ? s.why : null;
  return null;
}

/** The printer's own latest error line, unless the owner dismissed that one. */
export function alertOf(ui: PrinterUi): PrinterAlert | null {
  const a = stateOf(ui)?.alert ?? null;
  return a && a.time !== ui.dismissedAlert ? a : null;
}
