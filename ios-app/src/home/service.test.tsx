import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { Store } from '../core/store';
import { memoryStore } from '../settings/settings';
import { HomeSettings, ServiceRow } from '../ui/HomeSettings';
import { PageSignInModal } from '../ui/PageSignInModal';
import { ServiceSignIn } from '../ui/ServiceSignIn';
import { themes } from '../ui/theme';
import { movedWords } from './actions';
import { Home } from './home';
import type { HomeBackend, HomeDevice } from './model';
import { stateText, type HomeService, type ServiceState, type SignInForm, type SignInResult } from './service';

const mockCurrent: { home: unknown } = { home: null };
jest.mock('../core/services', () => ({
  useServices: () => ({ home: mockCurrent.home }),
  useStore: (s: { get: () => unknown }) => s.get(),
}));
type WebViewProps = { onShouldStartLoadWithRequest: (r: { url: string }) => boolean; onNavigationStateChange: (n: { url: string }) => void; source: { uri: string }; incognito?: boolean; userAgent?: string };
const mockWeb: { props: WebViewProps | null } = { props: null };
jest.mock('react-native-webview', () => ({
  WebView: (props: WebViewProps) => {
    mockWeb.props = props;
    return null;
  },
}));

const lamp: HomeDevice = { id: 'l1', name: 'Garage lamp', room: 'Garage', type: 'light', capabilities: ['on_off'], backend: 'fake', online: true, state: { on: true }, stateAt: 0 };

class FakeService implements HomeService {
  readonly id = 'fake';
  readonly name = 'Fake Cloud';
  readonly state = new Store<ServiceState>({ kind: 'signed_out' });
  signedInWith: Record<string, string>[] = [];
  disconnected = 0;
  restored = 0;
  next: SignInResult = { kind: 'ok' };
  throwOnRestore = false;
  pageSignIn?: HomeService['pageSignIn'];
  readonly backendImpl: HomeBackend = { id: 'fake', name: 'Fake Cloud', timeoutMs: 20, devices: async () => [lamp], state: async () => ({ on: true }), command: async () => {} };
  signInForm = (): SignInForm => ({
    title: 'Sign in to Fake Cloud',
    intro: 'Type your Fake Cloud login.',
    fields: [
      { key: 'email', label: 'Email', keyboard: 'email' },
      { key: 'password', label: 'Password', secret: true },
      { key: 'note', label: 'Note', optional: true },
    ],
  });
  signIn = async (values: Record<string, string>) => {
    this.signedInWith.push(values);
    if (this.next.kind === 'ok') this.state.set({ kind: 'connected', detail: '1 device' });
    return this.next;
  };
  disconnect = async () => {
    this.disconnected++;
    this.state.set({ kind: 'signed_out' });
  };
  backend = () => this.backendImpl;
  restore = async () => {
    this.restored++;
    if (this.throwOnRestore) throw new Error('keychain locked');
  };
}
const newHome = () => new Home({ store: memoryStore() as never, setTimer: () => 0 }); // no real timers: nothing may keep the test run alive

describe('Home with services', () => {
  it('reads a service only while it is connected, and drops its devices when it disconnects', async () => {
    const home = newHome();
    const svc = new FakeService();
    home.registerService(svc);
    expect(home.services.get()).toHaveLength(1);
    expect(home.connected).toBe(false);
    expect(home.backendKey()).toBe('');
    svc.state.set({ kind: 'connected' });
    expect(home.connected).toBe(true);
    expect(home.backendKey()).toBe('fake');
    expect((await home.refresh()).devices.map((d) => d.id)).toEqual(['l1']);
    svc.state.set({ kind: 'needs_sign_in', reason: 'token refused' });
    expect(home.connected).toBe(false);
    expect(home.cached()).toEqual([]);
  });

  it('a registered service is ignored the second time, and fixed backends stay beside services', async () => {
    const home = newHome();
    const svc = new FakeService();
    home.registerService(svc);
    home.registerService(svc);
    expect(home.services.get()).toHaveLength(1);
    const demo: HomeBackend = { id: 'demo', name: 'Demo', devices: async () => [{ ...lamp, id: 'd1', backend: 'demo' }], state: async () => ({}), command: async () => {} };
    home.setBackends([demo]);
    svc.state.set({ kind: 'connected' });
    expect(home.backendKey()).toBe('demo,fake');
  });

  it('restores every service at startup and one failing does not stop the others', async () => {
    const home = newHome();
    const a = new FakeService();
    a.throwOnRestore = true;
    const b = Object.assign(new FakeService(), { id: 'second' });
    home.registerService(a);
    home.registerService(b);
    await expect(home.restoreServices()).resolves.toBeUndefined();
    expect([a.restored, b.restored]).toEqual([1, 1]);
  });

  it("uses the service's own read limit", async () => {
    const home = newHome();
    const svc = new FakeService();
    (svc.backendImpl as { devices: () => Promise<HomeDevice[]> }).devices = () => new Promise(() => {});
    home.registerService(svc);
    svc.state.set({ kind: 'connected' });
    const r = await home.refresh(); // timeoutMs 20
    expect(r.problems).toEqual(['Fake Cloud: took too long']);
  });

  it('logs which service a command went to', async () => {
    const home = newHome();
    const svc = new FakeService();
    home.registerService(svc);
    svc.state.set({ kind: 'connected' });
    await home.refresh();
    await home.command(lamp, { type: 'turn_off' }, 'home');
    expect(home.commandLog()[0].service).toBe('fake');
  });
});

