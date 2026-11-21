import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { serializeCommand, type PrinterCommand } from '../printer/feed/commands';
import { initialControl, type ControlUi } from '../printer/feed/control';
import { COLLAPSED_AT_FIRST } from '../printer/feed/prefs';
import type { PrinterUi } from '../printer/feed/page';
import type { PrinterScreenState } from '../printer/feed/session';
import { fixture } from '../printer/feed/testkit';
import { parsePrinterState, parseStateAnswer, type PrinterState } from '../printer/feed/wire';
import { CameraControls, FanControls, FileControls, FirmwareRestartButton, LimitControls, ObjectControls, QueueControls, SensorControls, SystemControls, TemperatureControls, ToolheadControls, type ControlsCtx } from './printer/CardControls';
import { themes } from './theme';

const theme = themes.light;
const NOW = 1_763_400_000_000;
const base = (): PrinterState => parsePrinterState((fixture('state-printing') as { state: unknown }).state);
const live = (over: Record<string, unknown> = {}): PrinterScreenState => ({ kind: 'live', data: parseStateAnswer({ ...fixture('state-printing'), ...over }), receivedAt: NOW, offsetMs: 0, stale: false });
const pageUi = (screen: PrinterScreenState = live(), presets = [{ name: 'PLA', extruder: 220, bed: 60 }]): PrinterUi => ({ screen, name: 'P', collapsed: COLLAPSED_AT_FIRST, dismissedAlert: null, now: NOW, presets });

function ctxOf(over: Partial<ControlUi> = {}, screen?: PrinterScreenState) {
  const sent: string[] = [];
  const ctx: ControlsCtx = { ui: pageUi(screen), controls: { ...initialControl(), mayControl: true, armed: true, ...over }, send: (c: PrinterCommand) => sent.push(serializeCommand(c)), theme };
  return { ctx, sent };
}
const press = (label: string) => act(async () => fireEvent.press(screen.getByLabelText(label)));
const type = (label: string, text: string) => act(async () => fireEvent.changeText(screen.getByLabelText(label), text));
const slide = (label: string, v: number) => act(async () => fireEvent(screen.getByLabelText(label), 'slidingComplete', v));

describe('Temperatures', () => {
  it('Set sends the typed target for that heater only when the box is right; Off and the presets and All off send theirs', async () => {
    const { ctx, sent } = ctxOf();
    const st = base();
    await render(<TemperatureControls state={st} presets={[{ name: 'PLA', extruder: 220, bed: 60 }]} ctx={ctx} />);
    expect(screen.getByLabelText('Set Nozzle').props.accessibilityState.disabled).toBe(true); // empty box
    await type('Nozzle °C', '999');
    expect(screen.getByLabelText('Set Nozzle').props.accessibilityState.disabled).toBe(true); // over its maximum
    await type('Nozzle °C', '215');
    await press('Set Nozzle');
    await type('Bed °C', '60,5');
    await press('Set Bed');
    await press('Nozzle off');
    await press('PLA');
    await press('All off');
    expect(sent).toEqual([
      '{"kind":"setTemp","heater":"extruder","target":215}',
      '{"kind":"setTemp","heater":"heater_bed","target":60.5}',
      '{"kind":"setTemp","heater":"extruder","target":0}',
      '{"kind":"preset","name":"PLA"}',
      '{"kind":"heatersOff"}',
    ]);
  });

  it('with Controls off nothing can be pressed and it says why; a refusal shows under its heater', async () => {
    const off = ctxOf({ armed: false });
    await render(<TemperatureControls state={base()} presets={[]} ctx={off.ctx} />);
    expect(screen.getByLabelText('Nozzle off').props.accessibilityState.disabled).toBe(true);
    expect(screen.getByTestId('why-off')).toHaveTextContent('Turn on Controls to use this');
    const refused = ctxOf({ refused: { 'setTemp:extruder': 'Too hot for this printer.' } });
    await render(<TemperatureControls state={base()} presets={[]} ctx={refused.ctx} />);
    expect(screen.getByTestId('refusal')).toHaveTextContent('Too hot for this printer.');
  });

  it('a sent button shows busy and cannot be pressed again', async () => {
    const { ctx } = ctxOf({ pending: new Set(['preset:PLA']) });
    await render(<TemperatureControls state={base()} presets={[{ name: 'PLA', extruder: 220, bed: 60 }]} ctx={ctx} />);
    expect(screen.getByLabelText('PLA').props.accessibilityState).toMatchObject({ disabled: true, busy: true });
  });
});

