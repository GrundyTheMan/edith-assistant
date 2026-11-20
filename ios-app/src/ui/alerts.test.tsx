import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { AppState } from 'react-native';
import { AlertLoop } from '../alerts/loop';
import { AlertChoices } from '../alerts/model';
import type { Notifier } from '../alerts/notifications';
import { AlertPrefs } from '../alerts/prefs';
import { AlertWatcher } from '../alerts/watcher';
import { Store } from '../core/store';
import { Home } from '../home/home';
import type { HomeService, ServiceState } from '../home/service';
import { memoryStore } from '../settings/settings';
import { AlertsHost } from './AlertsHost';
import { AlertsSettings } from './AlertsSettings';
import { CatchUpBanner } from './CatchUpBanner';
import { themes } from './theme';

const mockCurrent: { services: unknown } = { services: null };
const mockNavigate = jest.fn();
jest.mock('../core/services', () => ({
  useServices: () => mockCurrent.services,
  useStore: (s: { get: () => unknown }) => s.get(),
}));
jest.mock('expo-router', () => ({ useRouter: () => ({ navigate: mockNavigate }) }));

const theme = themes.light;

function service(id: string, name: string, connected: boolean): HomeService {
  const state = new Store<ServiceState>(connected ? { kind: 'connected' } : { kind: 'signed_out' });
  return {
    id,
    name,
    state,
    signInForm: () => ({ title: 't', fields: [] }),
    signIn: async () => ({ kind: 'ok' }),
    disconnect: async () => {},
    backend: () => ({ id, name, devices: async () => [], state: async () => ({}), command: async () => {} }),
    restore: async () => {},
  };
}

async function setup(opts: { permission?: 'granted' | 'denied' | 'undetermined'; allow?: boolean; services?: HomeService[] } = {}) {
  const home = new Home({ store: memoryStore() as never, setTimer: () => 0 });
  for (const s of opts.services ?? [service('ring', 'Ring', true), service('blink', 'Blink', true), service('meross', 'Meross', true), service('alexa', 'Alexa', false)]) home.registerService(s);
  const prefs = new AlertPrefs(memoryStore());
  await prefs.load();
  const opened: (() => void)[] = [];
  const notifier: Notifier = {
    permission: jest.fn(async () => opts.permission ?? 'undetermined'),
    request: jest.fn(async () => opts.allow ?? true),
    show: jest.fn(async () => {}),
    onOpen: jest.fn((cb) => {
      opened.push(cb);
      return () => {};
    }),
  };
  const watcher = new AlertWatcher({ activity: async () => [] });
  const loop = new AlertLoop({ watcher, choices: prefs.choices, notifier, photo: async () => null });
  const demoRing = jest.fn();
  mockCurrent.services = { home, alerts: { prefs, loop, notifier, demoRing } };
  return { home, prefs, notifier, loop, demoRing, opened };
}