describe('words', () => {
  it('states in words', () => {
    expect(stateText({ kind: 'signed_out' })).toBe('Not connected');
    expect(stateText({ kind: 'connecting' })).toBe('Connecting…');
    expect(stateText({ kind: 'connected' })).toBe('Connected');
    expect(stateText({ kind: 'connected', detail: '3 devices' })).toBe('Connected · 3 devices');
    expect(stateText({ kind: 'needs_sign_in', reason: 'token refused' })).toBe('Sign in again: token refused');
    expect(stateText({ kind: 'error', message: 'Not reachable.' })).toBe('Not reachable.');
  });
  it('moved ago', () => {
    expect(movedWords(undefined, 1000)).toBe('');
    expect(movedWords(0, 17 * 3600_000)).toBe('moved 17 h ago');
    expect(movedWords(0, 10_000)).toBe('moved just now');
  });
});

describe('Settings → Home services', () => {
  it('shows each service with its state and the right button', async () => {
    const svc = new FakeService();
    const home = newHome();
    home.registerService(svc);
    mockCurrent.home = home;
    const ask = jest.fn((_n: string, go: () => void) => go());
    const onSignIn = jest.fn();
    await render(<ServiceRow service={svc} theme={themes.light} onSignIn={onSignIn} ask={ask} />);
    expect(screen.getByText('Not connected')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Sign in to Fake Cloud'));
    expect(onSignIn).toHaveBeenCalledWith(false);
    expect(screen.queryByLabelText('Disconnect Fake Cloud')).toBeNull();
  });

  it('offers Sign in again and Disconnect for a refused token, and asks before disconnecting', async () => {
    const svc = new FakeService();
    svc.state.set({ kind: 'needs_sign_in', reason: 'token refused' });
    const ask = jest.fn();
    const onSignIn = jest.fn();
    await render(<ServiceRow service={svc} theme={themes.light} onSignIn={onSignIn} ask={ask} />);
    expect(screen.getByText('Sign in again: token refused')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Sign in again to Fake Cloud'));
    expect(onSignIn).toHaveBeenCalledWith(true);
    await fireEvent.press(screen.getByLabelText('Disconnect Fake Cloud'));
    expect(ask).toHaveBeenCalledWith('Fake Cloud', expect.any(Function));
    expect(svc.disconnected).toBe(0); // nothing until the owner agrees
    ask.mock.calls[0][1]();
    expect(svc.disconnected).toBe(1);
  });

  it('opens the form from the Settings section and signs in', async () => {
    const svc = new FakeService();
    const home = newHome();
    home.registerService(svc);
    mockCurrent.home = home;
    await render(<HomeSettings theme={themes.light} />);
    expect(screen.getByText('Services')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Sign in to Fake Cloud'));
    expect(screen.getByText('Type your Fake Cloud login.')).toBeTruthy();
    expect(screen.getByLabelText('Password').props.secureTextEntry).toBe(true);
    expect(screen.getByLabelText('Email').props.secureTextEntry).toBeFalsy();
    await fireEvent.changeText(screen.getByLabelText('Email'), 'me@example.com');
    await fireEvent.changeText(screen.getByLabelText('Password'), 'hunter2');
    await fireEvent.press(screen.getByLabelText('Sign in'));
    // The sign-in resolves a moment later: wait for it instead of hoping it already has (this test failed once under load).
    await waitFor(() => expect(screen.queryByText('Type your Fake Cloud login.')).toBeNull()); // the form closed
    expect(svc.signedInWith).toEqual([{ email: 'me@example.com', password: 'hunter2' }]);
  });
});

describe('the sign-in form', () => {
  const open = async (svc: FakeService, again = false) => {
    const onClose = jest.fn();
    await render(<ServiceSignIn service={svc} again={again} theme={themes.light} onClose={onClose} />);
    return onClose;
  };
  const fill = async () => {
    await fireEvent.changeText(screen.getByLabelText('Email'), 'me@example.com');
    await fireEvent.changeText(screen.getByLabelText('Password'), 'hunter2');
  };

  it("keeps Submit off until the required fields are filled, and the optional one doesn't count", async () => {
    await open(new FakeService());
    expect(screen.getByLabelText('Sign in').props.accessibilityState?.disabled ?? screen.getByLabelText('Sign in').props.disabled).toBeTruthy();
    await fill();
    expect(screen.getByLabelText('Sign in').props.disabled).toBeFalsy();
  });

  it("shows the service's own words when sign-in fails, and keeps the form", async () => {
    const svc = new FakeService();
    svc.next = { kind: 'failed', message: 'Wrong password.' };
    const onClose = await open(svc);
    await fill();
    await fireEvent.press(screen.getByLabelText('Sign in'));
    expect(screen.getByText('Wrong password.')).toBeTruthy();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('never shows text from a thrown error, which could hold a secret', async () => {
    const svc = new FakeService();
    svc.signIn = async () => {
      throw new Error('token=SECRET123');
    };
    await open(svc);
    await fill();
    await fireEvent.press(screen.getByLabelText('Sign in'));
    expect(screen.queryByText(/SECRET123/)).toBeNull();
    expect(screen.getByText('Something went wrong signing in. Try again.')).toBeTruthy();
  });

  it('shows the code step next, sends both steps together, and closes', async () => {
    const svc = new FakeService();
    svc.next = { kind: 'needs_code', form: { title: 'Enter the code', fields: [{ key: 'code', label: 'Code', keyboard: 'number' }], submitLabel: 'Verify' } };
    const onClose = await open(svc);
    await fill();
    await fireEvent.press(screen.getByLabelText('Sign in'));
    expect(screen.getByText('Enter the code')).toBeTruthy();
    expect(screen.queryByLabelText('Password')).toBeNull();
    svc.next = { kind: 'ok' };
    await fireEvent.changeText(screen.getByLabelText('Code'), '123456');
    await fireEvent.press(screen.getByLabelText('Verify'));
    expect(svc.signedInWith[1]).toEqual({ email: 'me@example.com', password: 'hunter2', code: '123456' });
    expect(onClose).toHaveBeenCalled();
  });

  it('says "again" when signing in again, and Cancel closes without signing in', async () => {
    const svc = new FakeService();
    const onClose = await open(svc, true);
    expect(screen.getByText('Sign in to Fake Cloud again')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Cancel sign-in'));
    expect(onClose).toHaveBeenCalled();
    expect(svc.signedInWith).toEqual([]);
  });
});

describe('the page sign-in', () => {
  it('stops the load at the finish address, hands the code on once, and lets other pages load', async () => {
    const svc = new FakeService();
    const complete = jest.fn(async () => ({ kind: 'ok' }) as SignInResult);
    svc.pageSignIn = {
      startUrl: () => 'https://signin.example.com/start',
      isFinish: (url) => (url.includes('/done?code=') ? { code: url.split('code=')[1] } : null),
      complete,
    };
    const onClose = jest.fn();
    await render(<PageSignInModal service={svc} theme={themes.light} onClose={onClose} />);
    const p = mockWeb.props!;
    expect(p.source.uri).toBe('https://signin.example.com/start');
    expect(p.incognito).toBe(true);
    expect(p.userAgent).toContain('Safari');
    expect(p.onShouldStartLoadWithRequest({ url: 'https://signin.example.com/next' })).toBe(true);
    await act(async () => {
      expect(p.onShouldStartLoadWithRequest({ url: 'https://signin.example.com/done?code=ABC' })).toBe(false);
      p.onNavigationStateChange({ url: 'https://signin.example.com/done?code=ABC' }); // the same finish seen again
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(complete).toHaveBeenCalledTimes(1);
    expect(complete).toHaveBeenCalledWith('ABC');
    expect(onClose).toHaveBeenCalled();
  });
});
