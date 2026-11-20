import { age, countdown, duration, eta, factor, filament, percent, position, target, temperature, timeLeft, timeLeftSource } from './format';

describe('the Printer page formatting (the same cases as Android\'s PrinterFormatTest)', () => {
  it('durations round minutes down and never show 0m for an hour or more', () => {
    expect(duration(3700)).toBe('1h 1m');
    expect(duration(3600)).toBe('1h');
    expect(duration(7259)).toBe('2h');
    expect(duration(3120)).toBe('52m');
    expect(duration(60)).toBe('1m');
    expect(duration(59.9)).toBe('59s');
    expect(duration(0)).toBe('0s');
  });

  it('an unknown or negative duration is a dash', () => {
    expect(duration(null)).toBe('—');
    expect(duration(undefined)).toBe('—');
    expect(duration(-1)).toBe('—');
    expect(duration(NaN)).toBe('—');
  });

  it('time left always says about, and unknown is a dash', () => {
    expect(timeLeft(4440)).toBe('about 1h 14m');
    expect(timeLeft(45)).toBe('about 45s');
    expect(timeLeft(null)).toBe('—');
  });

  it('the note under the time left says where the estimate came from', () => {
    expect(timeLeftSource('slicer')).toBe('Estimated from the slicer');
    expect(timeLeftSource('progress')).toBe('Estimated from the time printed and the progress');
    expect(timeLeftSource(null)).toBeNull();
    expect(timeLeftSource('guess')).toBeNull();
  });

  it("the finish time is in the phone's zone, 24 hours or 12", () => {
    const now = new Date(2026, 10, 18, 13, 0, 0).getTime(); // 13:00 local, whatever the zone
    expect(eta(now, 5520, true)).toBe('about 14:32');
    expect(eta(now, 5520, false)).toBe('about 2:32 PM');
    expect(eta(new Date(2026, 10, 18, 0, 5, 0).getTime(), 60, false)).toBe('about 12:06 AM');
    expect(eta(new Date(2026, 10, 18, 9, 5, 0).getTime(), 3600, true)).toBe('about 10:05');
    expect(eta(now, null, true)).toBe('—');
  });

  it('temperatures have one decimal, targets are whole, positions two', () => {
    expect(temperature(219.8)).toBe('219.8 °C');
    expect(temperature(23.96)).toBe('24.0 °C');
    expect(target(219.6)).toBe('220 °C');
    expect(target(0)).toBe('off');
    expect(position(100.5)).toBe('100.50');
  });

  it('filament is metres with one decimal and percentages are whole with a space', () => {
    expect(filament(12400)).toBe('12.4 m');
    expect(filament(0)).toBe('0.0 m');
    expect(percent(0.4)).toBe('40 %');
    expect(factor(0.85)).toBe('85 %');
  });

  it('ages are relative under a week and a date after', () => {
    const now = new Date(2026, 10, 18, 13, 0, 0).getTime();
    expect(age(now - 20_000, now)).toBe('just now');
    expect(age(now - 125_000, now)).toBe('2 min ago');
    expect(age(now - 3 * 3_600_000 - 5_000, now)).toBe('3 h ago');
    expect(age(new Date(2026, 10, 17, 20, 0, 0).getTime(), now)).toBe('yesterday');
    expect(age(new Date(2026, 10, 14, 10, 0, 0).getTime(), now)).toBe('4 days ago');
    expect(age(new Date(2026, 10, 3, 10, 0, 0).getTime(), now)).toBe('Nov 3');
    expect(age(now + 5_000, now)).toBe('just now');
    // Just after midnight: two hours ago was yesterday, not "2 h ago".
    const late = new Date(2026, 10, 18, 1, 0, 0).getTime();
    expect(age(new Date(2026, 10, 17, 23, 0, 0).getTime(), late)).toBe('yesterday');
  });

  it("the Controls countdown uses the server's clock, not the phone's", () => {
    // The phone's clock is 5 minutes slow: serverTime - phone = +300 000 ms.
    expect(countdown(1_203_000, 800_000, 300_000)).toBe('1:43');
    expect(countdown(1_000, 5_000, 0)).toBe('0:00');
    expect(countdown(1_120_000, 1_000_000, 0)).toBe('2:00');
  });
});
