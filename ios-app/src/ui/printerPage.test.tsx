import { fireEvent, render, screen } from '@testing-library/react-native';
import { initialControl } from '../printer/feed/control';
import type { CardsUi } from '../printer/feed/cards';
import type { CardActions } from './printer/Cards';
import { COLLAPSED_AT_FIRST } from '../printer/feed/prefs';
import type { PrinterUi } from '../printer/feed/page';
import type { PrinterScreenState } from '../printer/feed/session';
import { fixture } from '../printer/feed/testkit';
import { parseStateAnswer } from '../printer/feed/wire';
import { PrinterWords } from '../printer/feed/words';
import { PrinterPage } from './printer/PrinterScreen';
import { printStateLabel, statusTitle } from './printer/StatusCard';
import { themes } from './theme';

jest.mock('../core/services', () => ({ useServices: jest.fn(), useStore: jest.fn() }));

const noActions = new Proxy({}, { get: () => () => {} }) as CardActions;
const cardsUi = (): CardsUi => ({ chart: [], chartHidden: new Set(), history: { jobs: [], totals: null, loading: false, more: false, error: null }, files: { files: null, cachedAt: 0, loading: false, error: null, query: '', printedOnly: true, detailOf: null, detail: null, detailLoading: false, detailError: null }, terminal: { lines: [], hideValues: true, error: null }, camera: { cameraId: null, frame: null, number: 0, offline: false } });
const NOW = 1_763_400_000_000;
const answer = (name: string) => parseStateAnswer(fixture(name));
const live = (name: string): PrinterScreenState => ({ kind: 'live', data: answer(name), receivedAt: NOW, offsetMs: 0, stale: false });
const ui = (screen: PrinterScreenState, extra: Partial<PrinterUi> = {}): PrinterUi => ({ screen, name: 'FlashForge AD5X', collapsed: COLLAPSED_AT_FIRST, dismissedAlert: null, now: NOW, ...extra });

async function show(u: PrinterUi, over: Partial<Parameters<typeof PrinterPage>[0]> = {}) {
  const calls = { back: 0, again: 0, pair: 0, toggled: [] as string[], dismissed: [] as number[] };
  await render(
    <PrinterPage
      ui={u}
      theme={themes.light}
      is24Hour
      lastPrint={null}
      cards={cardsUi()}
      actions={noActions}
      controls={initialControl()}
      controlActions={noActions as never}
      directConnected={false}
      onBack={() => calls.back++}
      onTryAgain={() => calls.again++}
      onPair={() => calls.pair++}
      onToggleCard={(id) => calls.toggled.push(id)}
      onDismissAlert={(t) => calls.dismissed.push(t)}
      {...over}
    />,
  );
  return calls;
}