describe('Toolhead', () => {
  it('home, jog by the chosen step, Z offset, extrude, retract and motors off send the literal commands', async () => {
    const { ctx, sent } = ctxOf();
    await render(<ToolheadControls state={base()} ctx={ctx} />);
    await press('Home all');
    await press('Home XY');
    await press('Home Z');
    await press('X+');
    await press('Y−');
    await press('Step 10 millimetres');
    await press('X−');
    await press('Z+');
    await press('Step 100 millimetres');
    await press('Y+');
    await press('Z−'); // 100 is not offered for Z: it moves 50
    await press('+0.05');
    await press('−0.01');
    await press('Extrude');
    await press('Retract');
    await press('Motors off');
    expect(sent).toEqual([
      '{"kind":"home","axes":"xyz"}',
      '{"kind":"home","axes":"xy"}',
      '{"kind":"home","axes":"z"}',
      '{"kind":"jog","axis":"x","distance":1}',
      '{"kind":"jog","axis":"y","distance":-1}',
      '{"kind":"jog","axis":"x","distance":-10}',
      '{"kind":"jog","axis":"z","distance":10}',
      '{"kind":"jog","axis":"y","distance":100}',
      '{"kind":"jog","axis":"z","distance":-50}',
      '{"kind":"zOffset","delta":0.05}',
      '{"kind":"zOffset","delta":-0.01}',
      '{"kind":"extrude","length":10,"speed":5}',
      '{"kind":"extrude","length":-10,"speed":5}',
      '{"kind":"motorsOff"}',
    ]);
  });

  it('an empty or wrong filament box disables Extrude and Retract; the server\'s refusal of a jog shows under the cross', async () => {
    const { ctx } = ctxOf({ refused: { jog: 'Home the printer first.' } });
    await render(<ToolheadControls state={base()} ctx={ctx} />);
    await type('Length mm', '');
    expect(screen.getByLabelText('Extrude').props.accessibilityState.disabled).toBe(true);
    await type('Length mm', '10');
    await type('Speed mm/s', '80');
    expect(screen.getByLabelText('Retract').props.accessibilityState.disabled).toBe(true);
    expect(screen.getByTestId('refusal')).toHaveTextContent('Home the printer first.');
  });

  it('the server\'s own reason is shown for a home it will not do', async () => {
    const { ctx } = ctxOf({}, live({ quick: { 'home:xyz': { allowed: false, reason: 'Not while printing.' } } }));
    await render(<ToolheadControls state={base()} ctx={ctx} />);
    expect(screen.getByLabelText('Home all').props.accessibilityState.disabled).toBe(true);
    expect(screen.getByLabelText('Home XY').props.accessibilityState.disabled).toBe(false);
    const xy = ctxOf({}, live({ quick: { 'home:xy': { allowed: false, reason: 'Not now.' } } }));
    await render(<ToolheadControls state={base()} ctx={xy.ctx} />);
    expect(screen.getByLabelText('Home XY').props.accessibilityState.disabled).toBe(true);
    expect(screen.getByLabelText('Home all').props.accessibilityState.disabled).toBe(false);
    expect(screen.getByLabelText('Home Z').props.accessibilityState.disabled).toBe(false);
  });
});

describe('Fans, light and air', () => {
  it('sliders send only when released, as whole levels; the light buttons and the air modes send theirs', async () => {
    const { ctx, sent } = ctxOf();
    const st = base();
    await render(<FanControls state={st} ctx={ctx} />);
    await act(async () => fireEvent(screen.getByLabelText('Part cooling speed'), 'valueChange', 30));
    expect(sent).toEqual([]); // dragging sends nothing
    await slide('Part cooling speed', 40.4);
    await slide('Light level', 0);
    await press('Light on');
    await press('Light off');
    await press('Internal');
    await press('External');
    await press('Stop air');
    expect(sent).toEqual([
      `{"kind":"fan","fan":"${st.fans[0].id}","speed":0.4}`,
      '{"kind":"light","level":0}',
      '{"kind":"light","level":1}',
      '{"kind":"light","level":0}',
      '{"kind":"airCirculation","mode":"internal"}',
      '{"kind":"airCirculation","mode":"external"}',
      '{"kind":"airCirculation","mode":"stop"}',
    ]);
  });

  it('a fan the printer runs by itself has no slider; no light means no light controls; Controls off disables the sliders', async () => {
    const { ctx } = ctxOf();
    const st = base();
    await render(<FanControls state={{ ...st, fans: [{ ...st.fans[0], settable: false }], light: null }} ctx={ctx} />);
    expect(screen.queryByLabelText('Part cooling speed')).toBeNull();
    expect(screen.queryByLabelText('Light level')).toBeNull();
    const off = ctxOf({ armed: false });
    await render(<FanControls state={st} ctx={off.ctx} />);
    expect(screen.getByLabelText('Light level').props.disabled).toBe(true);
  });
});

