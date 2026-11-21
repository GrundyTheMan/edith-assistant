import { memoryStore } from '../../settings/settings';
import { PrinterCardData, MacroList } from './cards';
import { ConsoleHistory } from './consoleHistory';
import { PrinterFeed } from './feed';
import { PrinterPrefs } from './prefs';
import { PrinterSession } from './session';
import { connectedFeed, fixture } from './testkit';
import { parseMacros, parseSettings } from './wire';

const flush = async () => {
  for (let i = 0; i < 60; i++) await Promise.resolve();
};

describe('the console history (as Android\'s ConsoleHistory)', () => {
  it('newest first, no repeats, trimmed, at most 50', () => {
    expect(ConsoleHistory.push([], '  M115 ')).toEqual(['M115']);
    expect(ConsoleHistory.push(['b', 'a'], 'a')).toEqual(['a', 'b']);
    expect(ConsoleHistory.push(['a'], '   ')).toEqual(['a']);
    const full = Array.from({ length: 50 }, (_, i) => `l${i}`);
    const next = ConsoleHistory.push(full, 'new');
    expect(next).toHaveLength(50);
    expect(next[0]).toBe('new');
    expect(next).not.toContain('l49');
  });
  it('the arrows stop at the ends', () => {
    const h = ['c', 'b', 'a'];
    expect(ConsoleHistory.older(h, -1)).toBe(0);
    expect(ConsoleHistory.older(h, 1)).toBe(2);
    expect(ConsoleHistory.older(h, 2)).toBe(2);
    expect(ConsoleHistory.older([], -1)).toBe(-1);
    expect(ConsoleHistory.newer(2)).toBe(1);
    expect(ConsoleHistory.newer(0)).toBe(-1);
    expect(ConsoleHistory.newer(-1)).toBe(-1);
    expect(ConsoleHistory.at(h, 1)).toBe('b');
    expect(ConsoleHistory.at(h, -1)).toBe('');
    expect(ConsoleHistory.at(h, 9)).toBe('');
  });
});

describe('the macro list', () => {
  const answer = parseMacros(fixture('macros'));
  it("keeps Jarvis's groups in its order, and puts a macro of an unlisted group under Other", () => {
    expect(MacroList.sections(answer, '').map((s) => [s.title, s.macros.map((m) => m.name)])).toEqual([
      ['Filament and nozzle', ['LOAD_FILAMENT']],
      ['Bed and calibration', ['BED_MESH_CALIBRATE']],
    ]);
    const odd = { ...answer, macros: [...answer.macros, { name: 'WEIRD', description: null, params: [], risk: 'confirm', group: 'nowhere' }] };
    const s = MacroList.sections(odd, '');
    expect(s.at(-1)).toMatchObject({ id: 'other', title: 'Other' });
    expect(s.at(-1)!.macros.map((m) => m.name)).toEqual(['WEIRD']);
    const named = { ...odd, groups: [...odd.groups, { id: 'other', title: 'Everything else' }] };
    expect(MacroList.sections(named, '').at(-1)!.title).toBe('Everything else');
    expect(MacroList.sections(named, '').filter((x) => x.id === 'other')).toHaveLength(1);
  });
  it('a filter matches the name or the description in any case, and a group with none is left out', () => {
    expect(MacroList.sections(answer, 'load').map((s) => s.id)).toEqual(['filament']);
    expect(MacroList.sections(answer, ' mesh ').map((s) => s.id)).toEqual(['calibration']);
    expect(MacroList.sections(answer, 'LOAD FILAMENT').map((s) => s.id)).toEqual(['filament']); // the description is "Load filament", in any case
    expect(MacroList.sections(answer, 'load_fil').map((s) => s.id)).toEqual(['filament']);
    expect(MacroList.sections(answer, 'zzz')).toEqual([]);
  });
  it('typed parameters are trimmed and an empty box is not sent', () => {
    expect(MacroList.params({ A: ' 1 ', B: '', C: '   ' })).toEqual({ A: '1' });
    expect(MacroList.params({})).toEqual({});
  });
});

describe('the settings answer', () => {
  it('is read as Jarvis sent it, and damaged data is empty', () => {
    expect(parseSettings({ presets: [{ name: 'PLA', extruder: 220, bed: 60 }], hiddenMacros: ['A', 5, ''] })).toEqual({ presets: [{ name: 'PLA', extruder: 220, bed: 60 }], hiddenMacros: ['A'] });
    expect(parseSettings(null)).toEqual({ presets: [], hiddenMacros: [] });
  });
});

describe('the preferences of the console and the camera', () => {
  it('keep their choices across restarts, with damaged data meaning the defaults', async () => {
    const store = memoryStore();
    const prefs = new PrinterPrefs(store);
    await prefs.load();
    expect(prefs.consoleHideValues.get()).toBe(true);
    expect(prefs.consoleHistory.get()).toEqual([]);
    expect(prefs.camera.get()).toBeNull();
    await prefs.setConsoleHideValues(false);
    await prefs.pushConsoleHistory('M115');
    await prefs.pushConsoleHistory('G28');
    await prefs.pushConsoleHistory('M115');
    await prefs.setCamera('cam2');
    const again = new PrinterPrefs(store);
    await again.load();
    expect(again.consoleHideValues.get()).toBe(false);
    expect(again.consoleHistory.get()).toEqual(['M115', 'G28']);
    expect(again.camera.get()).toBe('cam2');
    const bad = new PrinterPrefs(memoryStore({ printer_console_hide_values: '"x"', printer_console_history: '{nope' }));
    await bad.load();
    expect(bad.consoleHideValues.get()).toBe(true);
    expect(bad.consoleHistory.get()).toEqual([]);
    const many = new PrinterPrefs(memoryStore({ printer_console_history: JSON.stringify(Array.from({ length: 60 }, (_, i) => `l${i}`)) }));
    await many.load();
    expect(many.consoleHistory.get()).toHaveLength(50);
    const junk = new PrinterPrefs(memoryStore({ printer_console_history: '["a", 5, "  ", null, "b"]' }));
    await junk.load();
    expect(junk.consoleHistory.get()).toEqual(['a', 'b']);
  });
});

