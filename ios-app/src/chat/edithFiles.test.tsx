import { fireEvent, render, screen } from '@testing-library/react-native';
import { AttachmentStore, type AttachmentFiles, type PendingAttachment } from '../attachments/attachments';
import { applyRetention } from '../attachments/retention';
import { base64ToBytes, bytesToBase64, utf8Text } from '../core/bytes';
import { utf8Bytes } from '../core/md5';
import { ChatRepository, type StoredAttachment, type StoredMessage } from '../data/chatRepository';
import { ModelCatalog } from '../ollama/catalog';
import { OllamaClient } from '../ollama/client';
import { ModelSelection } from '../settings/modelSelection';
import { SettingsRepository, memoryStore } from '../settings/settings';
import { cleanForSpeech } from '../speech/cleanForSpeech';
import { SpeechOutput, type TtsEngine } from '../speech/speechOutput';
import { fakeFetch, jsonResponse, ndjson, streamResponse, type RecordedRequest } from '../testing/fakeHttp';
import { FakeClock, migratedDb } from '../testing/nodeSqlite';
import { MessageBubble } from '../ui/ChatComponents';
import { FileViewer } from '../ui/FileCards';
import { themes } from '../ui/theme';
import { buildContext, type MediaContext } from './contextBuilder';
import { ChatEngine } from './engine';
import { PromptBuilder, dateTimeSection } from './promptBuilder';
import { SpeechFilter, displayPartial, edithFilesSection, filePlaceholder, mimeForName, parseReply, plainReply, sanitizeFileName, stripPlaceholders } from './replyFormat';

const BASE = 'http://192.168.4.82:11434';
const clock = new FakeClock();

describe('parsing a reply', () => {
  it('finds text, a file and text again', () => {
    expect(parseReply('Here you go.\n<file name="beach.md">- sunscreen\n- towel</file>\nEnjoy!')).toEqual([
      { kind: 'text', text: 'Here you go.\n' },
      { kind: 'file', name: 'beach.md', content: '- sunscreen\n- towel', closed: true },
      { kind: 'text', text: '\nEnjoy!' },
    ]);
  });
  it('an unclosed file runs to the end of the reply', () => {
    expect(parseReply('Ok <file name="a.txt">line one\nline two')).toEqual([
      { kind: 'text', text: 'Ok ' },
      { kind: 'file', name: 'a.txt', content: 'line one\nline two', closed: false },
    ]);
  });
  it('reads attribute styles, tag case and two files', () => {
    const p = parseReply("<FILE NAME='x.csv'>a,b</File> and <file name=y.json>{}</file>");
    expect(p.filter((x) => x.kind === 'file').map((x) => (x as { name: string }).name)).toEqual(['x.csv', 'y.json']);
  });
  it('finds a resend tag with any spacing, and ignores one without a number', () => {
    expect(parseReply('Here: <attachment number="3"/> done')).toEqual([
      { kind: 'text', text: 'Here: ' },
      { kind: 'resend', number: 3 },
      { kind: 'text', text: ' done' },
    ]);
    expect(parseReply('<attachment  number = "12" />').find((p) => p.kind === 'resend')).toEqual({ kind: 'resend', number: 12 });
    expect(parseReply('<attachment number="x"/>').some((p) => p.kind === 'resend')).toBe(false);
  });
  it('leaves other angle brackets alone', () => {
    expect(parseReply('1 < 2 and <b>bold</b> and <files> and <filesystem x>')).toEqual([{ kind: 'text', text: '1 < 2 and <b>bold</b> and <files> and <filesystem x>' }]);
    expect(parseReply('a <file name="x"')).toEqual([{ kind: 'text', text: 'a <file name="x"' }]);
  });
  it('sanitises names: no path, no odd characters, at most 60, a default', () => {
    expect(sanitizeFileName('../../etc/passwd')).toBe('etc-passwd');
    expect(sanitizeFileName('my list (final).md')).toBe('my-list-final-.md');
    expect(sanitizeFileName(undefined)).toBe('note.md');
    expect(sanitizeFileName('')).toBe('note.md');
    expect(sanitizeFileName('...')).toBe('note.md');
    const long = sanitizeFileName(`${'a'.repeat(100)}.csv`);
    expect(long.length).toBeLessThanOrEqual(60);
    expect(long.endsWith('.csv')).toBe(true);
    expect(parseReply('<file>x</file>').find((p) => p.kind === 'file')).toMatchObject({ name: 'note.md' });
  });
  it('picks a type from the extension', () => {
    expect([mimeForName('a.md'), mimeForName('a.CSV'), mimeForName('a.py'), mimeForName('a'), mimeForName('a.json')]).toEqual(['text/markdown', 'text/csv', 'text/plain', 'text/plain', 'application/json']);
  });
  it('writes the stored text, the partial display and the speakable text', () => {
    expect(plainReply('Hi <file name="a.md">x</file> bye <attachment number="1"/>')).toBe('Hi [Attached file: a.md] bye');
    expect(displayPartial('Hi <file name="a.md">partial text')).toBe('Hi Writing a.md…');
    expect(displayPartial('Hi <file name="a.md">x</file> bye')).toBe('Hi [Attached file: a.md] bye');
    expect(displayPartial('plain')).toBe('plain');
    expect(stripPlaceholders('See this.\n[Attached file: a.md]\n\n\nThanks')).toBe('See this.\n\nThanks');
    expect(cleanForSpeech('Here is your list. [Attached file: beach.md] Enjoy.')).toBe('Here is your list. Enjoy.');
  });
  it('teaches the two conventions', () => {
    const text = edithFilesSection.text({ now: 0, zone: 'UTC', offsetMinutes: 0, model: { name: 'm', capabilities: [] } })!;
    expect(text).toContain('<file name="short-name.ext">');
    expect(text).toContain('<attachment number="3"/>');
  });
});