describe('the Printer page', () => {
  it('connecting shows a spinner and its words', async () => {
    await show(ui({ kind: 'connecting' }));
    expect(screen.getByTestId('connecting')).toBeTruthy();
    expect(screen.getByTestId('connection-chip')).toHaveTextContent('Connecting…');
  });

  it('no Jarvis offers pairing and says the home tile still works when it is connected', async () => {
    const c = await show(ui({ kind: 'noJarvis' }), { directConnected: true });
    expect(screen.getByTestId('printer-message')).toHaveTextContent(/Pair Edith with Jarvis/);
    expect(screen.getByText('At home, the printer tile on Home still works.')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Pair with Jarvis'));
    expect(c.pair).toBe(1);
  });

  it('not shared and no printer are full-page messages without cards', async () => {
    await show(ui({ kind: 'notShared' }));
    expect(screen.getByTestId('printer-message')).toHaveTextContent(/Let paired phones see the printer/);
    expect(screen.queryByTestId('card-status')).toBeNull();
    expect(screen.queryByLabelText('Pair with Jarvis')).toBeNull();
  });

  it('a printing page shows the title, state, progress and the facts', async () => {
    await show(ui(live('state-printing')));
    const p = answer('state-printing').state.print;
    expect(screen.getByTestId('connection-chip')).toHaveTextContent('Connected');
    expect(screen.getByText(statusTitle(p))).toBeTruthy();
    expect(screen.getByText('Printing')).toBeTruthy();
    expect(screen.getByText('Time left')).toBeTruthy();
    expect(screen.getByText('ETA')).toBeTruthy();
    expect(screen.getByLabelText('Fold Status')).toBeTruthy();
    expect(screen.getByTestId('card-camera')).toBeTruthy();
    expect(screen.getByTestId('card-temperatures')).toBeTruthy();
  });

  it('folding a card asks the caller to toggle it; a folded one shows only its title', async () => {
    const c = await show(ui(live('state-printing')));
    await fireEvent.press(screen.getByLabelText('Fold Status'));
    expect(c.toggled).toEqual(['status']);
  });

  it('a folded Status card hides its body', async () => {
    await show(ui(live('state-printing'), { collapsed: new Set(['status']) }));
    expect(screen.queryByTestId('status-card')).toBeNull();
    expect(screen.getByLabelText('Open Status')).toBeTruthy();
  });

  it('an alert can be dismissed', async () => {
    const c = await show(ui(live('state-alert')));
    expect(screen.getByText('The printer said: Filament runout detected')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Dismiss'));
    expect(c.dismissed).toHaveLength(1);
  });

  it('an unreachable printer shows the banner over the last page', async () => {
    await show(ui(live('state-unreachable')));
    expect(screen.getByTestId('banner')).toHaveTextContent(/reach the printer/);
    expect(screen.getByTestId('status-card')).toBeTruthy();
  });

  it('Jarvis away with a last page shows a banner and Try again; with none, the reason alone', async () => {
    const c = await show(ui({ kind: 'away', last: answer('state-printing'), why: PrinterWords.UNREACHABLE }));
    expect(screen.getByTestId('banner')).toHaveTextContent(new RegExp(PrinterWords.UNREACHABLE.replace(/[.*+?^${}()|[]\]/g, "\$&")));
    await fireEvent.press(screen.getByLabelText('Try again'));
    expect(c.again).toBe(1);
  });

  it('Jarvis away with nothing to show is the reason with Try again', async () => {
    const c = await show(ui({ kind: 'away', last: null, why: PrinterWords.UNREACHABLE }));
    expect(screen.getByTestId('printer-message')).toHaveTextContent(new RegExp(PrinterWords.UNREACHABLE.replace(/[.*+?^${}()|[]\]/g, "\$&")));
    await fireEvent.press(screen.getByLabelText('Try again'));
    expect(c.again).toBe(1);
  });

  it('the idle page names the last print', async () => {
    await show(ui(live('state-ready-idle')), { lastPrint: { title: 'Benchy', status: 'completed', at: NOW - 2 * 3_600_000 - 5000 } });
    expect(screen.getByTestId('last-print')).toHaveTextContent('Last print: Benchy, completed 2 h ago');
  });

  it('a controlling phone sees the Controls bar, the emergency stop and the job buttons; a look-only one does not see buttons', async () => {
    const send: string[] = [];
    await show(ui(live('state-printing')), { controls: { ...initialControl(), mayControl: true, armed: true }, controlActions: { send: (c: { kind: string }) => send.push(c.kind) } as never });
    expect(screen.getByTestId('controls-bar')).toBeTruthy();
    expect(screen.getByTestId('job-buttons')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Emergency stop'));
    await fireEvent.press(screen.getByLabelText('Pause'));
    expect(send).toEqual(['estop', 'pause']);
  });

  it('a look-only phone has no job buttons and no Controls switch, but keeps the emergency stop', async () => {
    await show(ui(live('state-printing')));
    expect(screen.queryByTestId('job-buttons')).toBeNull();
    expect(screen.getByTestId('controls-look-only')).toBeTruthy();
    expect(screen.getByLabelText('Emergency stop')).toBeTruthy();
  });

  it('the confirm sheet and the control message are drawn over the page', async () => {
    const sheet = { question: 'Really cancel?', confirm: 'Cancel print', danger: true, command: { kind: 'cancel' }, requestId: 'r', keepLabel: 'Keep printing' };
    await show(ui(live('state-printing')), { controls: { ...initialControl(), mayControl: true, armed: true, sheet: sheet as never, message: 'Home the printer first.' } });
    expect(screen.getByTestId('confirm-question')).toHaveTextContent('Really cancel?');
    expect(screen.getByTestId('control-message')).toBeTruthy();
  });

  it('Back goes back', async () => {
    const c = await show(ui({ kind: 'noJarvis' }));
    await fireEvent.press(screen.getByLabelText('Back'));
    expect(c.back).toBe(1);
  });
});

describe('the Status card words', () => {
  it('names states, including one from a newer Jarvis as it came', async () => {
    expect(printStateLabel('standby')).toBe('Standby');
    expect(printStateLabel('cancelled')).toBe('Cancelled');
    expect(printStateLabel('homing')).toBe('Homing');
  });
});
