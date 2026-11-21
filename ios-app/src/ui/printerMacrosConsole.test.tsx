import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { serializeCommand, type PrinterCommand } from '../printer/feed/commands';
import { initialControl, type ControlUi } from '../printer/feed/control';
import { COLLAPSED_AT_FIRST } from '../printer/feed/prefs';
import type { PrinterUi } from '../printer/feed/page';
import { fixture } from '../printer/feed/testkit';
import { parseMacros, parsePrinterState, parseStateAnswer } from '../printer/feed/wire';
import { MAX_LINE } from '../printer/feed/input';
import { TemperatureControls, type ControlsCtx } from './printer/CardControls';
import { ConsoleInput, MacrosCard, PresetsDialog } from './printer/MacrosAndConsole';
import { themes } from './theme';

const theme = themes.light;
const NOW = 1_763_400_000_000;
const pageUi = (): PrinterUi => ({ screen: { kind: 'live', data: parseStateAnswer(fixture('state-printing')), receivedAt: NOW, offsetMs: 0, stale: false }, name: 'P', collapsed: COLLAPSED_AT_FIRST, dismissedAlert: null, now: NOW, presets: [] });
function ctxOf(over: Partial<ControlUi> = {}) {
  const sent: string[] = [];
  const ctx: ControlsCtx = { ui: pageUi(), controls: { ...initialControl(), mayControl: true, armed: true, ...over }, send: (c: PrinterCommand) => sent.push(serializeCommand(c)), theme };
  return { ctx, sent };
}
const press = (label: string) => act(async () => fireEvent.press(screen.getByLabelText(label)));
const type = (label: string, text: string) => act(async () => fireEvent.changeText(screen.getByLabelText(label), text));
const macros = (over: Record<string, unknown> = {}) => ({ answer: parseMacros(fixture('macros')), loading: false, error: null, query: '', ...over }) as never;

describe('the Macros card', () => {
  it('shows Jarvis\'s groups and macros; a macro without parameters is one button that runs it', async () => {
    const { ctx, sent } = ctxOf();
    await render(<MacrosCard macros={macros()} ctx={ctx} onQuery={() => {}} onHidden={() => {}} />);
    expect(screen.getByText('Filament and nozzle (1)')).toBeTruthy();
    expect(screen.getByText('Bed and calibration (1)')).toBeTruthy();
    expect(screen.getByText('Load filament')).toBeTruthy(); // the description
    await press('LOAD_FILAMENT');
    expect(sent).toEqual(['{"kind":"macro","name":"LOAD_FILAMENT"}']);
  });

  it('a macro with parameters opens a form (the default is the hint); only typed boxes are sent; a dangerous one looks it', async () => {
    const { ctx, sent } = ctxOf();
    await render(<MacrosCard macros={macros()} ctx={ctx} onQuery={() => {}} onHidden={() => {}} />);
    expect(screen.queryByLabelText('BED_MESH_CALIBRATE PROFILE')).toBeNull();
    expect(JSON.stringify(screen.getByLabelText('BED_MESH_CALIBRATE').props.style)).toContain(theme.error);
    await press('BED_MESH_CALIBRATE');
    expect(screen.getByLabelText('BED_MESH_CALIBRATE PROFILE').props.placeholder).toBe('default');
    await press('Run BED_MESH_CALIBRATE');
    await type('BED_MESH_CALIBRATE PROFILE', '  adaptive ');
    await press('Run BED_MESH_CALIBRATE');
    expect(sent).toEqual(['{"kind":"macro","name":"BED_MESH_CALIBRATE"}', '{"kind":"macro","name":"BED_MESH_CALIBRATE","params":{"PROFILE":"adaptive"}}']);
    await press('Close BED_MESH_CALIBRATE');
    expect(screen.queryByLabelText('BED_MESH_CALIBRATE PROFILE')).toBeNull();
  });

  it('a group folds, but while the filter has text every group is open', async () => {
    const { ctx } = ctxOf();
    await render(<MacrosCard macros={macros()} ctx={ctx} onQuery={() => {}} onHidden={() => {}} />);
    await press('Fold Filament and nozzle');
    expect(screen.queryByLabelText('LOAD_FILAMENT')).toBeNull();
    expect(screen.getByLabelText('Open Filament and nozzle')).toBeTruthy();
    await screen.rerender(<MacrosCard macros={macros({ query: 'load' })} ctx={ctx} onQuery={() => {}} onHidden={() => {}} />); // the same card, still folded: the filter opens it
    expect(screen.getByLabelText('LOAD_FILAMENT')).toBeTruthy();
    expect(screen.queryByLabelText('BED_MESH_CALIBRATE')).toBeNull(); // filtered out
    await render(<MacrosCard macros={macros({ query: 'zzz' })} ctx={ctx} onQuery={() => {}} onHidden={() => {}} />);
    expect(screen.getByText('No macros match.')).toBeTruthy();
  });

  it('the filter box asks to filter', async () => {
    const { ctx } = ctxOf();
    const queries: string[] = [];
    await render(<MacrosCard macros={macros()} ctx={ctx} onQuery={(q) => queries.push(q)} onHidden={() => {}} />);
    await type('Filter macros', 'mesh');
    expect(queries).toEqual(['mesh']);
  });

  it('Hide adds the macro to Jarvis\'s hidden list; Show takes one off; the hidden ones are listed', async () => {
    const { ctx } = ctxOf();
    const calls: string[][] = [];
    await render(<MacrosCard macros={macros()} ctx={ctx} onQuery={() => {}} onHidden={(h) => calls.push(h)} />);
    expect(screen.getByText('Hidden (1)')).toBeTruthy();
    await press('Hide LOAD_FILAMENT');
    await press('Show SECRET_MACRO');
    expect(calls).toEqual([['SECRET_MACRO', 'LOAD_FILAMENT'], []]);
  });

  it('while loading, or with nothing yet, it says so; Controls off disables every run with the reason; a refusal shows under its macro', async () => {
    const { ctx } = ctxOf();
    await render(<MacrosCard macros={macros({ answer: null, loading: true })} ctx={ctx} onQuery={() => {}} onHidden={() => {}} />);
    expect(screen.getByTestId('macros-card')).toBeTruthy();
    await render(<MacrosCard macros={macros({ answer: null, error: 'Jarvis is not answering.' })} ctx={ctx} onQuery={() => {}} onHidden={() => {}} />);
    expect(screen.getByText('Jarvis is not answering.')).toBeTruthy();
    const off = ctxOf({ armed: false });
    await render(<MacrosCard macros={macros()} ctx={off.ctx} onQuery={() => {}} onHidden={() => {}} />);
    expect(screen.getByLabelText('LOAD_FILAMENT').props.accessibilityState.disabled).toBe(true);
    expect(screen.getByTestId('why-off')).toHaveTextContent('Turn on Controls to use this');
    const refused = ctxOf({ refused: { 'macro:LOAD_FILAMENT': 'Not while printing.' } });
    await render(<MacrosCard macros={macros()} ctx={refused.ctx} onQuery={() => {}} onHidden={() => {}} />);
    expect(screen.getByTestId('refusal')).toHaveTextContent('Not while printing.');
  });
});