describe('the speech filter', () => {
  const run = (...pieces: string[]) => {
    const f = new SpeechFilter();
    return pieces.map((p) => f.push(p)).join('') + f.flush();
  };
  it('passes plain text through', () => {
    expect(run('Hello there. ', 'How are you?')).toBe('Hello there. How are you?');
  });
  it('drops a file block and a resend tag, even when tags are split across pieces', () => {
    expect(run('Here it is. <file name="a.md">secret list', ' more secret</file> Enjoy!')).toBe('Here it is.  Enjoy!');
    expect(run('Before <fi', 'le name="a.md">hidden</fi', 'le> after')).toBe('Before  after');
    expect(run('Sending <attachment number', '="3"/> now')).toBe('Sending  now');
    expect(run('x <', 'file name="b.txt">no</file>y')).toBe('x y');
  });
  it('keeps an ordinary "<" and a tag that never became one', () => {
    expect(run('1 <', ' 2')).toBe('1 < 2');
    expect(run('a <b', 'old> b')).toBe('a <bold> b');
    expect(run('end <fi')).toBe('end <fi');
    expect(run('<files> ok')).toBe('<files> ok');
  });
  it('an unclosed file or unfinished tag is never spoken', () => {
    expect(run('Start <file name="a.md">never closes')).toBe('Start ');
    expect(run('Start <file name="a')).toBe('Start ');
  });
  it('a streaming reply is spoken without its files', async () => {
    const spoken: string[] = [];
    const tts: TtsEngine = { speak: (t, _r, h) => (spoken.push(t), h.onDone()), stop: () => {} };
    const settings = new SettingsRepository(memoryStore());
    await settings.update((s) => ({ ...s, speakReplies: true }));
    const speech = new SpeechOutput(tts, settings, () => true);
    for (const d of ['Here is your list. <fi', 'le name="a.md">SECRET', ' STUFF</file> Enjoy it. Bye.']) speech.onDelta(1, d);
    speech.onFinished(1, 'x', 5);
    expect(spoken.join(' ')).toBe('Here is your list. Enjoy it. Bye.');
    expect(spoken.join(' ')).not.toMatch(/SECRET|file/);
  });
});

describe('Base64 both ways', () => {
  it('decodes what it encodes, and UTF-8 text survives', () => {
    for (const text of ['', 'a', 'ab', 'abc', 'héllo wörld 😀 日本語\nline two']) {
      expect(utf8Text(base64ToBytes(bytesToBase64(utf8Bytes(text))))).toBe(text);
    }
    expect(Array.from(base64ToBytes('TWFu'))).toEqual([77, 97, 110]);
    expect(Array.from(base64ToBytes('TQ'))).toEqual([77]); // padding optional
    expect(Array.from(base64ToBytes('TQ=='))).toEqual([77]);
  });
});

