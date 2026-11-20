import { Store } from '../core/store';
import { memoryStore } from '../settings/settings';
import { AlertLoop, AWAY_MS } from './loop';
import { AlertChoices, type Activity, type Alert } from './model';
import { alertContent, alertTitle, summaryText, clockText, type Notifier } from './notifications';
import { AlertPrefs } from './prefs';
import { AlertWatcher, CATCH_UP_WINDOW_MS, INTERVAL_MS } from './watcher';

const NOW = new Date('2026-11-20T15:30:00').getTime();

function harness(over: { picture?: string | null } = {}) {
  const t = { ms: NOW };
  const timers: { fn: () => void; due: number; id: number }[] = [];
  let nextId = 1;
  const choices = new Store<AlertChoices>(new AlertChoices());
  const answers = new Map<string, Activity[] | Error>();
  const asked: [string, number][] = [];
  const shown: [Alert, string | undefined][] = [];
  const notifier: Notifier = {
    permission: async () => 'granted',
    request: async () => true,
    show: async (a, p) => void shown.push([a, p]),
    onOpen: () => () => {},
  };
  const watcher = new AlertWatcher(
    {
      activity: async (b, since) => {
        asked.push([b, since]);
        const a = answers.get(b) ?? [];
        if (a instanceof Error) throw a;
        return a;
      },
    },
    () => t.ms,
  );
  const loop = new AlertLoop({
    watcher,
    choices,
    notifier,
    photo: async () => (over.picture === undefined ? 'JPEG64' : over.picture),
    now: () => t.ms,
    setTimer: (fn, ms) => {
      const id = nextId++;
      timers.push({ fn, due: t.ms + ms, id });
      return id;
    },
    clearTimer: (id) => {
      const i = timers.findIndex((x) => x.id === id);
      if (i >= 0) timers.splice(i, 1);
    },
  });
  loop.start();
  const flush = async () => {
    for (let i = 0; i < 30; i++) await Promise.resolve();
  };
  const advance = async (ms: number) => {
    const end = t.ms + ms;
    for (;;) {
      const next = timers.filter((x) => x.due <= end).sort((a, b) => a.due - b.due)[0];
      if (!next) break;
      timers.splice(timers.indexOf(next), 1);
      t.ms = next.due;
      next.fn();
      await flush();
    }
    t.ms = end;
    await flush();
  };
  const act = (key: string, kind: Activity['kind'] = 'ring', backend = 'ring', device = 'Front door', at = t.ms): Activity => ({ key, backend, device, at, kind });
  const on = (backend: string, kind: Parameters<AlertChoices['with']>[1]) => choices.set(choices.get().with(backend, kind, true));
  return { t, loop, choices, answers, asked, shown, advance, flush, act, on, timers };
}

describe('the foreground loop', () => {
  it('looks at once when the app is active, then every 45 seconds; a ring becomes a notification, then gets its picture', async () => {
    const h = harness();
    h.loop.setActive(true);
    h.on('ring', 'doorbell');
    await h.flush();
    expect(h.asked).toHaveLength(1); // the first look only learns
    h.answers.set('ring', [h.act('r1')]);
    await h.advance(INTERVAL_MS + 100);
    expect(h.asked).toHaveLength(2);
    expect(h.shown.map(([a, p]) => [a.key, a.kind, p])).toEqual([
      ['r1', 'doorbell', undefined],
      ['r1', 'doorbell', 'JPEG64'], // the picture replaces the alert a moment later
    ]);
    await h.advance(INTERVAL_MS);
    expect(h.asked).toHaveLength(3);
    expect(h.shown).toHaveLength(2); // nothing announced twice
  });

  it('does nothing in the background', async () => {
    const h = harness();
    h.on('ring', 'doorbell');
    h.loop.setActive(true);
    await h.flush();
    h.loop.setActive(false);
    expect(h.timers).toEqual([]); // no timer is left waiting for the app to come back
    const before = h.asked.length;
    await h.advance(10 * INTERVAL_MS);
    expect(h.asked).toHaveLength(before);
    expect(h.timers).toEqual([]);
  });

  it('looks at once on a short return, with no summary', async () => {
    const h = harness();
    h.on('ring', 'doorbell');
    h.loop.setActive(true);
    await h.flush();
    h.loop.setActive(false);
    const before = h.asked.length;
    h.t.ms += 50_000; // away under two minutes, and the service is due again
    h.loop.setActive(true);
    await h.flush();
    expect(h.asked).toHaveLength(before + 1);
    expect(h.asked.at(-1)![1]).toBe(h.t.ms - 10 * 60_000);
    expect(h.loop.catchUp.get()).toBeNull();
  });

  it('after a long absence it does one catch-up look and shows a summary, not a burst of notifications', async () => {
    const h = harness();
    h.on('ring', 'doorbell');
    h.on('ring', 'person');
    h.loop.setActive(true);
    await h.flush();
    h.loop.setActive(false);
    const left = h.t.ms;
    h.t.ms += 20 * 60_000;
    h.answers.set('ring', [h.act('a', 'ring', 'ring', 'Front door', h.t.ms - 600_000), h.act('b', 'ring', 'ring', 'Front door', h.t.ms - 300_000), h.act('c', 'person', 'ring', 'Backyard', h.t.ms - 100_000)]);
    h.loop.setActive(true);
    await h.flush();
    expect(h.shown).toEqual([]);
    expect(h.asked.at(-2)![1]).toBe(h.t.ms - CATCH_UP_WINDOW_MS); // looked a day back (the look after it asks 10 minutes back)
    const summary = h.loop.catchUp.get()!;
    expect(summary.text).toBe(`Since ${clockText(left)}: Backyard saw a person, Front door rang (2)`);
    expect(summary.alerts).toHaveLength(3);
    h.loop.dismissCatchUp();
    expect(h.loop.catchUp.get()).toBeNull();
    expect(AWAY_MS).toBe(120_000);
  });

  it('says nothing when nothing happened while the owner was away', async () => {
    const h = harness();
    h.on('ring', 'doorbell');
    h.loop.setActive(true);
    await h.flush();
    h.loop.setActive(false);
    h.t.ms += 30 * 60_000;
    h.loop.setActive(true);
    await h.flush();
    expect(h.loop.catchUp.get()).toBeNull();
  });

  it('with every alert off it never looks, and switching the first one on starts looking (learning)', async () => {
    const h = harness();
    h.loop.setActive(true);
    await h.advance(5 * INTERVAL_MS);
    expect(h.asked).toEqual([]);
    h.on('ring', 'doorbell');
    await h.flush();
    expect(h.asked).toHaveLength(1);
    h.choices.set(new AlertChoices());
    await h.advance(5 * INTERVAL_MS);
    expect(h.asked).toHaveLength(1);
    expect(h.timers).toEqual([]);
  });

  it('a look that fails backs off and carries on; a failing picture leaves the alert as it was', async () => {
    const h = harness({ picture: null });
    h.on('ring', 'doorbell');
    h.loop.setActive(true);
    await h.flush();
    h.answers.set('ring', new Error('Ring is down'));
    await h.advance(INTERVAL_MS + 100);
    expect(h.asked).toHaveLength(2);
    await h.advance(60_000); // 90 s back-off: not asked yet
    expect(h.asked).toHaveLength(2);
    h.answers.set('ring', [h.act('r2')]);
    await h.advance(60_000);
    expect(h.asked).toHaveLength(3);
    expect(h.shown).toHaveLength(1); // no picture: only the first showing
  });

  it('being told twice that the app is active changes nothing', async () => {
    const h = harness();
    h.on('ring', 'doorbell');
    h.loop.setActive(true);
    h.loop.setActive(true);
    await h.flush();
    expect(h.asked).toHaveLength(1);
  });
});