describe('Limits and retraction', () => {
  it('speed and flow send on release; Apply sends only the boxes that hold a number', async () => {
    const { ctx, sent } = ctxOf();
    const st = { ...base(), retraction: { length: 0.8, speed: 35, extraLength: 0, unretractSpeed: 30 } };
    await render(<LimitControls state={st} ctx={ctx} />);
    await slide('Speed factor', 120.2);
    await slide('Flow factor', 95);
    expect(screen.getByLabelText('Apply limits').props.accessibilityState.disabled).toBe(true);
    await type('Velocity mm/s', '250');
    await type('Cruise ratio', '0.5');
    await press('Apply limits');
    await type('Length mm', '0.6');
    await press('Apply retraction');
    expect(sent).toEqual([
      '{"kind":"speedFactor","percent":120}',
      '{"kind":"flowFactor","percent":95}',
      '{"kind":"limits","velocity":250,"cruiseRatio":0.5}',
      '{"kind":"retraction","length":0.6}',
    ]);
  });

  it('a wrong number disables Apply; no retraction means no retraction boxes', async () => {
    const { ctx } = ctxOf();
    await render(<LimitControls state={{ ...base(), retraction: null }} ctx={ctx} />);
    expect(screen.queryByLabelText('Apply retraction')).toBeNull();
    await type('Accel mm/s²', '-5');
    expect(screen.getByLabelText('Apply limits').props.accessibilityState.disabled).toBe(true);
  });
});

describe('Sensors, objects, queue, system, camera, files', () => {
  it('the filament sensor switch sends its new state', async () => {
    const { ctx, sent } = ctxOf();
    const st = { ...base(), filamentSensors: [{ id: 'filament_switch_sensor fila', label: 'Runout', detected: true, enabled: true }] };
    await render(<SensorControls state={st} ctx={ctx} />);
    await act(async () => fireEvent(screen.getByLabelText('Runout enabled'), 'valueChange', false));
    expect(sent).toEqual(['{"kind":"filamentSensor","sensor":"filament_switch_sensor fila","enabled":false}']);
    await render(<SensorControls state={{ ...st, filamentSensors: [] }} ctx={ctx} />);
    expect(screen.queryByTestId('sensor-controls')).toBeNull();
    await render(<SensorControls state={st} ctx={ctxOf({ armed: false }).ctx} />);
    expect(screen.getByLabelText('Runout enabled').props.disabled).toBe(true);
    expect(screen.getByTestId('why-off')).toBeTruthy();
  });

  it('exclude is offered for objects still printing and sends the object\'s name', async () => {
    const { ctx, sent } = ctxOf();
    const st = base();
    await render(<ObjectControls state={{ ...st, objects: [...st.objects, { name: 'Gone', center: null, polygon: null, excluded: true, current: false }] }} ctx={ctx} />);
    expect(screen.queryByLabelText('Exclude Gone')).toBeNull();
    const red = (label: string) => JSON.stringify(screen.getByLabelText(label).props.style).includes(theme.error);
    expect(red('Exclude Chimney')).toBe(true); // dangerous things look it
    await press('Exclude Chimney');
    expect(sent).toEqual(['{"kind":"excludeObject","name":"Chimney"}']);
  });

  it('the queue: remove a job, start, pause', async () => {
    const { ctx, sent } = ctxOf();
    const st = { ...base(), queue: { state: 'ready', jobs: [{ id: '7', filename: 'a.gcode', title: '', added: 1 }] } };
    await render(<QueueControls state={st} ctx={ctx} />);
    await press('Remove a.gcode');
    await press('Start queue');
    await press('Pause queue');
    expect(sent).toEqual(['{"kind":"queueRemove","jobId":"7"}', '{"kind":"queueStart"}', '{"kind":"queuePause"}']);
    await render(<QueueControls state={{ ...st, queue: null }} ctx={ctx} />);
    expect(screen.queryByTestId('queue-controls')).toBeNull();
  });

  it('system, camera and the firmware restart button', async () => {
    const { ctx, sent } = ctxOf();
    await render(<SystemControls ctx={ctx} />);
    await press('Restart Klipper');
    await press('Firmware restart');
    await render(<CameraControls ctx={ctx} />);
    await press('Turn camera on');
    await render(<FirmwareRestartButton ctx={ctx} />);
    await press('Firmware restart');
    expect(sent).toEqual(['{"kind":"restart"}', '{"kind":"firmwareRestart"}', '{"kind":"camera","on":true}', '{"kind":"firmwareRestart"}']);
  });

  it('a file\'s Print, Add to queue and Delete send their own commands, and Jarvis\'s refusal shows under them', async () => {
    const { ctx, sent } = ctxOf({ refused: { start: 'A .3mf file cannot be printed from here.' } });
    await render(<FileControls path="a/b.gcode" ctx={ctx} />);
    await press('Print');
    await press('Add to queue');
    await press('Delete');
    expect(sent).toEqual(['{"kind":"start","path":"a/b.gcode"}', '{"kind":"queueAdd","path":"a/b.gcode"}', '{"kind":"deleteFile","path":"a/b.gcode"}']);
    expect(screen.getByTestId('refusal')).toHaveTextContent('A .3mf file cannot be printed from here.');
  });

  it('while Jarvis is away every control is disabled, with the reason', async () => {
    const { ctx } = ctxOf({}, { kind: 'away', last: parseStateAnswer(fixture('state-printing')), why: 'x' });
    await render(<SystemControls ctx={ctx} />);
    expect(screen.getByLabelText('Restart Klipper').props.accessibilityState.disabled).toBe(true);
    expect(screen.getByTestId('why-off')).toHaveTextContent("Jarvis isn't reachable right now.");
  });
});
