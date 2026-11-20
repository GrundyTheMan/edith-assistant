import { AlertChoices, alertKindOf, alertKindsOf, type Activity, type ActivitySource } from './model';
import { AlertWatcher, CATCH_UP_MAX, INTERVAL_MS, MAX_BACKOFF_MS, WINDOW_MS } from './watcher';

const clock = { ms: 1_000_000_000 };
class FakeSource implements ActivitySource {
  answers = new Map<string, Activity[] | Error>();
  asked: [string, number][] = [];
  async activity(backendId: string, sinceMs: number): Promise<Activity[]> {
    this.asked.push([backendId, sinceMs]);
    const a = this.answers.get(backendId) ?? [];
    if (a instanceof Error) throw a;
    return a;
  }
}
let source: FakeSource;
beforeEach(() => {
  clock.ms = 1_000_000_000;
  source = new FakeSource();
});
const watcher = (max = 3) => new AlertWatcher(source, () => clock.ms, { maxPerCheck: max });
const act = (key: string, kind: Activity['kind'] = 'motion', at = clock.ms, backend = 'blink', device = 'Porch'): Activity => ({ key, backend, device, at, kind });
const give = (backend: string, ...a: Activity[]) => void source.answers.set(backend, a);
const fail = new Error('timeout');
const blinkMotion = () => new AlertChoices(new Set([AlertChoices.key('blink', 'motion')]));

describe('AlertWatcher (the same rules as Android\'s AlertWatcherTest)', () => {
  it('the first look only learns what is already there', async () => {
    give('blink', act('old1', 'motion', clock.ms - 60_000));
    const w = watcher();
    expect((await w.check(blinkMotion())).alerts).toEqual([]);
    clock.ms += 45_000;
    expect((await w.check(blinkMotion())).alerts).toEqual([]); // the old clip isn't announced later either
  });

  it('a new event is announced once and not again', async () => {
    const w = watcher();
    await w.check(blinkMotion());
    clock.ms += 45_000;
    give('blink', act('c1', 'motion', clock.ms - 5_000));
    const first = (await w.check(blinkMotion())).alerts;
    expect(first.map((a) => a.key)).toEqual(['c1']);
    expect(first[0].kind).toBe('motion');
    clock.ms += 45_000;
    expect((await w.check(blinkMotion())).alerts).toEqual([]);
  });

  it('at most three alerts per look, the newest, shown oldest first', async () => {
    const w = watcher();
    await w.check(blinkMotion());
    clock.ms += 45_000;
    give('blink', ...[1, 2, 3, 4, 5].map((i) => act(`c${i}`, 'motion', clock.ms - (6 - i) * 1_000)));
    expect((await w.check(blinkMotion())).alerts.map((a) => a.key)).toEqual(['c3', 'c4', 'c5']);
    clock.ms += 45_000;
    expect((await w.check(blinkMotion())).alerts).toEqual([]); // the dropped ones are not queued for later
  });

  it('a kind that is off is not announced, and switching it on later does not announce the past', async () => {
    const ringPerson = new AlertChoices(new Set([AlertChoices.key('ring', 'person')]));
    const w = watcher();
    await w.check(ringPerson);
    clock.ms += 45_000;
    give('ring', act('d1', 'ring', clock.ms, 'ring'), act('p1', 'person', clock.ms, 'ring'));
    expect((await w.check(ringPerson)).alerts.map((a) => a.key)).toEqual(['p1']);
    clock.ms += 45_000;
    const both = new AlertChoices(new Set([AlertChoices.key('ring', 'person'), AlertChoices.key('ring', 'doorbell')]));
    expect((await w.check(both)).alerts).toEqual([]);
  });

  it('the owner\'s own live view is never an alert', async () => {
    const w = watcher();
    await w.check(blinkMotion());
    clock.ms += 45_000;
    give('blink', act('lv', 'live'));
    expect((await w.check(blinkMotion())).alerts).toEqual([]);
  });

  it('unknown activity counts as motion', async () => {
    const w = watcher();
    await w.check(blinkMotion());
    clock.ms += 45_000;
    give('blink', act('o1', 'other'));
    expect((await w.check(blinkMotion())).alerts.map((a) => a.kind)).toEqual(['motion']);
  });

  it('a failed look waits twice as long each time, up to five minutes, and a good one resets it', async () => {
    const w = watcher();
    source.answers.set('blink', fail);
    expect((await w.check(blinkMotion())).nextInMs).toBe(90_000);
    clock.ms += 90_000;
    expect((await w.check(blinkMotion())).nextInMs).toBe(180_000);
    clock.ms += 180_000;
    expect((await w.check(blinkMotion())).nextInMs).toBe(300_000);
    clock.ms += 300_000;
    expect((await w.check(blinkMotion())).nextInMs).toBe(300_000);
    clock.ms += 300_000;
    source.answers.set('blink', []);
    expect((await w.check(blinkMotion())).nextInMs).toBe(45_000);
    expect(MAX_BACKOFF_MS).toBe(300_000);
  });

  it('a service is not asked again before it is due', async () => {
    const w = watcher();
    await w.check(blinkMotion());
    clock.ms += 10_000;
    const r = await w.check(blinkMotion());
    expect(source.asked).toHaveLength(1);
    expect(r.nextInMs).toBe(35_000);
  });

  it('one service failing does not stop the other', async () => {
    const both = new AlertChoices(new Set([AlertChoices.key('blink', 'motion'), AlertChoices.key('ring', 'doorbell')]));
    const w = watcher();
    await w.check(both);
    clock.ms += 90_000;
    source.answers.set('blink', fail);
    give('ring', act('d1', 'ring', clock.ms, 'ring', 'Front door'));
    const alerts = (await w.check(both)).alerts;
    expect(alerts.map((a) => [a.device, a.kind])).toEqual([['Front door', 'doorbell']]);
  });

  it('switching a service off and on again learns afresh', async () => {
    const w = watcher();
    await w.check(blinkMotion());
    clock.ms += 45_000;
    await w.check(new AlertChoices());
    clock.ms += 45_000;
    give('blink', act('c9', 'motion', clock.ms - 20_000));
    expect((await w.check(blinkMotion())).alerts).toEqual([]); // the first look after switching on only learns
  });

  it('every look reaches back ten minutes', async () => {
    await watcher().check(blinkMotion());
    expect(source.asked).toEqual([['blink', clock.ms - WINDOW_MS]]);
  });

  it('with nothing switched on there is nothing to ask and a calm wait', async () => {
    const r = await watcher().check(new AlertChoices());
    expect(source.asked).toEqual([]);
    expect(r.nextInMs).toBe(INTERVAL_MS);
  });
});