describe('the words', () => {
  const alert = (kind: Alert['kind']): Alert => ({ backend: 'ring', device: 'Front door', kind, at: NOW, key: `ring:1:${kind}` });
  it('has titles, a category per kind, and a tap that opens the clips', () => {
    expect(['doorbell', 'person', 'motion'].map((k) => alertTitle(k as Alert['kind']))).toEqual(['Someone rang the doorbell', 'A person was seen', 'Motion']);
    const c = alertContent(alert('doorbell'));
    expect(c).toEqual({
      identifier: 'ring:1:doorbell',
      title: 'Someone rang the doorbell',
      body: 'Front door',
      categoryIdentifier: 'alerts_doorbell',
      interruptionLevel: 'timeSensitive',
      data: { open: 'clips', backend: 'ring', key: 'ring:1:doorbell' },
    });
    expect(alertContent(alert('motion')).interruptionLevel).toBe('active');
    expect(alertContent(alert('person')).categoryIdentifier).toBe('alerts_person');
  });

  it('summarises with counts, newest first, and says nothing for nothing', () => {
    const a = (device: string, kind: Alert['kind'], min: number): Alert => ({ backend: 'ring', device, kind, at: NOW - min * 60_000, key: `${device}${min}` });
    expect(summaryText([a('Front door', 'doorbell', 30), a('Front door', 'doorbell', 10), a('Backyard', 'person', 20), a('Garage', 'motion', 40)], NOW - 3_600_000)).toBe(
      `Since ${clockText(NOW - 3_600_000)}: Front door rang (2), Backyard saw a person, Garage saw motion`,
    );
    expect(summaryText([], NOW)).toBeNull();
    expect(clockText(new Date('2026-11-20T00:05:00').getTime())).toBe('12:05 AM');
    expect(clockText(new Date('2026-11-20T15:42:00').getTime())).toBe('3:42 PM');
  });
});

describe('the switches are remembered', () => {
  it('keeps them across restarts, all off at first, and survives damaged data', async () => {
    const store = memoryStore();
    const prefs = new AlertPrefs(store);
    await prefs.load();
    expect(prefs.choices.get().any).toBe(false);
    await prefs.set('ring', 'person', true);
    await prefs.set('blink', 'motion', true);
    await prefs.set('blink', 'motion', false);
    const again = new AlertPrefs(store);
    await again.load();
    expect([...again.choices.get().on]).toEqual(['ring:person']);
    const bad = new AlertPrefs(memoryStore({ alerts_on: '{nope' }));
    await bad.load();
    expect(bad.choices.get().any).toBe(false);
    const junk = new AlertPrefs(memoryStore({ alerts_on: '["ring:doorbell", 5, null]' }));
    await junk.load();
    expect([...junk.choices.get().on]).toEqual(['ring:doorbell']);
  });
});