class MemoryFiles implements AttachmentFiles {
  readonly disk = new Map<number, string>();
  write = async (id: number, b64: string) => void this.disk.set(id, b64);
  read = async (id: number) => this.disk.get(id) ?? null;
  delete = async (id: number) => void this.disk.delete(id);
  list = async () => [...this.disk.keys()];
}
const owner = (name = 'p.jpg'): PendingAttachment => ({ localId: name, kind: 'image', fileName: name, mimeType: 'image/jpeg', base64: 'T1dORVI=', sizeBytes: 5, width: 1, height: 1 });

async function setup(reply: (sent: { role: string; content: string; images?: string[] }[]) => string, caps = ['completion', 'vision'], keep = 10) {
  const requests: RecordedRequest[] = [];
  const { fetch } = fakeFetch((req) => {
    if (req.url.endsWith('/api/tags')) return jsonResponse({ models: [{ name: 'm' }] });
    if (req.url.endsWith('/api/show')) return jsonResponse({ capabilities: caps });
    requests.push(req);
    const sent = (JSON.parse(req.body!) as { messages: { role: string; content: string; images?: string[] }[] }).messages;
    return streamResponse([ndjson.line(reply(sent)), ndjson.done()]);
  });
  const { db } = await migratedDb();
  const repo = new ChatRepository(db, clock);
  const settings = new SettingsRepository(memoryStore());
  await settings.update((s) => ({ ...s, serverUrl: BASE, model: 'm', keepRecentAttachments: keep }));
  const client = new OllamaClient({ fetch });
  const files = new MemoryFiles();
  const store = new AttachmentStore(files);
  const retention = { repo, store, clock };
  const engine = new ChatEngine({
    repo,
    settings,
    selection: new ModelSelection(settings, new ModelCatalog(client)),
    client,
    prompts: new PromptBuilder(clock, () => [dateTimeSection, edithFilesSection]),
    listeners: [],
    attachments: store,
    afterAttachmentsSent: async () => void (await applyRetention(retention, keep)),
    throttleMs: 0,
  });
  const chat = await repo.createConversation();
  const bodies = () => requests.map((r) => JSON.parse(r.body!).messages as { role: string; content: string; images?: string[] }[]);
  return { repo, store, engine, chat, files, retention, bodies };
}

describe("Edith's files in the engine", () => {
  const LIST = 'Here is your packing list.\n<file name="beach day.md">- sunscreen\n- towel</file>\nHave fun!';
  it('stores a file as her attachment with its text on disk, and the message has a placeholder', async () => {
    const f = await setup(() => LIST);
    await f.engine.send(f.chat, 'make me a packing list');
    const [reply] = (await f.repo.messages(f.chat)).filter((m) => m.role === 'assistant');
    expect(reply.content).toBe('Here is your packing list.\n[Attached file: beach-day.md]\nHave fun!');
    const [row] = await f.repo.attachments(f.chat);
    expect(row).toMatchObject({ direction: 'from_edith', kind: 'file', fileName: 'beach-day.md', mimeType: 'text/markdown', sizeBytes: 19, resendOf: null });
    expect(f.store.fileText(row.id)).toBe('- sunscreen\n- towel');
    expect(utf8Text(base64ToBytes(f.files.disk.get(row.id)!))).toBe('- sunscreen\n- towel');
    expect(filePlaceholder('beach-day.md')).toBe('[Attached file: beach-day.md]');
  });
  it('shows her own file back to the model within the media window, and as a placeholder after it is gone', async () => {
    const f = await setup((sent) => (sent.length === 2 ? LIST : 'Added one.'));
    await f.engine.send(f.chat, 'make me a packing list');
    await f.engine.send(f.chat, 'add a hat');
    const assistant = f.bodies()[1].find((m) => m.role === 'assistant')!;
    expect(assistant.content).toContain('<file name="beach-day.md">\n- sunscreen\n- towel\n</file>');
    expect(assistant.content).not.toContain('[Attached file');
    const [row] = await f.repo.attachments(f.chat);
    await f.store.forget([row.id]);
    await f.repo.markAttachmentsRemoved([row.id], 1);
    await f.engine.send(f.chat, 'and a book');
    const later = f.bodies()[2].find((m) => m.role === 'assistant' && m.content.includes('packing list'))!;
    expect(later.content).toContain('[Attached file: beach-day.md]');
    expect(later.content).not.toContain('sunscreen');
  });
  it('a reply that is only a file is kept, and an empty reply still fails', async () => {
    const only = await setup(() => '<file name="a.txt">x</file>');
    await only.engine.send(only.chat, 'file please');
    expect((await only.repo.messages(only.chat)).at(-1)?.status).toBe('complete');
    const empty = await setup(() => '   ');
    await empty.engine.send(empty.chat, 'hello');
    expect((await empty.repo.messages(empty.chat)).at(-1)?.status).toBe('failed');
  });
  it('two files in one reply get two rows with their own text', async () => {
    const f = await setup(() => 'Two: <file name="a.md">A</file> <file name="b.csv">x,y</file>');
    await f.engine.send(f.chat, 'two files');
    const rows = await f.repo.attachments(f.chat);
    expect(rows.map((r) => [r.fileName, f.store.fileText(r.id)])).toEqual([['a.md', 'A'], ['b.csv', 'x,y']]);
  });
  it('retention counts files with her pictures per direction, and Regenerate removes the old file', async () => {
    const f = await setup(() => '<file name="n.md">hello</file> ok', ['completion'], 2);
    for (let i = 0; i < 3; i++) await f.engine.send(f.chat, `file ${i}`);
    expect((await f.repo.keptAttachments('from_edith')).length).toBe(2);
    const before = await f.repo.keptAttachments('from_edith');
    await f.engine.regenerateLast(f.chat);
    expect(f.files.disk.has(before[0].id)).toBe(false);
  });
  it('a stopped reply with a half-written file keeps a placeholder, not the raw block', async () => {
    const f = await setup(() => 'ok');
    const id = await f.repo.createConversation();
    await f.repo.addCancelledAssistantMessage(id, plainReply('Start <file name="a.md">half'), 'm');
    expect((await f.repo.messages(id))[0].content).toBe('Start [Attached file: a.md]');
  });
});