describe('the console input', () => {
  it('sends the typed line, remembers it, and empties the box', async () => {
    const { ctx, sent } = ctxOf();
    const remembered: string[] = [];
    await render(<ConsoleInput history={[]} ctx={ctx} onRemember={(l) => remembered.push(l)} />);
    expect(screen.getByLabelText('Send').props.accessibilityState.disabled).toBe(true);
    await type('Send a line to the printer', '  M115 ');
    await press('Send');
    expect(sent).toEqual(['{"kind":"gcode","script":"M115"}']);
    expect(remembered).toEqual(['M115']);
    expect(screen.getByLabelText('Send a line to the printer').props.value).toBe('');
  });

  it('Enter sends too; Enter on an empty box or with Controls off sends nothing', async () => {
    const { ctx, sent } = ctxOf();
    await render(<ConsoleInput history={[]} ctx={ctx} onRemember={() => {}} />);
    await act(async () => fireEvent(screen.getByLabelText('Send a line to the printer'), 'submitEditing'));
    expect(sent).toEqual([]);
    await type('Send a line to the printer', 'G28');
    await act(async () => fireEvent(screen.getByLabelText('Send a line to the printer'), 'submitEditing'));
    expect(sent).toEqual(['{"kind":"gcode","script":"G28"}']);
    const off = ctxOf({ armed: false });
    await render(<ConsoleInput history={[]} ctx={off.ctx} onRemember={() => {}} />);
    await type('Send a line to the printer', 'G28');
    await act(async () => fireEvent(screen.getByLabelText('Send a line to the printer'), 'submitEditing'));
    expect(off.sent).toEqual([]);
    expect(screen.getByTestId('why-off')).toBeTruthy();
  });

  it('a line is at most 200 characters and never has a line break; the count is shown', async () => {
    const { ctx } = ctxOf();
    await render(<ConsoleInput history={[]} ctx={ctx} onRemember={() => {}} />);
    await type('Send a line to the printer', 'a'.repeat(300));
    expect(screen.getByLabelText('Send a line to the printer').props.value).toHaveLength(MAX_LINE);
    expect(screen.getByText(`${MAX_LINE}/${MAX_LINE}`)).toBeTruthy();
    await type('Send a line to the printer', 'M115\nM114');
    expect(screen.getByLabelText('Send a line to the printer').props.value).toBe('M115M114');
  });

  it('the arrows walk back through the lines sent, and typing leaves the history', async () => {
    const { ctx } = ctxOf();
    await render(<ConsoleInput history={['G28', 'M115', 'M114']} ctx={ctx} onRemember={() => {}} />);
    const box = () => screen.getByLabelText('Send a line to the printer').props.value;
    expect(screen.getByLabelText('Newer line').props.accessibilityState.disabled).toBe(true);
    await press('Older line');
    expect(box()).toBe('G28');
    await press('Older line');
    await press('Older line');
    await press('Older line');
    expect(box()).toBe('M114');
    await press('Newer line');
    expect(box()).toBe('M115');
    await press('Newer line');
    await press('Newer line');
    expect(box()).toBe('');
    await press('Older line');
    await type('Send a line to the printer', 'G28 X');
    expect(screen.getByLabelText('Newer line').props.accessibilityState.disabled).toBe(true);
    await render(<ConsoleInput history={[]} ctx={ctx} onRemember={() => {}} />);
    expect(screen.getByLabelText('Older line').props.accessibilityState.disabled).toBe(true);
  });

  it('Jarvis\'s refusal of a line shows under the box', async () => {
    const { ctx } = ctxOf({ refused: { gcode: "Use the System card or the printer's screen for this." } });
    await render(<ConsoleInput history={[]} ctx={ctx} onRemember={() => {}} />);
    expect(screen.getByTestId('refusal')).toHaveTextContent(/Use the System card/);
  });
});

