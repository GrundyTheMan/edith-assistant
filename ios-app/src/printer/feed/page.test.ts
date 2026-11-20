import { memoryStore } from '../../settings/settings';
import { bannerOf, chipOf, isGreyed, messageOf, alertOf, NO_JARVIS, NOT_SHARED, NO_PRINTER, showsCards, stateOf, type PrinterUi } from './page';
import { COLLAPSED_AT_FIRST, CARD_IDS, PrinterPrefs } from './prefs';
import type { PrinterScreenState } from './session';
import { fixture } from './testkit';
import { parseStateAnswer } from './wire';
import { PrinterWords } from './words';

const NOW = 1_763_400_000_000;
const answer = (name: string, over: Record<string, unknown> = {}) => {
  const raw = fixture(name) as { state: Record<string, unknown> };
  return parseStateAnswer({ ...raw, state: { ...raw.state, ...over } });
};
const live = (name: string, over: Record<string, unknown> = {}): PrinterScreenState => ({ kind: 'live', data: answer(name, over), receivedAt: NOW, offsetMs: 0, stale: false });
const ui = (screen: PrinterScreenState, extra: Partial<PrinterUi> = {}): PrinterUi => ({ screen, name: 'FlashForge AD5X', collapsed: COLLAPSED_AT_FIRST, dismissedAlert: null, now: NOW, ...extra });

describe('what the page says for each state (C39 §6.2; the words are final)', () => {
  it('no Jarvis, not shared, connecting', () => {
    expect(messageOf(ui({ kind: 'noJarvis' }))).toBe(NO_JARVIS);
    expect(NO_JARVIS).toBe('Pair Edith with Jarvis to use the printer away from home.');
    expect(messageOf(ui({ kind: 'notShared' }))).toBe(NOT_SHARED);
    expect(NOT_SHARED).toContain('Let paired phones see the printer');
    expect(messageOf(ui({ kind: 'connecting' }))).toBeNull();
    expect(chipOf(ui({ kind: 'connecting' }))).toEqual({ text: 'Connecting…', tone: 'muted' });
    expect(showsCards(ui({ kind: 'connecting' }))).toBe(false);
  });

  it('a ready printer is connected, with the cards and no banner', () => {
    const u = ui(live('state-printing'));
    expect(chipOf(u)).toEqual({ text: 'Connected', tone: 'good' });
    expect(bannerOf(u)).toBeNull();
    expect(messageOf(u)).toBeNull();
    expect(showsCards(u)).toBe(true);
    expect(isGreyed(u)).toBe(false);
  });

  it('the printer not answering is the page greyed, with how long ago it was seen', () => {
    const u = ui(live('state-unreachable'), { now: 1_763_399_000_000 + 125_000 });
    expect(chipOf(u)).toEqual({ text: 'Printer not answering (last seen 2 min ago)', tone: 'bad' });
    expect(bannerOf(u)).toBe("Jarvis can't reach the printer (is it on?). Showing what it last saw 2 min ago.");
    expect(isGreyed(u)).toBe(true);
    expect(showsCards(u)).toBe(true);
  });

  it('klippy states use Jarvis\'s own words', () => {
    const shut = ui(live('state-klippy-shutdown'));
    expect(chipOf(shut).tone).toBe('bad');
    expect(chipOf(shut).text).toMatch(/^Klipper: shutdown/);
    expect(bannerOf(shut)).toBe(answer('state-klippy-shutdown').state.message);
    const start = ui(live('state-ready-idle', { connection: 'klippy-startup', message: 'Klipper is starting up.' }));
    expect(chipOf(start)).toEqual({ text: 'Klipper is starting…', tone: 'warn' });
    expect(bannerOf(start)).toBe('Klipper is starting up.');
    const err = ui(live('state-ready-idle', { connection: 'klippy-error', message: null }));
    expect(chipOf(err).text).toBe('Klipper: error');
    expect(bannerOf(err)).toBe('Klipper: error'); // no message: the chip's words
  });

  it('no printer set up in Jarvis', () => {
    const u = ui(live('state-ready-idle', { connection: 'none' }));
    expect(messageOf(u)).toBe(NO_PRINTER);
    expect(chipOf(u)).toEqual({ text: 'No printer set up', tone: 'muted' });
    expect(showsCards(u)).toBe(false);
  });

  it('Jarvis away shows the last page greyed with the reason, or the reason alone when there is none', () => {
    const last = answer('state-printing');
    const away = ui({ kind: 'away', last, why: PrinterWords.UNREACHABLE });
    expect(chipOf(away)).toEqual({ text: 'Jarvis not reachable', tone: 'bad' });
    expect(bannerOf(away)).toBe(PrinterWords.UNREACHABLE);
    expect(isGreyed(away)).toBe(true);
    expect(stateOf(away)).toBe(last.state);
    expect(messageOf(away)).toBeNull(); // the last page is shown
    const none = ui({ kind: 'away', last: null, why: PrinterWords.UNREACHABLE });
    expect(messageOf(none)).toBe(PrinterWords.UNREACHABLE);
    const removed = ui({ kind: 'away', last: null, why: PrinterWords.UNAUTHORIZED });
    expect(chipOf(removed).text).toBe('Jarvis removed this phone');
    expect(messageOf(removed)).toBe('Jarvis removed this phone: pair it again.');
  });

  it("the printer's own alert shows until it is dismissed, and only that one", () => {
    const u = ui(live('state-alert'));
    const a = alertOf(u)!;
    expect(a.text).toBe('Filament runout detected');
    expect(alertOf({ ...u, dismissedAlert: a.time })).toBeNull();
    expect(alertOf({ ...u, dismissedAlert: a.time + 1 })).not.toBeNull(); // a newer alert would show
    expect(alertOf(ui(live('state-printing')))).toBeNull();
  });
});

describe('the folded cards are remembered', () => {
  it('Status, Camera and Temperatures start open and the rest folded', () => {
    expect(CARD_IDS).toHaveLength(16);
    expect([...COLLAPSED_AT_FIRST].sort()).toEqual(CARD_IDS.filter((id) => !['status', 'camera', 'temperatures'].includes(id)).sort());
  });

  it('keeps the choice across restarts, and damaged data means the defaults', async () => {
    const store = memoryStore();
    const prefs = new PrinterPrefs(store);
    await prefs.load();
    expect(prefs.collapsed.get().has('status')).toBe(false);
    await prefs.setCollapsed('status', true);
    await prefs.setCollapsed('files', false);
    const again = new PrinterPrefs(store);
    await again.load();
    expect(again.collapsed.get().has('status')).toBe(true);
    expect(again.collapsed.get().has('files')).toBe(false);
    const bad = new PrinterPrefs(memoryStore({ printer_collapsed: '{nope' }));
    await bad.load();
    expect(bad.collapsed.get()).toBe(COLLAPSED_AT_FIRST);
    const junk = new PrinterPrefs(memoryStore({ printer_collapsed: '["status", 5, null]' }));
    await junk.load();
    expect([...junk.collapsed.get()]).toEqual(['status']);
  });
});
