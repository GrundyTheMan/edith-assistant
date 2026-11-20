import { fireEvent, render, screen } from '@testing-library/react-native';
import { ActionRow, InFlightBubble, InputRow, MessageBubble } from './ChatComponents';
import { MarkdownText } from './MarkdownText';
import { parseInline, parseMarkdown, stripMarkdown } from './markdown';
import { listTime, messageTime } from './messageTimes';
import { themes } from './theme';
import type { StoredMessage } from '../data/chatRepository';

const theme = themes.light;
const stored = (over: Partial<StoredMessage> = {}): StoredMessage => ({
  id: 1,
  conversationId: 1,
  role: 'assistant',
  content: 'Hello **there**',
  createdAt: new Date(2026, 10, 19, 14, 6).getTime(),
  status: 'complete',
  source: 'typed',
  model: 'm',
  toolsUsed: [],
  errorMessage: null,
  ...over,
});
const NOW = new Date(2026, 10, 19, 20, 0).getTime();

describe('Markdown parser', () => {
  it('parses blocks: headings, bullets, numbered lists, quotes, code fences and paragraphs', () => {
    const blocks = parseMarkdown('# Plan\nIntro line\n\n- one\n- two\n1. first\n2) second\n> quoted\n```\ncode here\n```');
    expect(blocks.map((b) => b.type)).toEqual(['heading', 'paragraph', 'bullet', 'bullet', 'numbered', 'numbered', 'quote', 'code']);
    expect(blocks[7]).toEqual({ type: 'code', text: 'code here' });
  });

  it('parses inline bold, italics, code and links, and leaves unrecognised text as written', () => {
    expect(parseInline('a **b** *c* `d` [e](https://x.io/y)')).toEqual([
      { type: 'text', text: 'a ' },
      { type: 'bold', children: [{ type: 'text', text: 'b' }] },
      { type: 'text', text: ' ' },
      { type: 'italic', children: [{ type: 'text', text: 'c' }] },
      { type: 'text', text: ' ' },
      { type: 'code', text: 'd' },
      { type: 'text', text: ' ' },
      { type: 'link', text: 'e', url: 'https://x.io/y' },
    ]);
    expect(parseInline('2 * 3 * 4 and snake_case_name and **unclosed')).toEqual([{ type: 'text', text: '2 * 3 * 4 and snake_case_name and **unclosed' }]);
    expect(parseInline('see https://example.com/a.')).toEqual([
      { type: 'text', text: 'see ' },
      { type: 'link', text: 'https://example.com/a', url: 'https://example.com/a' },
      { type: 'text', text: '.' },
    ]);
  });

  it('strips markers for the list preview', () => {
    expect(stripMarkdown('# Hi\n**Bold** and - not a list\n- item')).toBe('Hi Bold and - not a list item');
  });
});

describe('times', () => {
  it('shows a clock time today, a weekday this week, and a date before that', () => {
    expect(messageTime(new Date(2026, 10, 19, 14, 6).getTime(), NOW)).toBe('2:06 PM');
    expect(messageTime(new Date(2026, 10, 19, 0, 5).getTime(), NOW)).toBe('12:05 AM');
    expect(messageTime(new Date(2026, 10, 16, 9, 30).getTime(), NOW)).toBe('Mon 9:30 AM');
    expect(messageTime(new Date(2026, 9, 12, 12, 0).getTime(), NOW)).toBe('Oct 12, 12:00 PM');
    expect(listTime(new Date(2026, 10, 19, 14, 6).getTime(), NOW)).toBe('2:06 PM');
    expect(listTime(new Date(2026, 10, 16, 9, 30).getTime(), NOW)).toBe('Mon');
    expect(listTime(new Date(2026, 9, 12, 12, 0).getTime(), NOW)).toBe('Oct 12');
  });
});

