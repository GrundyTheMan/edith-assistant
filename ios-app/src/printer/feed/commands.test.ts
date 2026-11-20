import { COMMAND_KINDS, AirMode, Axis, HomeAxes, commandObject, serializeCommand, type PrinterCommand } from './commands';

/** Every kind Jarvis's `PrinterCommand` has, with the exact JSON Edith sends for one example (the same table as Android's PrinterCommandTest; JavaScript writes 220.0 as 220). */
const table: [PrinterCommand, string][] = [
  [{ kind: 'pause' }, '{"kind":"pause"}'],
  [{ kind: 'resume' }, '{"kind":"resume"}'],
  [{ kind: 'cancel' }, '{"kind":"cancel"}'],
  [{ kind: 'start', path: 'benchy.gcode' }, '{"kind":"start","path":"benchy.gcode"}'],
  [{ kind: 'estop' }, '{"kind":"estop"}'],
  [{ kind: 'restart' }, '{"kind":"restart"}'],
  [{ kind: 'firmwareRestart' }, '{"kind":"firmwareRestart"}'],
  [{ kind: 'setTemp', heater: 'extruder', target: 220 }, '{"kind":"setTemp","heater":"extruder","target":220}'],
  [{ kind: 'preset', name: 'PLA' }, '{"kind":"preset","name":"PLA"}'],
  [{ kind: 'heatersOff' }, '{"kind":"heatersOff"}'],
  [{ kind: 'home', axes: HomeAxes.xy }, '{"kind":"home","axes":"xy"}'],
  [{ kind: 'jog', axis: Axis.x, distance: 10 }, '{"kind":"jog","axis":"x","distance":10}'],
  [{ kind: 'zOffset', delta: -0.05 }, '{"kind":"zOffset","delta":-0.05}'],
  [{ kind: 'extrude', length: 10, speed: 5 }, '{"kind":"extrude","length":10,"speed":5}'],
  [{ kind: 'motorsOff' }, '{"kind":"motorsOff"}'],
  [{ kind: 'speedFactor', percent: 85 }, '{"kind":"speedFactor","percent":85}'],
  [{ kind: 'flowFactor', percent: 100 }, '{"kind":"flowFactor","percent":100}'],
  [{ kind: 'limits', velocity: 200, scv: 5 }, '{"kind":"limits","velocity":200,"scv":5}'],
  [{ kind: 'retraction', length: 0.8 }, '{"kind":"retraction","length":0.8}'],
  [{ kind: 'fan', fan: 'fan_generic fanM106', speed: 0.5 }, '{"kind":"fan","fan":"fan_generic fanM106","speed":0.5}'],
  [{ kind: 'light', level: 1 }, '{"kind":"light","level":1}'],
  [{ kind: 'airCirculation', mode: AirMode.internal }, '{"kind":"airCirculation","mode":"internal"}'],
  [{ kind: 'filamentSensor', sensor: 'filament_switch_sensor head', enabled: false }, '{"kind":"filamentSensor","sensor":"filament_switch_sensor head","enabled":false}'],
  [{ kind: 'camera', on: true }, '{"kind":"camera","on":true}'],
  [{ kind: 'macro', name: 'LOAD_FILAMENT' }, '{"kind":"macro","name":"LOAD_FILAMENT"}'],
  [{ kind: 'macro', name: 'BED_MESH_CALIBRATE', params: { PROFILE: 'default' } }, '{"kind":"macro","name":"BED_MESH_CALIBRATE","params":{"PROFILE":"default"}}'],
  [{ kind: 'excludeObject', name: 'Chimney' }, '{"kind":"excludeObject","name":"Chimney"}'],
  [{ kind: 'gcode', script: 'M105' }, '{"kind":"gcode","script":"M105"}'],
  [{ kind: 'deleteFile', path: 'old.gcode' }, '{"kind":"deleteFile","path":"old.gcode"}'],
  [{ kind: 'screenTap', x: 400, y: 240 }, '{"kind":"screenTap","x":400,"y":240}'],
  [{ kind: 'queueAdd', path: 'benchy.gcode' }, '{"kind":"queueAdd","path":"benchy.gcode"}'],
  [{ kind: 'queueRemove', jobId: '000123' }, '{"kind":"queueRemove","jobId":"000123"}'],
  [{ kind: 'queueStart' }, '{"kind":"queueStart"}'],
  [{ kind: 'queuePause' }, '{"kind":"queuePause"}'],
];

describe('the commands', () => {
  it('writes every command as the exact JSON Jarvis expects', () => {
    for (const [command, json] of table) expect(serializeCommand(command)).toBe(json);
  });

  it('the table lists all 33 kinds and misses none', () => {
    expect(COMMAND_KINDS).toHaveLength(33);
    expect(new Set(table.map(([c]) => c.kind))).toEqual(new Set(COMMAND_KINDS));
  });

  it('leaves out what is not set: optional numbers, an empty params, a null', () => {
    expect(serializeCommand({ kind: 'limits' })).toBe('{"kind":"limits"}');
    expect(serializeCommand({ kind: 'macro', name: 'X', params: {} })).toBe('{"kind":"macro","name":"X"}');
    expect(serializeCommand({ kind: 'retraction', length: undefined, speed: 30 })).toBe('{"kind":"retraction","speed":30}');
    expect(commandObject({ kind: 'jog', axis: 'z', distance: -5 })).toEqual({ kind: 'jog', axis: 'z', distance: -5 });
  });

  it('writes the kind first, and never a field Jarvis does not know (a stray property is dropped)', () => {
    const stray = { kind: 'pause', script: 'M112', confirmed: true } as unknown as PrinterCommand;
    expect(serializeCommand(stray)).toBe('{"kind":"pause"}');
    expect(Object.keys(commandObject({ kind: 'setTemp', heater: 'extruder', target: 200 }))[0]).toBe('kind');
  });

  it('keeps text exactly as given, including quotes and unicode', () => {
    const json = serializeCommand({ kind: 'gcode', script: 'M117 "Hi" é😀' });
    expect(JSON.parse(json)).toEqual({ kind: 'gcode', script: 'M117 "Hi" é😀' });
  });
});