describe('AlertsSettings', () => {
  it('lists each signed-in service that can alert with its kinds, all off, and says what iPhone allows', async () => {
    await setup();
    await render(<AlertsSettings theme={theme} />);
    expect(screen.getByText(/Alerts arrive while Edith is open, and when you return/)).toBeTruthy();
    for (const label of ['Ring: Doorbell rings', 'Ring: A person is seen', 'Ring: Motion', 'Blink: Motion']) {
      expect(screen.getByLabelText(label).props.value).toBe(false);
    }
    expect(screen.queryByLabelText(/Meross/)).toBeNull(); // no alerts for a garage door
    expect(screen.queryByLabelText(/Alexa/)).toBeNull(); // not signed in
  });

  it('asks for notifications when the FIRST alert is switched on, then saves it', async () => {
    const { prefs, notifier } = await setup();
    await render(<AlertsSettings theme={theme} />);
    await fireEvent(screen.getByLabelText('Ring: Doorbell rings'), 'valueChange', true);
    await waitFor(() => expect(prefs.choices.get().enabled('ring', 'doorbell')).toBe(true));
    expect(notifier.request).toHaveBeenCalledTimes(1);
    await fireEvent(screen.getByLabelText('Blink: Motion'), 'valueChange', true);
    await waitFor(() => expect(prefs.choices.get().enabled('blink', 'motion')).toBe(true));
    expect(notifier.request).toHaveBeenCalledTimes(1); // not asked again
  });

  it('does not ask again when the phone already allows them', async () => {
    const { notifier } = await setup({ permission: 'granted' });
    await render(<AlertsSettings theme={theme} />);
    await fireEvent(screen.getByLabelText('Ring: Motion'), 'valueChange', true);
    await waitFor(() => expect(notifier.permission).toHaveBeenCalled());
    expect(notifier.request).not.toHaveBeenCalled();
  });

  it('keeps everything off and says how to allow notifications when the owner refuses', async () => {
    const { prefs } = await setup({ permission: 'denied', allow: false });
    await render(<AlertsSettings theme={theme} />);
    await fireEvent(screen.getByLabelText('Ring: Doorbell rings'), 'valueChange', true);
    expect(await screen.findByText(/Notifications are off for Edith/)).toBeTruthy();
    expect(prefs.choices.get().any).toBe(false);
  });

  it('says to sign in when no camera service is connected', async () => {
    await setup({ services: [service('ring', 'Ring', false)] });
    await render(<AlertsSettings theme={theme} />);
    expect(screen.getByText('Sign in to Ring or Blink above to switch alerts on.')).toBeTruthy();
  });

  it('has a Demo doorbell button in development builds with the Demo home on', async () => {
    const { home, demoRing } = await setup({ services: [] });
    await render(<AlertsSettings theme={theme} />);
    expect(screen.queryByLabelText('Ring the Demo doorbell')).toBeNull();
    home.setDemo(true);
    await render(<AlertsSettings theme={theme} />);
    await fireEvent.press(screen.getAllByLabelText('Ring the Demo doorbell')[0]);
    expect(demoRing).toHaveBeenCalled();
    expect(screen.getAllByLabelText('Demo home: Doorbell rings').length).toBeGreaterThan(0);
  });
});

describe('CatchUpBanner', () => {
  it('shows the summary, opens the clips, dismisses; and shows nothing without one', async () => {
    const store = new Store<{ text: string; alerts: never[] } | null>(null);
    const onOpen = jest.fn();
    const onDismiss = jest.fn();
    const { rerender } = await render(<CatchUpBanner store={store as never} onOpen={onOpen} onDismiss={onDismiss} theme={theme} />);
    expect(screen.queryByText('While you were away')).toBeNull();
    store.set({ text: 'Since 3:10 PM: Front door rang (2)', alerts: [] });
    await rerender(<CatchUpBanner store={store as never} onOpen={onOpen} onDismiss={onDismiss} theme={theme} />);
    expect(screen.getByText('While you were away')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Since 3:10 PM: Front door rang (2). Open clips'));
    expect(onOpen).toHaveBeenCalled();
    await fireEvent.press(screen.getByLabelText('Dismiss'));
    expect(onDismiss).toHaveBeenCalled();
  });
});

describe('AlertsHost', () => {
  it('tells the loop whether the app is active, and a tapped alert opens the Home tab\'s Clips list', async () => {
    const { loop, home, opened } = await setup();
    const setActive = jest.spyOn(loop, 'setActive');
    const handlers: ((s: string) => void)[] = [];
    jest.spyOn(AppState, 'addEventListener').mockImplementation(((_: string, h: (s: string) => void) => {
      handlers.push(h);
      return { remove: () => {} };
    }) as never);
    await render(<AlertsHost />);
    expect(setActive).toHaveBeenLastCalledWith(AppState.currentState === 'active');
    await act(async () => handlers[0]('background'));
    expect(setActive).toHaveBeenLastCalledWith(false);
    await act(async () => handlers[0]('active'));
    expect(setActive).toHaveBeenLastCalledWith(true);

    const before = home.clipsRequest.get();
    await act(async () => opened[0]());
    expect(mockNavigate).toHaveBeenCalledWith('/home');
    expect(home.clipsRequest.get()).toBe(before + 1);
    expect(new AlertChoices().any).toBe(false);
  });
});
