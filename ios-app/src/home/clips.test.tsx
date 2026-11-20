import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { Store } from '../core/store';
import { memoryStore } from '../settings/settings';
import { themes } from '../ui/theme';
import { ClipsScreen } from '../ui/ClipsScreen';
import { MAX_DAYS, WINDOW_MS, clipKindWords, clipLength, clipTime, emptyClips, loadOlder, noPlanText } from './clips';
import { Home } from './home';
import type { ClipsPage, DeviceClip, HomeBackend } from './model';
import type { HomeService, ServiceState } from './service';

const mockPlayer = { play: jest.fn(), pause: jest.fn(), addListener: jest.fn(() => ({ remove: jest.fn() })) };
const mockUseVideoPlayer = jest.fn((_source: unknown, setup?: (p: typeof mockPlayer) => void) => {
  setup?.(mockPlayer);
  return mockPlayer;
});
jest.mock('expo-video', () => ({ useVideoPlayer: (s: unknown, setup?: (p: unknown) => void) => mockUseVideoPlayer(s, setup as never), VideoView: () => null }));

const DAY = 86_400_000;
const NOW = Date.parse('2026-11-20T20:00:00');
const clip = (id: string, backend: string, hoursAgo: number, over: Partial<DeviceClip> = {}): DeviceClip => ({
  id,
  deviceId: `${backend}:1`,
  deviceName: 'Front door',
  backend,
  kind: 'motion',
  at: NOW - hoursAgo * 3_600_000,
  ...over,
});
const backend = (id: string, name: string, clips: (s: number, u: number) => Promise<ClipsPage>, extra: Partial<HomeBackend> = {}): HomeBackend => ({
  id,
  name,
  devices: async () => [],
  state: async () => ({}),
  command: async () => {},
  hasClips: true,
  clips,
  ...extra,
});
const inWindow = (all: DeviceClip[]) => async (s: number, u: number): Promise<ClipsPage> => ({ clips: all.filter((c) => c.at >= s && c.at <= u) });
const newHome = (backends: HomeBackend[], readTimeoutMs?: number) => {
  const h = new Home({ store: memoryStore() as never, now: () => NOW, setTimer: () => 0, readTimeoutMs });
  h.setBackends(backends);
  return h;
};

describe('Home.clips', () => {
  it('merges two services newest first, tagged with the service', async () => {
    const home = newHome([backend('ring', 'Ring', inWindow([clip('r1', 'ring', 5), clip('r2', 'ring', 1)])), backend('blink', 'Blink', inWindow([clip('b1', 'blink', 3)]))]);
    const r = await home.clips(NOW - DAY, NOW);
    expect(r.clips.map((c) => [c.id, c.backend])).toEqual([['r2', 'ring'], ['b1', 'blink'], ['r1', 'ring']]);
    expect(r.problems).toEqual([]);
  });

  it('a failing or hanging service becomes a problem line and hides nothing', async () => {
    const home = newHome(
      [
        backend('ring', 'Ring', async () => {
          throw new Error('Ring answered HTTP 500 for the clips');
        }),
        backend('blink', 'Blink', () => new Promise<ClipsPage>(() => {})),
        backend('ok', 'Cams', inWindow([clip('c1', 'ok', 2)])),
      ],
      5,
    );
    const r = await home.clips(NOW - DAY, NOW);
    expect(r.clips.map((c) => c.id)).toEqual(['c1']);
    expect(r.problems).toHaveLength(2);
    expect(r.problems.join('\n')).toContain('Ring: Ring answered HTTP 500 for the clips');
    expect(r.problems.join('\n')).toContain("Blink: didn't answer in time");
  });

  it('reports a service with no plan, and is never an error', async () => {
    const home = newHome([backend('ring', 'Ring', async () => ({ clips: [], noPlan: true }))]);
    expect(await home.clips(NOW - DAY, NOW)).toEqual({ clips: [], problems: [], noPlan: ['Ring'] });
    expect(noPlanText('Ring')).toBe("Ring keeps recordings only with a Ring Protect plan, and this account doesn't have one.");
  });

  it('has clips only while a CONNECTED service keeps them', () => {
    const state = new Store<ServiceState>({ kind: 'signed_out' });
    const svc: HomeService = {
      id: 'ring',
      name: 'Ring',
      state,
      signInForm: () => ({ title: 't', fields: [] }),
      signIn: async () => ({ kind: 'ok' }),
      disconnect: async () => {},
      backend: () => backend('ring', 'Ring', inWindow([])),
      restore: async () => {},
    };
    const home = new Home({ store: memoryStore() as never, setTimer: () => 0 });
    home.registerService(svc);
    expect(home.hasClips).toBe(false);
    state.set({ kind: 'connected' });
    expect(home.hasClips).toBe(true);
    state.set({ kind: 'signed_out' });
    expect(home.hasClips).toBe(false);
    expect(newHome([{ ...backend('x', 'X', inWindow([])), hasClips: false }]).hasClips).toBe(false);
  });

  it('plays only https addresses, and asks the service at play time', async () => {
    let n = 0;
    const home = newHome([
      backend('ring', 'Ring', inWindow([]), { clipSource: async (id) => ({ uri: id === 'bad' ? 'http://x.example/a.mp4' : `https://x.example/${id}-${++n}.mp4` }) }),
    ]);
    expect(await home.clipSource(clip('a', 'ring', 1))).toEqual({ uri: 'https://x.example/a-1.mp4' });
    expect(await home.clipSource(clip('a', 'ring', 1))).toEqual({ uri: 'https://x.example/a-2.mp4' }); // a new address each time
    await expect(home.clipSource(clip('bad', 'ring', 1))).rejects.toThrow("isn't secure");
    await expect(home.clipSource(clip('a', 'gone', 1))).rejects.toThrow("service isn't connected");
  });
});

