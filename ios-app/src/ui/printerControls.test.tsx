import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { initialControl, type ConfirmUi, type ControlUi } from '../printer/feed/control';
import type { PrinterUi } from '../printer/feed/page';
import type { PrinterScreenState } from '../printer/feed/session';
import { fixture } from '../printer/feed/testkit';
import { parseStateAnswer } from '../printer/feed/wire';
import { COLLAPSED_AT_FIRST } from '../printer/feed/prefs';
import { ConfirmSheet, ControlMessage, ControlsBar, EmergencyStop, JobButtons, TURN_ON_CONTROLS, disabledReason } from './printer/ControlViews';
import { themes } from './theme';

const theme = themes.light;
const NOW = 1_763_400_000_000;
const answer = (name: string, over: Record<string, unknown> = {}) => parseStateAnswer({ ...fixture(name), ...over });
const live = (name = 'state-printing', over: Record<string, unknown> = {}): PrinterScreenState => ({ kind: 'live', data: answer(name, over), receivedAt: NOW, offsetMs: 0, stale: false });
const ui = (screen: PrinterScreenState): PrinterUi => ({ screen, name: 'Printer', collapsed: COLLAPSED_AT_FIRST, dismissedAlert: null, now: NOW });
const c = (over: Partial<ControlUi> = {}): ControlUi => ({ ...initialControl(), mayControl: true, ...over });
const stateOf = (u: PrinterUi) => (u.screen.kind === 'live' ? u.screen.data.state : (null as never));

describe('why a control cannot be used', () => {
  it('Jarvis away, Controls off, and the server\'s own reason for a parameterless command', () => {
    expect(disabledReason(ui({ kind: 'away', last: answer('state-printing'), why: 'x' }), c({ armed: true }))).toBe("Jarvis isn't reachable right now.");
    expect(disabledReason(ui(live()), c())).toBe(TURN_ON_CONTROLS);
    expect(disabledReason(ui(live()), c({ armed: true }))).toBeNull();
    expect(disabledReason(ui(live()), c({ armed: true }), 'pause')).toBeNull();
    const refusing = live('state-printing', { quick: { pause: { allowed: false, reason: 'Already paused.' }, resume: { allowed: false } } });
    expect(disabledReason(ui(refusing), c({ armed: true }), 'pause')).toBe('Already paused.');
    expect(disabledReason(ui(refusing), c({ armed: true }), 'resume')).toBe('Not now.');
    expect(disabledReason(ui(refusing), c({ armed: true }), 'home:xyz')).toBeNull(); // nothing said about it
  });
});

describe('the Controls bar', () => {
  it('off: just "Controls" and a switch that asks to turn them on', async () => {
    const calls: boolean[] = [];
    await render(<ControlsBar ui={ui(live())} c={c()} theme={theme} onToggle={(on) => calls.push(on)} />);
    expect(screen.getByTestId('controls-text')).toHaveTextContent('Controls');
    expect(screen.getByTestId('controls-text')).not.toHaveTextContent('on');
    await act(async () => fireEvent(screen.getByLabelText('Controls'), 'valueChange', true));
    expect(calls).toEqual([true]);
  });

  it("on: counts down from Jarvis's own timer with its clock offset", async () => {
    jest.useFakeTimers({ now: NOW + 1_000 });
    await render(<ControlsBar ui={ui(live())} c={c({ armed: true, armUntilMs: NOW + 103_000, offsetMs: 0 })} theme={theme} onToggle={() => {}} />);
    expect(screen.getByTestId('controls-text')).toHaveTextContent('Controls on · 1:42');
    await act(async () => {
      jest.advanceTimersByTime(2_000);
    });
    expect(screen.getByTestId('controls-text')).toHaveTextContent('Controls on · 1:40');
    jest.useRealTimers();
    await render(<ControlsBar ui={ui(live())} c={c({ armed: true, armUntilMs: NOW + 103_000, offsetMs: 60_000 })} theme={theme} onToggle={() => {}} />);
    expect(screen.getByTestId('controls-text').props.children).toMatch(/Controls on · \d:\d\d/);
  });

  it('the switch is disabled while Jarvis is away', async () => {
    await render(<ControlsBar ui={ui({ kind: 'away', last: answer('state-printing'), why: 'x' })} c={c({ armed: true })} theme={theme} onToggle={() => {}} />);
    expect(screen.getByLabelText('Controls').props.disabled).toBe(true);
    await render(<ControlsBar ui={ui(live())} c={c({ armed: true })} theme={theme} onToggle={() => {}} />);
    expect(screen.getByLabelText('Controls').props.disabled).toBeFalsy();
  });

  it('a look-only phone gets one line instead; nothing at all when there are no cards', async () => {
    await render(<ControlsBar ui={ui(live())} c={c({ mayControl: false })} theme={theme} onToggle={() => {}} />);
    expect(screen.getByTestId('controls-look-only')).toHaveTextContent('Jarvis lets this phone look, not control.');
    expect(screen.queryByTestId('controls-switch')).toBeNull();
    await render(<ControlsBar ui={ui({ kind: 'noJarvis' })} c={c()} theme={theme} onToggle={() => {}} />);
    expect(screen.queryByTestId('controls-bar')).toBeNull();
    expect(screen.queryByTestId('controls-look-only')).toBeNull();
  });
});

