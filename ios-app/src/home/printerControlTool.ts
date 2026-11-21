import type { PrinterCommand } from '../printer/feed/commands';
import type { PrinterAccess, PrinterFeedSource } from '../printer/feed/feed';
import { PrinterWords } from '../printer/feed/words';
import { failed, ok, type ConfirmationSpec, type Tool, type ToolResult } from '../tools/framework/tool';
import type { ToolArgs } from '../tools/framework/toolArgs';

export const PRINTER_ACTIONS = ['pause', 'resume', 'cancel', 'light'];

type Parsed = { ok: true; command: PrinterCommand; action: string } | { ok: false; why: string };

function parse(args: ToolArgs): Parsed {
  const action = (args.string('action') ?? '').trim().toLowerCase();
  switch (action) {
    case 'pause':
      return { ok: true, command: { kind: 'pause' }, action };
    case 'resume':
      return { ok: true, command: { kind: 'resume' }, action };
    case 'cancel':
      return { ok: true, command: { kind: 'cancel' }, action };
    case 'light': {
      const text = (args.string('level') ?? '').trim().replace(/%$/, '').trim().replace(',', '.');
      const n = text === '' ? 100 : /^[+-]?(\d+\.?\d*|\.\d+)$/.test(text) ? Number(text) : NaN;
      if (!Number.isFinite(n)) return { ok: false, why: 'The light level must be a number from 0 to 100.' };
      return { ok: true, command: { kind: 'light', level: Math.min(100, Math.max(0, n)) / 100 }, action };
    }
    default:
      return { ok: false, why: 'The action must be pause, resume, cancel or light.' };
  }
}

const done = (p: Extract<Parsed, { ok: true }>): string => {
  const c = p.command;
  if (c.kind === 'pause') return 'paused the print';
  if (c.kind === 'resume') return 'resumed the print';
  if (c.kind === 'cancel') return 'cancelled the print';
  if (c.kind === 'light') return `set the printer's light to ${Math.round(c.level * 100)}%`;
  return p.action;
};

const fallbackTitle = (p: Extract<Parsed, { ok: true }>): string => {
  const c = p.command;
  if (c.kind === 'pause') return 'Pause the print?';
  if (c.kind === 'resume') return 'Resume the print?';
  if (c.kind === 'cancel') return "Cancel the print? It can't be continued.";
  if (c.kind === 'light') return c.level <= 0 ? "Turn the printer's light off?" : `Set the printer's light to ${Math.round(c.level * 100)}%?`;
  return 'Send this to the printer?';
};
const fallbackConfirm = (p: Extract<Parsed, { ok: true }>): string => (p.command.kind === 'pause' ? 'Pause' : p.command.kind === 'resume' ? 'Resume' : p.command.kind === 'cancel' ? 'Cancel print' : 'Set light');

/** What Edith can do right now, or the words to say why not. */
function ready(a: PrinterAccess): { access: Extract<PrinterAccess, { kind: 'ready' }> | null; why: string } {
  switch (a.kind) {
    case 'noJarvis':
      return { access: null, why: "The owner hasn't paired Edith with Jarvis, so she can't reach the printer away from home." };
    case 'notShared':
      return { access: null, why: PrinterWords.NOT_SHARED };
    case 'away':
      return { access: null, why: a.why };
    case 'ready':
      return a.shared.control ? { access: a, why: '' } : { access: null, why: PrinterWords.CONTROL_OFF };
  }
}

/**
 * `printer_control(action, level?)` (C39 §10, D26): pause, resume or cancel the owner's print, or set the printer's light, through Jarvis, so it works away from home. It is
 * Jarvis's chat path: Jarvis accepts only these four, needs no Controls switch on the phone, and still refuses to send without the owner's yes. So **every call shows a card
 * first, and nothing is sent without one**; the card's words are Jarvis's own (a dry run asks what it would say). **Cancelling needs a tap on the screen, never a voice**
 * (`screenOnly`). `home_control` on the printer is unchanged: it is for the printer on the home Wi-Fi; this one is for anywhere.
 */
export function printerControlTool(deps: {
  feed: PrinterFeedSource;
  /** Whether the paired Jarvis shares the printer with control, as last read: when it does not, the tool is not offered at all. */
  mayControl: () => boolean;
  uuid: () => string;
}): Tool {
  /** Set when `confirmation` returned a card for the call that runs next; `execute` sends nothing without it. */
  let carded = false;
  /** Set when Jarvis's dry run said no: `execute` says why instead of sending. */
  let refusal: string | null = null;
  return {
    spec: {
      name: 'printer_control',
      description: "Pause, resume or cancel the owner's 3D print, or set the printer's light, through Jarvis (works away from home). Every call shows the owner a card first.",
      parameters: [
        { name: 'action', type: 'string', description: 'What to do', required: true, enumValues: PRINTER_ACTIONS, aliases: ['command'] },
        { name: 'level', type: 'string', description: 'For light: 0 (off) to 100 (full); without it the light goes to 100', aliases: ['brightness', 'value', 'percent'] },
      ],
    },
    statusLabel: 'Working on the printer…',
    displayName: 'Printer',
    enabled: () => deps.mayControl(),

    async confirmation(args): Promise<ConfirmationSpec | null> {
      carded = false;
      refusal = null;
      const p = parse(args);
      if (!p.ok) return null; // execute explains
      const { access } = ready(await deps.feed.access());
      if (!access) return null; // execute explains
      // A dry run: Jarvis says what it would ask, and whether it would refuse (then there is no card, and execute says why).
      const r = await access.api.command(p.command, 'chat', { confirmed: false, dryRun: true });
      const cancel = p.command.kind === 'cancel';
      if (r.kind === 'checked' && !r.check.allowed) {
        refusal = r.check.reason && r.check.reason.trim() ? r.check.reason : 'Jarvis says no to that right now.';
        return null;
      }
      // When the dry run could not be had, the owner still decides: the card says the same thing in Edith's own words.
      const check = r.kind === 'checked' ? r.check : null;
      carded = true;
      return {
        title: check?.question && check.question.trim() ? check.question : fallbackTitle(p),
        details: [['Printer', access.shared.name.trim() || '3D printer']],
        confirmLabel: check?.confirm && check.confirm.trim() ? check.confirm : fallbackConfirm(p),
        // "Cancel" on both buttons would mean "stop the print" on one and "keep printing" on the other.
        denyLabel: cancel ? 'Keep printing' : 'Cancel',
        screenOnly: cancel,
      };
    },

    async execute(args): Promise<ToolResult> {
      const wasCarded = carded;
      const why = refusal;
      carded = false;
      refusal = null;
      const p = parse(args);
      if (!p.ok) return failed(p.why);
      const { access, why: notReady } = ready(await deps.feed.access());
      if (!access) return failed(notReady);
      if (why) return failed(why);
      // Nothing goes to the printer unless the owner was shown a card and said yes.
      if (!wasCarded) return failed("The owner wasn't asked, so nothing was sent.");
      const r = await access.api.command(p.command, 'chat', { confirmed: true, dryRun: false, requestId: deps.uuid() });
      switch (r.kind) {
        case 'sent':
          return ok(`Done: ${done(p)}. Jarvis sent ${r.sent}.`);
        case 'refused':
          return failed(r.reason);
        case 'failed':
          return failed(r.words);
        case 'controlsOff':
          return failed(PrinterWords.CONTROLS_OFF);
        case 'needsConfirm':
          return failed('Jarvis asked again; nothing was done. Ask the owner to try again.');
        case 'checked':
          return failed('Nothing was sent.');
      }
    },
  };
}