describe('sending an attachment back', () => {
  it('shows the original again with no file of its own, and does not count it', async () => {
    const f = await setup((sent) => (sent.some((m) => m.content.includes('send it again')) ? 'Here: <attachment number="1"/>' : 'Got it.'));
    await f.engine.send(f.chat, 'my receipt', 'typed', [owner('receipt.jpg')]);
    const original = (await f.repo.attachments(f.chat))[0];
    await f.engine.send(f.chat, 'send it again');
    const rows = await f.repo.attachments(f.chat);
    const resend = rows[1];
    expect(resend).toMatchObject({ direction: 'from_edith', kind: 'image', fileName: 'receipt.jpg', resendOf: original.id });
    expect(resend.number).toBeGreaterThan(original.number);
    expect(f.files.disk.has(resend.id)).toBe(false); // no copy of the bytes
    expect((await f.repo.keptAttachments('from_edith')).length).toBe(0); // not counted
    expect((await f.repo.messages(f.chat)).at(-1)?.content).toBe('Here:');
    expect((await f.repo.attachmentByNumber(original.number))?.id).toBe(original.id); // the number belongs to the original
  });
  it("sends a vision model the original's picture for the resend", async () => {
    const f = await setup((sent) => (sent.some((m) => m.content.includes('send it again')) ? 'Here: <attachment number="1"/>' : 'ok'));
    await f.engine.send(f.chat, 'my receipt', 'typed', [owner('receipt.jpg')]);
    await f.engine.send(f.chat, 'send it again');
    await f.engine.send(f.chat, 'thanks');
    const last = f.bodies().at(-1)!;
    const assistantWithResend = last.find((m) => m.role === 'assistant' && m.content.includes('[Attachment 2: receipt.jpg (image)]'))!;
    expect(assistantWithResend.images).toEqual(['T1dORVI=']);
  });
  it('an unknown or removed number is said in words, and creates no row', async () => {
    const f = await setup(() => 'Sure: <attachment number="99"/> there');
    await f.engine.send(f.chat, 'send attachment 99');
    expect((await f.repo.messages(f.chat)).at(-1)?.content).toBe("Sure: Attachment 99 isn't available. there");
    expect(await f.repo.attachments(f.chat)).toEqual([]);
    const g = await setup((sent) => (sent.some((m) => m.content.includes('again')) ? '<attachment number="1"/>' : 'ok'));
    await g.engine.send(g.chat, 'pic', 'typed', [owner()]);
    const [orig] = await g.repo.attachments(g.chat);
    await g.store.forget([orig.id]);
    await g.repo.markAttachmentsRemoved([orig.id], 1);
    await g.engine.send(g.chat, 'send it again');
    expect((await g.repo.messages(g.chat)).at(-1)?.content).toBe("Attachment 1 isn't available.");
  });
  it('a reply of only a resend tag is kept', async () => {
    const f = await setup((sent) => (sent.some((m) => m.content.includes('again')) ? '<attachment number="1"/>' : 'ok'));
    await f.engine.send(f.chat, 'pic', 'typed', [owner()]);
    await f.engine.send(f.chat, 'again');
    const last = (await f.repo.messages(f.chat)).at(-1) as StoredMessage;
    expect(last.status).toBe('complete');
    expect(last.content).toBe('');
  });
});