describe('Emergency stop', () => {
  it('is there whenever the printer is ready, with Controls off and on a look-only phone', async () => {
    const calls: number[] = [];
    const u = ui(live());
    await render(<EmergencyStop ui={u} state={stateOf(u)} theme={theme} onPress={() => calls.push(1)} />);
    await act(async () => fireEvent.press(screen.getByLabelText('Emergency stop')));
    expect(calls).toHaveLength(1);
  });
  it('is not there when Jarvis is away, the printer is not ready, or there is no state', async () => {
    const away = ui({ kind: 'away', last: answer('state-printing'), why: 'x' });
    await render(<EmergencyStop ui={away} state={stateOf(ui(live()))} theme={theme} onPress={() => {}} />);
    expect(screen.queryByTestId('estop')).toBeNull();
    const down = ui(live('state-klippy-shutdown'));
    await render(<EmergencyStop ui={down} state={stateOf(down)} theme={theme} onPress={() => {}} />);
    expect(screen.queryByTestId('estop')).toBeNull();
    await render(<EmergencyStop ui={ui({ kind: 'connecting' })} state={null} theme={theme} onPress={() => {}} />);
    expect(screen.queryByTestId('estop')).toBeNull();
  });
});

describe('the confirm sheet', () => {
  const sheet = (over: Partial<ConfirmUi> = {}): ConfirmUi => ({ question: 'Really cancel the print?', confirm: 'Cancel print', danger: true, command: { kind: 'cancel' }, requestId: 'r', keepLabel: 'Keep printing', ...over });
  it("shows the server's question and words; the two buttons are never alike", async () => {
    const calls: string[] = [];
    await render(<ConfirmSheet c={c({ sheet: sheet() })} theme={theme} onConfirm={() => calls.push('yes')} onDismiss={() => calls.push('no')} />);
    expect(screen.getByTestId('confirm-question')).toHaveTextContent('Really cancel the print?');
    expect(screen.getByLabelText('Cancel print')).toBeTruthy();
    expect(screen.getByLabelText('Keep printing')).toBeTruthy();
    expect(screen.queryByText('OK')).toBeNull();
    expect(screen.queryByText('Yes')).toBeNull();
    await act(async () => fireEvent.press(screen.getByTestId('confirm-no')));
    await act(async () => fireEvent.press(screen.getByTestId('confirm-yes')));
    expect(calls).toEqual(['no', 'yes']);
  });
  it('is not there without a sheet', async () => {
    await render(<ConfirmSheet c={c()} theme={theme} onConfirm={() => {}} onDismiss={() => {}} />);
    expect(screen.queryByTestId('confirm-sheet')).toBeNull();
  });
});

describe('the job buttons', () => {
  const sent: string[] = [];
  const show = async (u: PrinterUi, ctl: ControlUi) => render(<JobButtons ui={u} state={stateOf(u)} c={ctl} theme={theme} onSend={(x) => sent.push(x.kind)} />);
  beforeEach(() => (sent.length = 0));

  it('Pause while printing, Resume while paused; Cancel print always; each sends its command', async () => {
    await show(ui(live()), c({ armed: true }));
    await act(async () => fireEvent.press(screen.getByLabelText('Pause')));
    await act(async () => fireEvent.press(screen.getByLabelText('Cancel print')));
    expect(sent).toEqual(['pause', 'cancel']);
    await show(ui(live('state-paused')), c({ armed: true }));
    expect(screen.getByLabelText('Resume')).toBeTruthy();
    expect(screen.queryByLabelText('Pause')).toBeNull();
  });

  it('with Controls off the buttons cannot be pressed and say why', async () => {
    await show(ui(live()), c());
    expect(screen.getByLabelText('Pause').props.accessibilityState.disabled).toBe(true);
    expect(screen.getByTestId('job-why')).toHaveTextContent(TURN_ON_CONTROLS);
    await act(async () => fireEvent.press(screen.getByLabelText('Pause')));
    expect(sent).toEqual([]);
  });

  it("the server's reason wins for a command it will not allow, and a sent command shows a spinner and cannot be pressed again", async () => {
    await show(ui(live('state-printing', { quick: { pause: { allowed: false, reason: 'Already pausing.' } } })), c({ armed: true }));
    expect(screen.getByTestId('job-why')).toHaveTextContent('Already pausing.');
    expect(screen.getByLabelText('Pause').props.accessibilityState.disabled).toBe(true);
    await show(ui(live()), c({ armed: true, pending: new Set(['cancel']) }));
    expect(screen.getByLabelText('Cancel print').props.accessibilityState).toMatchObject({ disabled: true, busy: true });
    expect(screen.getByLabelText('Pause').props.accessibilityState.disabled).toBe(false);
    await show(ui(live()), c({ armed: true, pending: new Set(['pause']) }));
    expect(screen.getByLabelText('Pause').props.accessibilityState).toMatchObject({ disabled: true, busy: true });
  });
});

describe('the message line', () => {
  it('shows once, and goes when pressed or after six seconds', async () => {
    jest.useFakeTimers();
    const shown: number[] = [];
    await render(<ControlMessage c={c({ message: 'Home the printer first.' })} theme={theme} onShown={() => shown.push(1)} />);
    expect(screen.getByTestId('control-message')).toHaveTextContent('Home the printer first.');
    await act(async () => {
      jest.advanceTimersByTime(5_999);
    });
    expect(shown).toHaveLength(0);
    await act(async () => {
      jest.advanceTimersByTime(1);
    });
    expect(shown).toHaveLength(1);
    await act(async () => fireEvent.press(screen.getByTestId('control-message')));
    expect(shown).toHaveLength(2);
    jest.useRealTimers();
    await render(<ControlMessage c={c()} theme={theme} onShown={() => {}} />);
    expect(screen.queryByTestId('control-message')).toBeNull();
  });
});