describe('windows', () => {
  it('asks 3-day windows, newest first, and stops at the first one that has clips', async () => {
    const asked: [number, number][] = [];
    const all = [clip('old', 'ring', 24 * 7)]; // 7 days ago: the third window
    const home = newHome([backend('ring', 'Ring', async (s, u) => (asked.push([s, u]), inWindow(all)(s, u)))]);
    const s = await loadOlder(home, emptyClips(NOW), NOW);
    expect(asked).toEqual([
      [NOW - WINDOW_MS, NOW],
      [NOW - 2 * WINDOW_MS, NOW - WINDOW_MS],
      [NOW - 3 * WINDOW_MS, NOW - 2 * WINDOW_MS],
    ]);
    expect(s.clips.map((c) => c.id)).toEqual(['old']);
    expect(s.reached).toBe(NOW - 3 * WINDOW_MS);
    expect(s.done).toBe(false);
  });

  it('loads at most four quiet windows per load, then continues from where it stopped', async () => {
    const asked: number[] = [];
    const home = newHome([backend('ring', 'Ring', async (s) => (asked.push(s), { clips: [] }))]);
    const first = await loadOlder(home, emptyClips(NOW), NOW);
    expect(asked).toHaveLength(4);
    await loadOlder(home, first, NOW);
    expect(asked).toHaveLength(8);
    expect(asked[4]).toBe(NOW - 5 * WINDOW_MS);
  });

  it('stops at 60 days and is then done; further loads ask nothing', async () => {
    const asked: number[] = [];
    const home = newHome([backend('ring', 'Ring', async (s) => (asked.push(s), { clips: [] }))]);
    let s = emptyClips(NOW);
    for (let i = 0; i < 8; i++) s = await loadOlder(home, s, NOW);
    expect(s.done).toBe(true);
    expect(Math.min(...asked)).toBe(NOW - MAX_DAYS * DAY);
    const before = asked.length;
    await loadOlder(home, s, NOW);
    expect(asked).toHaveLength(before);
  });

  it('keeps what it has, drops duplicates, and keeps the problem and no-plan lines once', async () => {
    const same = clip('a', 'ring', 1);
    const home = newHome([backend('ring', 'Ring', async () => ({ clips: [same], noPlan: false })), backend('blink', 'Blink', async () => ({ clips: [], noPlan: true }), { name: 'Ring' })]);
    const one = await loadOlder(home, emptyClips(NOW), NOW);
    const two = await loadOlder(home, one, NOW);
    expect(two.clips).toHaveLength(1);
    expect(two.noPlan).toEqual(['Ring']);
  });
});