describe('chat components', () => {
  it('renders Edith\'s Markdown and the timestamp, with Copy, Play and Stop and Regenerate on the newest reply', async () => {
    const onPlay = jest.fn();
    const onRegenerate = jest.fn();
    await render(
      <MessageBubble
        message={stored()}
        theme={theme}
        now={NOW}
        onRetry={jest.fn()}
        actions={{ speaking: false, canRegenerate: true, onPlay, onStop: jest.fn(), onRegenerate }}
      />,
    );
    expect(screen.getByText('there')).toBeTruthy();
    expect(screen.getByText('2:06 PM')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Play reply'));
    await fireEvent.press(screen.getByLabelText('Regenerate reply'));
    expect(onPlay).toHaveBeenCalledTimes(1);
    expect(onRegenerate).toHaveBeenCalledTimes(1);
    expect(screen.getByLabelText('Stop playback').props.accessibilityState?.disabled).toBe(true);
  });

  it('has no Regenerate on an older reply, and Stop playback is enabled while that message speaks', async () => {
    await render(<ActionRow message={stored()} speaking canRegenerate={false} onPlay={jest.fn()} onStop={jest.fn()} onRegenerate={jest.fn()} theme={theme} />);
    expect(screen.queryByLabelText('Regenerate reply')).toBeNull();
    expect(screen.getByLabelText('Stop playback').props.accessibilityState?.disabled).toBeFalsy();
  });

  it('a failed reply shows its error and a working Retry; a stopped one is marked "(stopped)"', async () => {
    const onRetry = jest.fn();
    const { unmount } = await render(
      <MessageBubble message={stored({ status: 'failed', content: '', errorMessage: 'Edith took too long to answer. Try again.' })} theme={theme} now={NOW} onRetry={onRetry} actions={null} />,
    );
    expect(screen.getByText('Edith took too long to answer. Try again.')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Retry'));
    expect(onRetry).toHaveBeenCalledTimes(1);
    await unmount();
    await render(<MessageBubble message={stored({ status: 'cancelled', content: 'Half a sent' })} theme={theme} now={NOW} onRetry={jest.fn()} actions={null} />);
    expect(screen.getByText('(stopped)')).toBeTruthy();
  });

  it("the owner's message is a user bubble with its time", async () => {
    await render(<MessageBubble message={stored({ role: 'user', content: 'Hi Edith' })} theme={theme} now={NOW} onRetry={jest.fn()} actions={null} />);
    expect(screen.getByText('Hi Edith')).toBeTruthy();
    expect(screen.getByText('2:06 PM')).toBeTruthy();
  });

  it('the in-flight bubble shows the status label before any text, then the text, and Stop calls cancel', async () => {
    const onStop = jest.fn();
    const { rerender } = await render(<InFlightBubble text="" label="Edith is thinking…" canStop onStop={onStop} theme={theme} />);
    expect(screen.getByText('Edith is thinking…')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Stop reply'));
    expect(onStop).toHaveBeenCalledTimes(1);
    await rerender(<InFlightBubble text="Streaming now" label="" canStop onStop={onStop} theme={theme} />);
    expect(screen.getByText('Streaming now')).toBeTruthy();
    await rerender(<InFlightBubble text="Done" label="" canStop={false} onStop={onStop} theme={theme} />);
    expect(screen.queryByLabelText('Stop reply')).toBeNull();
  });

  it('the input row sends only when allowed, and shows the busy-elsewhere hint', async () => {
    const onSend = jest.fn();
    const onChange = jest.fn();
    const { rerender } = await render(<InputRow value="" onChange={onChange} canSend={false} hint="Edith is replying in another chat" onSend={onSend} theme={theme} />);
    expect(screen.getByText('Edith is replying in another chat')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Send'));
    expect(onSend).not.toHaveBeenCalled();
    await fireEvent.changeText(screen.getByLabelText('Message Edith'), 'hi');
    expect(onChange).toHaveBeenCalledWith('hi');
    await rerender(<InputRow value="hi" onChange={onChange} canSend hint={null} onSend={onSend} theme={theme} />);
    await fireEvent.press(screen.getByLabelText('Send'));
    expect(onSend).toHaveBeenCalledTimes(1);
  });

  it('renders in the dark theme too', async () => {
    await render(<MarkdownText text={'- a\n- b'} color={themes.dark.onEdithBubble} theme={themes.dark} />);
    expect(screen.getAllByText('•')).toHaveLength(2);
  });
});