describe('the macros, presets and console data against the pretend feed', () => {
  async function setup() {
    const { feed, service } = await connectedFeed();
    const session = new PrinterSession({ feed: new PrinterFeed(service) });
    const prefs = new PrinterPrefs(memoryStore());
    const data = new PrinterCardData({ session, prefs, delay: () => new Promise(() => {}) });
    session.attach();
    for (let i = 0; i < 80 && session.state.get().kind !== 'live'; i++) await flush();
    return { feed, session, prefs, data };
  }

  it('the macros are read when the card opens, once, and again after leaving and coming back', async () => {
    const { feed, session, data } = await setup();
    expect(feed.count('/v1/printer/macros')).toBe(0);
    data.visible('macros', true);
    await flush();
    expect(feed.count('/v1/printer/macros')).toBe(1);
    expect(data.ui.get().macros.answer!.macros).toHaveLength(2);
    expect(data.ui.get().macros.answer!.hidden).toEqual(['SECRET_MACRO']);
    data.visible('macros', false);
    data.visible('macros', true);
    await flush();
    expect(feed.count('/v1/printer/macros')).toBe(1);
    data.detached();
    data.attached();
    await flush();
    expect(feed.count('/v1/printer/macros')).toBe(2);
    data.setMacroQuery('mesh');
    expect(data.ui.get().macros.query).toBe('mesh');
    session.detach();
  });

  it('a macro reply that arrives after the page left is dropped', async () => {
    const { session, data } = await setup();
    data.visible('macros', true);
    data.detached();
    await flush();
    expect(data.ui.get().macros.answer).toBeNull();
    expect(data.ui.get().macros.loading).toBe(false);
    session.detach();
  });

  it('hiding a macro sends the new list to Jarvis and reads the macros again; without Controls it says so in words', async () => {
    const { feed, session, data } = await setup();
    data.visible('macros', true);
    await flush();
    await data.setHiddenMacros(['SECRET_MACRO', 'LOAD_FILAMENT']);
    expect(data.ui.get().macros.error).toBe('Controls turned off. Turn them on to continue.');
    expect(feed.savedHidden).toBeNull();
    feed.armed = true;
    await data.setHiddenMacros(['SECRET_MACRO', 'LOAD_FILAMENT']);
    expect(feed.savedHidden).toEqual(['SECRET_MACRO', 'LOAD_FILAMENT']);
    expect(feed.count('/v1/printer/macros')).toBe(2);
    expect(feed.requests.filter((r) => r.path === '/v1/printer/settings').at(-1)!.body).toEqual({ hiddenMacros: ['SECRET_MACRO', 'LOAD_FILAMENT'] }); // no presets sent
    session.detach();
  });

  it('saving presets sends only the presets, tells the page what Jarvis kept, and counts the save', async () => {
    const { feed, session, data } = await setup();
    feed.armed = true;
    const saved: unknown[] = [];
    (session as { updatePresets: (p: unknown[]) => void }).updatePresets = (p) => saved.push(p);
    await data.savePresets([{ name: 'PLA', extruder: 215, bed: 55 }]);
    expect(feed.requests.filter((r) => r.path === '/v1/printer/settings').at(-1)!.body).toEqual({ presets: [{ name: 'PLA', extruder: 215, bed: 55 }] });
    expect(saved).toEqual([[{ name: 'PLA', extruder: 215, bed: 55 }]]);
    expect(data.ui.get().presets).toEqual({ saving: false, error: null, saved: 1 });
    session.detach();
  });

  it('a refused save keeps the dialog open with Jarvis\'s words, and clearing the error works', async () => {
    const { feed, session, data } = await setup();
    feed.armed = false;
    await data.savePresets([{ name: 'PLA', extruder: 215, bed: 55 }]);
    expect(data.ui.get().presets).toMatchObject({ saving: false, saved: 0, error: 'Controls turned off. Turn them on to continue.' });
    data.clearPresetsError();
    expect(data.ui.get().presets.error).toBeNull();
    session.detach();
  });

  it('two saves at once send one', async () => {
    const { feed, session, data } = await setup();
    feed.armed = true;
    await Promise.all([data.savePresets([]), data.savePresets([])]);
    expect(feed.requests.filter((r) => r.path === '/v1/printer/settings')).toHaveLength(1);
    session.detach();
  });

  it('the console switch, history and camera choice are kept in the preferences and shown on the cards', async () => {
    const { prefs, session, data } = await setup();
    data.setHideValues(false);
    await flush();
    expect(data.ui.get().terminal.hideValues).toBe(false);
    expect(prefs.consoleHideValues.get()).toBe(false);
    data.rememberConsoleLine('M115');
    data.rememberConsoleLine('G28');
    await flush();
    expect(data.ui.get().terminal.history).toEqual(['G28', 'M115']);
    data.chooseCamera('cam2');
    await flush();
    expect(prefs.camera.get()).toBe('cam2');
    expect(data.ui.get().camera.cameraId).toBe('cam2');
    session.detach();
  });
});