describe('words', () => {
  it('says what made it record, when, and how long', () => {
    expect(clipKindWords(clip('a', 'ring', 1, { kind: 'ring' }))).toBe('Ring');
    expect(clipKindWords(clip('a', 'ring', 1, { kind: 'person' }))).toBe('A person');
    expect(clipKindWords(clip('a', 'ring', 1, { kind: 'live' }))).toBe('Live view');
    expect(clipKindWords(clip('a', 'ring', 1))).toBe('Motion');
    expect(clipTime(Date.parse('2026-11-20T15:42:00'), NOW)).toBe('Today 3:42 PM');
    expect(clipTime(Date.parse('2026-11-19T09:05:00'), NOW)).toBe('Yesterday 9:05 AM');
    expect(clipTime(Date.parse('2026-11-16T00:07:00'), NOW)).toBe('Mon Nov 16 12:07 AM');
    expect(clipLength(clip('a', 'ring', 1, { durationSec: 23 }))).toBe('0:23');
    expect(clipLength(clip('a', 'ring', 1, { durationSec: 125 }))).toBe('2:05');
    expect(clipLength(clip('a', 'blink', 1))).toBeNull(); // Blink gives none
  });
});

describe('the Clips list and player', () => {
  beforeEach(() => {
    mockUseVideoPlayer.mockClear();
    mockPlayer.pause.mockClear();
  });
  const sources: Record<string, { uri: string; headers?: Record<string, string> }> = {
    r1: { uri: 'https://ring.example/r1.mp4' },
    b1: { uri: 'https://rest-u014.immedia-semi.com/m/b1.mp4', headers: { Authorization: 'Bearer tok' } },
  };
  const homeWithClips = () =>
    newHome([
      backend('ring', 'Ring', inWindow([clip('r1', 'ring', 1, { kind: 'ring', durationSec: 23 })]), { clipSource: async (id) => sources[id] }),
      backend('blink', 'Blink', inWindow([clip('b1', 'blink', 2, { deviceName: 'Backyard' })]), { clipSource: async (id) => sources[id] }),
    ]);

  it('lists the clips with their words, and a quiet account says so', async () => {
    const home = homeWithClips();
    await render(<ClipsScreen visible onClose={() => {}} home={home} theme={themes.light} nowFn={() => NOW} />);
    expect(await screen.findByLabelText('Front door, Ring, Today 7:00 PM')).toBeTruthy();
    expect(screen.getByLabelText('Backyard, Motion, Today 6:00 PM')).toBeTruthy();
    expect(screen.getByText('Ring · Today 7:00 PM · 0:23')).toBeTruthy();
    expect(screen.getByText('Motion · Today 6:00 PM')).toBeTruthy(); // Blink: no length
  });

  it('says a service has no plan and still lists the others', async () => {
    const home = newHome([backend('ring', 'Ring', async () => ({ clips: [], noPlan: true })), backend('blink', 'Blink', inWindow([clip('b1', 'blink', 2)]))]);
    await render(<ClipsScreen visible onClose={() => {}} home={home} theme={themes.light} nowFn={() => NOW} />);
    expect(await screen.findByText("Ring keeps recordings only with a Ring Protect plan, and this account doesn't have one.")).toBeTruthy();
    expect(screen.getByLabelText(/Front door, Motion/)).toBeTruthy();
  });

  it('plays a Blink clip with its header, and releases the player when it closes', async () => {
    const home = homeWithClips();
    await render(<ClipsScreen visible onClose={() => {}} home={home} theme={themes.light} nowFn={() => NOW} />);
    await fireEvent.press(await screen.findByLabelText('Backyard, Motion, Today 6:00 PM'));
    await waitFor(() => expect(mockUseVideoPlayer).toHaveBeenCalled());
    expect(mockUseVideoPlayer.mock.calls.at(-1)![0]).toEqual({ uri: 'https://rest-u014.immedia-semi.com/m/b1.mp4', headers: { Authorization: 'Bearer tok' } });
    expect(mockPlayer.play).toHaveBeenCalled();
    await fireEvent.press(screen.getAllByLabelText('Close').at(-1)!);
    await waitFor(() => expect(mockPlayer.pause).toHaveBeenCalled());
  });

  it('says why a clip cannot be had', async () => {
    const home = newHome([
      backend('ring', 'Ring', inWindow([clip('r1', 'ring', 1)]), {
        clipSource: async () => {
          throw new Error('Ring answered HTTP 404 for the clip');
        },
      }),
    ]);
    await render(<ClipsScreen visible onClose={() => {}} home={home} theme={themes.light} nowFn={() => NOW} />);
    await fireEvent.press(await screen.findByLabelText(/Front door/));
    expect(await screen.findByText(/Ring answered HTTP 404 for the clip/)).toBeTruthy();
  });
});
