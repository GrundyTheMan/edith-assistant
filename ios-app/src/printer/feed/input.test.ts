import { between, consoleLine, extrude, level, limits, number, percent, presets, presetsProblem, retraction, setTemp, stepLabel, stepsFor, STEPS, Z_OFFSET_STEPS } from './input';

describe('what is typed into the boxes (as Android\'s PrinterInput)', () => {
  it('numbers as a person types them', () => {
    expect(number('1.5')).toBe(1.5);
    expect(number(' 1,5 ')).toBe(1.5);
    expect(number('-3')).toBe(-3);
    expect(number('.5')).toBe(0.5);
    expect(number('5.')).toBe(5);
    expect(number('1e2')).toBe(100);
    for (const bad of ['', '  ', 'abc', '0x10', 'NaN', 'Infinity', '1.2.3', '1 2', '--1', '12abc']) expect(number(bad)).toBeNull();
  });

  it('a range includes both ends', () => {
    expect(between('0', 0, 10)).toBe(0);
    expect(between('10', 0, 10)).toBe(10);
    expect(between('10.01', 0, 10)).toBeNull();
    expect(between('-0.1', 0, 10)).toBeNull();
    expect(between('x', 0, 10)).toBeNull();
  });

  it('a target is from 0 to what Klipper allows for the heater (300 when it did not say)', () => {
    expect(setTemp('extruder', '220', 300)).toEqual({ kind: 'setTemp', heater: 'extruder', target: 220 });
    expect(setTemp('extruder', '0', 300)).toEqual({ kind: 'setTemp', heater: 'extruder', target: 0 });
    expect(setTemp('extruder', '301', 300)).toBeNull();
    expect(setTemp('heater_bed', '121', 120)).toBeNull();
    expect(setTemp('heater_bed', '120', 120)).not.toBeNull();
    expect(setTemp('x', '300', 0)).not.toBeNull();
    expect(setTemp('x', '301', 0)).toBeNull();
    expect(setTemp('x', '', 300)).toBeNull();
    expect(setTemp('x', '-1', 300)).toBeNull();
  });

  it('extrude and retract: a length of 0.01 to 100 and a speed of 0.1 to 50; retract is a negative length', () => {
    expect(extrude('10', '5', false)).toEqual({ kind: 'extrude', length: 10, speed: 5 });
    expect(extrude('10', '5', true)).toEqual({ kind: 'extrude', length: -10, speed: 5 });
    expect(extrude('0.01', '0.1', false)).not.toBeNull();
    expect(extrude('100', '50', false)).not.toBeNull();
    for (const [l, s] of [['0', '5'], ['100.1', '5'], ['10', '0'], ['10', '50.1'], ['', '5'], ['10', '']] as const) expect(extrude(l, s, false)).toBeNull();
  });

  it('limits: only the boxes that hold a number are sent; nothing typed, or a number that cannot be right, is null', () => {
    expect(limits('300', '', '', '')).toEqual({ kind: 'limits', velocity: 300 });
    expect(limits('', '3000', '5', '0.5')).toEqual({ kind: 'limits', accel: 3000, scv: 5, cruiseRatio: 0.5 });
    expect(limits('300', '3000', '5', '0')).toEqual({ kind: 'limits', velocity: 300, accel: 3000, scv: 5, cruiseRatio: 0 });
    expect(limits('', '', '', '')).toBeNull();
    for (const bad of [['0', '', '', ''], ['-1', '', '', ''], ['', '0', '', ''], ['', '', '0', ''], ['', '', '', '1.1'], ['', '', '', '-0.1'], ['x', '', '', '']] as [string, string, string, string][]) expect(limits(bad[0], bad[1], bad[2], bad[3])).toBeNull();
    expect(limits('300', 'x', '', '')).toBeNull(); // one bad box stops all of it
    expect(limits('', '', '', '1')).toEqual({ kind: 'limits', cruiseRatio: 1 });
  });

  it('retraction: zero is fine for the lengths and not for the speeds; empty boxes are left alone', () => {
    expect(retraction('0.8', '', '', '')).toEqual({ kind: 'retraction', length: 0.8 });
    expect(retraction('0', '35', '0', '30')).toEqual({ kind: 'retraction', length: 0, speed: 35, extraLength: 0, unretractSpeed: 30 });
    expect(retraction('', '0', '', '')).toBeNull();
    expect(retraction('', '', '', '0')).toBeNull();
    expect(retraction('-1', '', '', '')).toBeNull();
    expect(retraction('', '', '-0.1', '')).toBeNull();
    expect(retraction('', '', '', '')).toBeNull();
    expect(retraction('1', 'x', '', '')).toBeNull();
  });

  it('a console line is one non-empty line of at most 200 characters, trimmed', () => {
    expect(consoleLine('  M115  ')).toEqual({ kind: 'gcode', script: 'M115' });
    expect(consoleLine('')).toBeNull();
    expect(consoleLine('   ')).toBeNull();
    expect(consoleLine('a'.repeat(200))).not.toBeNull();
    expect(consoleLine('a'.repeat(201))).toBeNull();
    expect(consoleLine('M115\nM114')).toBeNull();
    expect(consoleLine('M115\rM114')).toBeNull();
  });

  it('presets: Jarvis\'s own rules, in plain words, and whole degrees', () => {
    const row = (name: string, nozzle = '220', bed = '60') => ({ name, nozzle, bed });
    expect(presetsProblem([row('PLA'), row('PETG', '240', '80')])).toBeNull();
    expect(presets([row(' PLA ', '219.6', '60.4')])).toEqual([{ name: 'PLA', extruder: 220, bed: 60 }]);
    expect(presets([])).toEqual([]);
    expect(presetsProblem(Array.from({ length: 11 }, (_, i) => row(`P${i}`)))).toBe('At most 10 presets.');
    expect(presetsProblem(Array.from({ length: 10 }, (_, i) => row(`P${i}`)))).toBeNull();
    expect(presetsProblem([row('  ')])).toBe('Every preset needs a name.');
    expect(presetsProblem([row('a'.repeat(31))])).toBe("A preset's name is at most 30 characters.");
    expect(presetsProblem([row('a'.repeat(30))])).toBeNull();
    expect(presetsProblem([row('PLA'), row('pla')])).toBe('There are two presets called pla.');
    expect(presetsProblem([row('pla'), row('PLA')])).toBe('There are two presets called PLA.');
    expect(presetsProblem([row('PLA', '301')])).toBe('The nozzle temperature of PLA must be from 0 to 300 °C.');
    expect(presetsProblem([row('PLA', '300', '121')])).toBe('The bed temperature of PLA must be from 0 to 120 °C.');
    expect(presetsProblem([row('PLA', '', '60')])).not.toBeNull();
    expect(presets([row('PLA', '301')])).toBeNull();
  });

  it('slider values become whole percentages and 0-1 levels', () => {
    expect(percent(99.6)).toBe(100);
    expect(level(85.4)).toBe(0.85);
    expect(level(0)).toBe(0);
    expect(level(100)).toBe(1);
  });

  it('the jog steps; 100 mm is only for X and Y; the Z offset steps', () => {
    expect(STEPS).toEqual([0.1, 1, 10, 50, 100]);
    expect(stepsFor('x')).toEqual(STEPS);
    expect(stepsFor('z')).toEqual([0.1, 1, 10, 50]);
    expect(Z_OFFSET_STEPS).toEqual([0.01, 0.05, 0.1]);
    expect([0.1, 1, 10].map(stepLabel)).toEqual(['0.1', '1', '10']);
  });
});