describe('the catch-up look (C72 §3)', () => {
  it('reaches back a day and returns what was not announced, newest 20, oldest first', async () => {
    const w = watcher();
    give('blink', ...Array.from({ length: 25 }, (_, i) => act(`c${i}`, 'motion', clock.ms - (i + 1) * 60_000)));
    const alerts = await w.catchUp(blinkMotion());
    expect(source.asked[0][1]).toBe(clock.ms - 24 * 3_600_000);
    expect(alerts).toHaveLength(CATCH_UP_MAX);
    expect(alerts[0].at).toBeLessThan(alerts[alerts.length - 1].at);
    expect(alerts.at(-1)!.key).toBe('c0');
  });

  it('does not repeat what a look already announced, and a later look does not repeat the catch-up', async () => {
    const w = watcher();
    await w.check(blinkMotion());
    clock.ms += 45_000;
    give('blink', act('seen', 'motion', clock.ms - 1_000));
    expect((await w.check(blinkMotion())).alerts.map((a) => a.key)).toEqual(['seen']);
    clock.ms += 600_000;
    give('blink', act('seen', 'motion', clock.ms - 600_000), act('new', 'motion', clock.ms - 100_000));
    expect((await w.catchUp(blinkMotion())).map((a) => a.key)).toEqual(['new']);
    clock.ms += 45_000;
    expect((await w.check(blinkMotion())).alerts).toEqual([]);
  });

  it('keeps only the kinds that are on, skips a service that fails, and never lists the owner\'s live view', async () => {
    const both = new AlertChoices(new Set([AlertChoices.key('blink', 'motion'), AlertChoices.key('ring', 'doorbell')]));
    source.answers.set('ring', fail);
    give('blink', act('m', 'motion'), act('lv', 'live'));
    const alerts = await watcher().catchUp(both);
    expect(alerts.map((a) => a.key)).toEqual(['m']);
  });
});

describe('the kinds', () => {
  it('maps events to alert kinds and lists what each service can alert on', () => {
    expect(['ring', 'person', 'motion', 'other', 'live'].map((k) => alertKindOf(k as Activity['kind']))).toEqual(['doorbell', 'person', 'motion', 'motion', null]);
    expect(alertKindsOf('ring')).toEqual(['doorbell', 'person', 'motion']);
    expect(alertKindsOf('blink')).toEqual(['motion']);
    expect(alertKindsOf('demo')).toEqual([]);
    expect(alertKindsOf('demo', true)).toEqual(['doorbell']);
    expect(alertKindsOf('alexa')).toEqual([]);
  });

  it('keeps the choices as service:kind, everything off at first', () => {
    const c = new AlertChoices();
    expect(c.any).toBe(false);
    const on = c.with('ring', 'person', true).with('blink', 'motion', true);
    expect(on.enabled('ring', 'person')).toBe(true);
    expect(on.enabled('ring', 'doorbell')).toBe(false);
    expect(on.backends.sort()).toEqual(['blink', 'ring']);
    expect(on.with('ring', 'person', false).backends).toEqual(['blink']);
  });
});