describe('the context for files', () => {
  const asst = (over: Partial<StoredAttachment>): StoredAttachment => ({
    id: 5, messageId: 2, number: 3, direction: 'from_edith', kind: 'file', fileName: 'a.md', mimeType: 'text/markdown', sizeBytes: 1, createdAt: 1, removedAt: null, resendOf: null, ...over,
  });
  const history: StoredMessage[] = [
    { id: 1, conversationId: 1, role: 'user', content: 'list', createdAt: 1, status: 'complete', source: 'typed', model: null, toolsUsed: [], errorMessage: null },
    { id: 2, conversationId: 1, role: 'assistant', content: 'Here.\n[Attached file: a.md]', createdAt: 2, status: 'complete', source: 'typed', model: 'm', toolsUsed: [], errorMessage: null },
    { id: 3, conversationId: 1, role: 'user', content: 'thanks', createdAt: 3, status: 'complete', source: 'typed', model: null, toolsUsed: [], errorMessage: null },
  ];
  const media = (att: StoredAttachment[], texts: Record<number, string>): MediaContext => ({ attachments: att, image: () => null, fileText: (id) => texts[id] ?? null, canSee: false });
  it("puts the file back where it was while it is held, and leaves the placeholder otherwise, without a label line", () => {
    const held = buildContext(history, 'sys', undefined, media([asst({})], { 5: 'AAA' }));
    expect(held[2].content).toBe('Here.\n<file name="a.md">\nAAA\n</file>');
    const gone = buildContext(history, 'sys', undefined, media([asst({})], {}));
    expect(gone[2].content).toBe('Here.\n[Attached file: a.md]');
    const removed = buildContext(history, 'sys', undefined, media([asst({ removedAt: 9 })], { 5: 'AAA' }));
    expect(removed[2].content).toBe('Here.\n[Attached file: a.md]');
  });
});

describe('the file card in the bubble', () => {
  const message: StoredMessage = { id: 9, conversationId: 1, role: 'assistant', content: 'Here you go.\n[Attached file: beach.md]', createdAt: 0, status: 'complete', source: 'typed', model: 'm', toolsUsed: [], errorMessage: null };
  const long = 'one\ntwo\nthree\nfour\nfive';
  it('shows the name and the first three lines, hides the placeholder, and offers View, Copy and Share', async () => {
    const onView = jest.fn();
    const onCopy = jest.fn();
    const onShare = jest.fn();
    const file = { id: 1, name: 'beach.md', text: long };
    await render(<MessageBubble message={message} theme={themes.light} now={0} actions={null} onRetry={() => {}} files={[file]} onViewFile={onView} onCopyFile={onCopy} onShareFile={onShare} />);
    expect(screen.getByText('beach.md')).toBeTruthy();
    expect(screen.getByText('one\ntwo\nthree')).toBeTruthy();
    expect(screen.queryByText(/Attached file/)).toBeNull();
    expect(screen.getByText('Here you go.')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('View beach.md'));
    await fireEvent.press(screen.getByLabelText('Copy beach.md'));
    await fireEvent.press(screen.getByLabelText('Share beach.md'));
    expect([onView, onCopy, onShare].map((m) => m.mock.calls[0][0])).toEqual([file, file, file]);
  });
  it('says "no longer kept" for a removed file', async () => {
    await render(<MessageBubble message={message} theme={themes.light} now={0} actions={null} onRetry={() => {}} files={[{ id: 1, name: 'beach.md', text: null }]} />);
    expect(screen.getByText('beach.md — no longer kept')).toBeTruthy();
    expect(screen.queryByLabelText('Share beach.md')).toBeNull();
  });
  it('the viewer shows the whole text and closes', async () => {
    const onClose = jest.fn();
    await render(<FileViewer file={{ id: 1, name: 'beach.md', text: long }} theme={themes.light} onClose={onClose} />);
    expect(screen.getByText(long)).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Close file'));
    expect(onClose).toHaveBeenCalled();
  });
});
