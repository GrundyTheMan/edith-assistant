import { fireEvent, render, screen } from '@testing-library/react-native';
import { Store } from '../core/store';
import { SettingsRepository, memoryStore } from '../settings/settings';
import { STATE_TEXT, type ConversationKind } from '../voice/conversation';
import { ConversationBanner, ConversationButton } from './ConversationViews';
import { ConversationSettings } from './ConversationSettings';
import { MessageBubble } from './ChatComponents';
import { themes } from './theme';

const mockCurrent: { services: unknown } = { services: null };
jest.mock('../core/services', () => ({
  useServices: () => mockCurrent.services,
  useStore: (s: { get: () => unknown }) => s.get(),
}));

const theme = themes.light;
const KINDS = Object.keys(STATE_TEXT) as Exclude<ConversationKind, 'off'>[];

describe('ConversationBanner', () => {
  it.each(KINDS)('says what Edith is doing: %s', async (kind) => {
    await render(<ConversationBanner kind={kind} level={() => 0.4} onEnd={() => {}} onStopSpeaking={() => {}} theme={theme} />);
    expect(screen.getByText(STATE_TEXT[kind])).toBeTruthy();
    expect(screen.getByLabelText('End conversation')).toBeTruthy();
  });

  it('has an End button that ends it', async () => {
    const onEnd = jest.fn();
    await render(<ConversationBanner kind="listening" level={() => 0} onEnd={onEnd} onStopSpeaking={() => {}} theme={theme} />);
    await fireEvent.press(screen.getByLabelText('End conversation'));
    expect(onEnd).toHaveBeenCalledTimes(1);
  });

  it('shows a Stop button only while Edith speaks, and the meter only while the microphone is open', async () => {
    const onStop = jest.fn();
    const { rerender } = await render(<ConversationBanner kind="speaking" level={() => 0} onEnd={() => {}} onStopSpeaking={onStop} theme={theme} />);
    await fireEvent.press(screen.getByLabelText('Stop speaking'));
    expect(onStop).toHaveBeenCalledTimes(1);
    expect(screen.queryByLabelText('Microphone level')).toBeNull();
    await rerender(<ConversationBanner kind="followup" level={() => 0.5} onEnd={() => {}} onStopSpeaking={onStop} theme={theme} />);
    expect(screen.queryByLabelText('Stop speaking')).toBeNull();
    expect(screen.getByLabelText('Microphone level')).toBeTruthy();
    await rerender(<ConversationBanner kind="waiting_tap" level={() => 0} onEnd={() => {}} onStopSpeaking={onStop} theme={theme} />);
    expect(screen.getByText('Tap Confirm or Cancel on the card above.')).toBeTruthy();
    expect(screen.queryByLabelText('Microphone level')).toBeNull();
  });
});

describe('ConversationButton', () => {
  it('is the header button, and does nothing while a reply is running', async () => {
    const onPress = jest.fn();
    const { rerender } = await render(<ConversationButton on={false} onPress={onPress} theme={theme} />);
    await fireEvent.press(screen.getByLabelText('Conversation mode'));
    expect(onPress).toHaveBeenCalledTimes(1);
    await rerender(<ConversationButton on={false} disabled onPress={onPress} theme={theme} />);
    await fireEvent.press(screen.getByLabelText('Conversation mode'));
    expect(onPress).toHaveBeenCalledTimes(1);
    expect(screen.getByLabelText('Conversation mode').props.accessibilityState).toMatchObject({ disabled: true });
  });
});

describe('the ear on a spoken message', () => {
  const message = (source: 'typed' | 'hands_free') =>
    ({ id: 1, conversationId: 1, role: 'user', content: 'hello', createdAt: 0, status: 'complete', source, model: null, toolsUsed: [], errorMessage: null }) as never;
  it('shows only for hands-free messages', async () => {
    const { rerender } = await render(<MessageBubble message={message('hands_free')} theme={theme} now={0} actions={null} onRetry={() => {}} />);
    expect(screen.getByLabelText('Said in conversation mode')).toBeTruthy();
    await rerender(<MessageBubble message={message('typed')} theme={theme} now={0} actions={null} onRetry={() => {}} />);
    expect(screen.queryByLabelText('Said in conversation mode')).toBeNull();
  });
});

describe('ConversationSettings', () => {
  async function setup() {
    const settings = new SettingsRepository(memoryStore());
    await settings.current();
    const testing = new Store<{ level: number; heard: boolean } | null>(null);
    const conversation = {
      testing,
      notice: new Store(null),
      startTest: jest.fn(async () => testing.set({ level: 0.3, heard: false })),
      stopTest: jest.fn(async () => testing.set(null)),
      dismiss: jest.fn(),
    };
    mockCurrent.services = { settings, conversation };
    return { settings, conversation, testing };
  }

  it('changes the speaking switch and saves it', async () => {
    const { settings } = await setup();
    await render(<ConversationSettings theme={theme} />);
    expect(screen.getByText('Follow-up window: 8 s')).toBeTruthy();
    expect(screen.getByText('Pause length: 1.1 s')).toBeTruthy();
    expect(screen.getByText('Sensitivity: 50%')).toBeTruthy();
    await fireEvent(screen.getByRole('switch'), 'valueChange', false);
    await Promise.resolve();
    expect(settings.snapshot().speakDuringConversation).toBe(false);
  });

  it('tests the microphone: a meter and a check when speech is heard', async () => {
    const { conversation, testing } = await setup();
    await render(<ConversationSettings theme={theme} />);
    await fireEvent.press(screen.getByText('Test microphone'));
    expect(conversation.startTest).toHaveBeenCalled();
    await render(<ConversationSettings theme={theme} />);
    expect(screen.getAllByText('Say something…').length).toBeGreaterThan(0);
    testing.set({ level: 0.7, heard: true });
    await render(<ConversationSettings theme={theme} />);
    expect(screen.getAllByText('✓ Heard you').length).toBeGreaterThan(0);
    await fireEvent.press(screen.getAllByText('Stop test')[0]);
    expect(conversation.stopTest).toHaveBeenCalled();
  });
});