describe('the presets editor', () => {
  const presets = [{ name: 'PLA', extruder: 220, bed: 60 }];
  const editor = { saving: false, error: null as string | null, saved: 0 };
  const open = async (over: { editor?: typeof editor; ctx?: ControlsCtx; presets?: typeof presets } = {}) => {
    const saves: unknown[] = [];
    const closed: number[] = [];
    await render(<PresetsDialog presets={over.presets ?? presets} ctx={over.ctx ?? ctxOf().ctx} editor={over.editor ?? editor} onSave={(p) => saves.push(p)} onClose={() => closed.push(1)} />);
    return { saves, closed };
  };

  it('starts from what Jarvis offers and saves the edited list in whole degrees', async () => {
    const { saves } = await open();
    expect(screen.getByLabelText('Preset 1 name').props.value).toBe('PLA');
    await type('Preset 1 nozzle', '215.4');
    await press('Add a preset');
    await type('Preset 2 name', 'PETG');
    await type('Preset 2 nozzle', '240');
    await type('Preset 2 bed', '80');
    await press('Save presets');
    expect(saves).toEqual([[{ name: 'PLA', extruder: 215, bed: 60 }, { name: 'PETG', extruder: 240, bed: 80 }]]);
  });

  it('says in words why it cannot be saved, and keeps Save off', async () => {
    await open();
    await press('Add a preset');
    expect(screen.getByTestId('presets-problem')).toHaveTextContent('Every preset needs a name.');
    expect(screen.getByLabelText('Save presets').props.accessibilityState.disabled).toBe(true);
    await type('Preset 2 name', 'pla');
    await type('Preset 2 nozzle', '200');
    await type('Preset 2 bed', '50');
    expect(screen.getByTestId('presets-problem')).toHaveTextContent('There are two presets called pla.');
  });

  it('removes a preset; none is allowed (Jarvis then uses its own); at most 10', async () => {
    const { saves } = await open();
    await press('Remove PLA');
    expect(screen.getByText('With none, Jarvis uses its own presets.')).toBeTruthy();
    await press('Save presets');
    expect(saves).toEqual([[]]);
    for (let i = 0; i < 10; i++) await press('Add a preset');
    expect(screen.getByLabelText('Add a preset').props.accessibilityState.disabled).toBe(true);
    await press('Add a preset');
    expect(screen.queryByLabelText('Preset 11 name')).toBeNull();
    expect(screen.getByLabelText('Preset 10 name')).toBeTruthy();
  });

  it('shows Jarvis\'s refusal, keeps Save off while saving, and closes when a save worked', async () => {
    await open({ editor: { saving: false, error: 'Controls turned off. Turn them on to continue.', saved: 0 } });
    expect(screen.getByTestId('presets-problem')).toHaveTextContent(/Controls turned off/);
    await open({ editor: { saving: true, error: null, saved: 0 } });
    expect(screen.getByLabelText('Save presets').props.accessibilityState).toMatchObject({ disabled: true, busy: true });
    const { closed } = await open();
    expect(closed).toEqual([]);
    const off = ctxOf({ armed: false });
    await open({ ctx: off.ctx });
    expect(screen.getByLabelText('Save presets').props.accessibilityState.disabled).toBe(true);
    expect(screen.getByTestId('presets-problem')).toHaveTextContent(/Turn on Controls/);
  });

  it('is opened from the Temperatures card by Edit presets, and Cancel closes it', async () => {
    const { ctx } = ctxOf();
    await render(<TemperatureControls state={parsePrinterState((fixture('state-printing') as { state: unknown }).state)} presets={presets} ctx={ctx} editor={editor} onSavePresets={() => {}} onClearPresetsError={() => {}} />);
    expect(screen.queryByTestId('presets-dialog')).toBeNull();
    await press('Edit presets');
    expect(screen.getByTestId('presets-dialog')).toBeTruthy();
    await press('Cancel');
    expect(screen.queryByTestId('presets-dialog')).toBeNull();
  });
});
