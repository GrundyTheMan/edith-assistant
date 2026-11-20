import { fireEvent, render, screen } from '@testing-library/react-native';
import { buildContext, type MediaContext } from '../chat/contextBuilder';
import { ChatEngine } from '../chat/engine';
import { PromptBuilder, attachmentsSection, dateTimeSection } from '../chat/promptBuilder';
import { ChatRepository, type StoredAttachment, type StoredMessage } from '../data/chatRepository';
import { ModelCatalog } from '../ollama/catalog';
import { OllamaClient } from '../ollama/client';
import { ModelSelection } from '../settings/modelSelection';
import { SettingsRepository, memoryStore } from '../settings/settings';
import { fakeFetch, jsonResponse, ndjson, streamResponse, type RecordedRequest } from '../testing/fakeHttp';
import { FakeClock, migratedDb } from '../testing/nodeSqlite';
import { ImageViewer, PendingChips } from '../ui/AttachmentViews';
import { MessageBubble } from '../ui/ChatComponents';
import { themes } from '../ui/theme';
import {
  AttachmentStore,
  MAX_IMAGE_SIDE,
  base64Bytes,
  prepareImage,
  scaledSize,
  type ImageProcessor,
  type PendingAttachment,
} from './attachments';

const lightTheme = themes.light;
const BASE = 'http://192.168.4.82:11434';
const clock = new FakeClock();

describe('preparing a photo', () => {
  it('shrinks to 1536 px on the longest side, keeps the shape, never enlarges', () => {
    expect(scaledSize(4000, 3000)).toEqual({ width: 1536, height: 1152 });
    expect(scaledSize(3000, 4000)).toEqual({ width: 1152, height: 1536 });
    expect(scaledSize(MAX_IMAGE_SIDE, 900)).toBeNull();
    expect(scaledSize(800, 600)).toBeNull();
    expect(scaledSize(100000, 1)).toEqual({ width: 1536, height: 1 });
  });

  it('asks the processor for a JPEG at that size and names the result .jpg (HEIC and PNG become JPEG)', async () => {
    const toJpeg = jest.fn(async (_uri: string, target: { width: number; height: number } | null) => ({
      base64: 'QUJDRA==',
      width: target?.width ?? 800,
      height: target?.height ?? 600,
    }));
    const processor: ImageProcessor = { toJpeg };
    const big = await prepareImage({ uri: 'file:///a.heic', width: 4000, height: 3000, fileName: 'IMG_0001.HEIC' }, processor);
    expect(toJpeg).toHaveBeenCalledWith('file:///a.heic', { width: 1536, height: 1152 });
    expect(big).toMatchObject({ kind: 'image', fileName: 'IMG_0001.jpg', mimeType: 'image/jpeg', width: 1536, height: 1152, sizeBytes: 4 });
    const small = await prepareImage({ uri: 'file:///b.png', width: 800, height: 600, fileName: null }, processor, 2);
    expect(toJpeg).toHaveBeenLastCalledWith('file:///b.png', null);
    expect(small.fileName).toBe('image-2.jpg');
    expect(small.localId).not.toBe(big.localId);
    await expect(prepareImage({ uri: 'x', width: 1, height: 1 }, { toJpeg: async () => ({ base64: '', width: 1, height: 1 }) })).rejects.toThrow("couldn't be read");
  });

  it('counts Base64 bytes', () => {
    expect(base64Bytes('QUJDRA==')).toBe(4);
    expect(base64Bytes('QUJD')).toBe(3);
    expect(base64Bytes('QUI=')).toBe(2);
  });
});

// --- context ------------------------------------------------------------------------------------------------------

const msg = (over: Partial<StoredMessage>): StoredMessage => ({
  id: 1,
  conversationId: 1,
  role: 'user',
  content: 'x',
  createdAt: 1,
  status: 'complete',
  source: 'typed',
  model: null,
  toolsUsed: [],
  errorMessage: null,
  ...over,
});
const att = (over: Partial<StoredAttachment>): StoredAttachment => ({
  id: 1,
  messageId: 1,
  number: 1,
  direction: 'from_user',
  kind: 'image',
  fileName: 'receipt.jpg',
  mimeType: 'image/jpeg',
  sizeBytes: 10,
  createdAt: 1,
  removedAt: null,
  ...over,
});
const media = (attachments: StoredAttachment[], held: Record<number, string>, canSee = true): MediaContext => ({
  attachments,
  image: (id) => held[id] ?? null,
  canSee,
});

describe('buildContext with attachments', () => {
  it('appends label lines and sends the picture for a model that sees', () => {
    const out = buildContext([msg({ id: 5, content: "What's on this?" })], 'SYS', undefined, media([att({ id: 9, messageId: 5, number: 3 })], { 9: 'B64' }));
    expect(out[1]).toEqual({ role: 'user', content: "What's on this?\n[Attachment 3: receipt.jpg (image)]", images: ['B64'] });
  });

  it('keeps a message of only attachments', () => {
    const out = buildContext([msg({ id: 5, content: '' })], 'SYS', undefined, media([att({ id: 9, messageId: 5 })], { 9: 'B64' }));
    expect(out).toHaveLength(2);
    expect(out[1].content).toBe('[Attachment 1: receipt.jpg (image)]');
    expect(out[1].images).toEqual(['B64']);
  });

  it('sends pictures only for the newest 10 messages; older ones are labels', () => {
    const history: StoredMessage[] = [];
    for (let i = 1; i <= 12; i++) history.push(msg({ id: i, role: i % 2 ? 'user' : 'assistant', content: `m${i}` }));
    const out = buildContext(history, 'SYS', undefined, media([att({ id: 1, messageId: 1, number: 1 }), att({ id: 11, messageId: 11, number: 2 })], { 1: 'OLD', 11: 'NEW' }));
    const old = out.find((m) => m.content.includes('Attachment 1'));
    expect(old?.images).toBeUndefined();
    expect(old?.content).toContain('[Attachment 1: receipt.jpg (image)]');
    expect(out.find((m) => m.content.includes('Attachment 2'))?.images).toEqual(['NEW']);
  });

  it('says "no longer available" when the bytes are gone or the file was removed, and sends only labels to a model that can\'t see', () => {
    const gone = buildContext([msg({ id: 5, content: 'hi' })], 'SYS', undefined, media([att({ id: 9, messageId: 5 })], {}));
    expect(gone[1]).toEqual({ role: 'user', content: 'hi\n[Attachment 1: receipt.jpg (image) — no longer available]' });
    const removed = buildContext([msg({ id: 5, content: 'hi' })], 'SYS', undefined, media([att({ id: 9, messageId: 5, removedAt: 5 })], { 9: 'B64' }));
    expect(removed[1].images).toBeUndefined();
    expect(removed[1].content).toContain('no longer available');
    const blind = buildContext([msg({ id: 5, content: 'hi' })], 'SYS', undefined, media([att({ id: 9, messageId: 5 })], { 9: 'B64' }, false));
    expect(blind[1]).toEqual({ role: 'user', content: 'hi\n[Attachment 1: receipt.jpg (image)]' });
  });

  it('is unchanged without media, and the prompt line appears only for models that see', () => {
    expect(buildContext([msg({ content: 'plain' })], 'SYS')[1]).toEqual({ role: 'user', content: 'plain' });
    const ctx = (capabilities: string[]) => ({ now: 0, zone: 'UTC', offsetMinutes: 0, model: { name: 'm', capabilities } });
    expect(attachmentsSection.text(ctx(['vision']))).toContain('[Attachment 3: receipt.jpg (image)]');
    expect(attachmentsSection.text(ctx(['completion']))).toBeNull();
  });
});

// --- the engine, the repository and the capability gate -------------------------------------------------------------

const pendingImage = (name = 'photo.jpg'): PendingAttachment => ({
  localId: name,
  kind: 'image',
  fileName: name,
  mimeType: 'image/jpeg',
  base64: 'Zm9v',
  sizeBytes: 3,
  width: 10,
  height: 10,
});

async function setup(caps: string[]) {
  const requests: RecordedRequest[] = [];
  const { fetch } = fakeFetch((req) => {
    requests.push(req);
    if (req.url.endsWith('/api/tags')) return jsonResponse({ models: [{ name: 'gemma4:12b-it-q8_0' }] });
    if (req.url.endsWith('/api/show')) return jsonResponse({ capabilities: caps });
    return streamResponse([ndjson.line('A receipt.'), ndjson.done()]);
  });
  const { db } = await migratedDb();
  const repo = new ChatRepository(db, clock);
  const settings = new SettingsRepository(memoryStore());
  await settings.update((s) => ({ ...s, serverUrl: BASE }));
  const client = new OllamaClient({ fetch });
  const store = new AttachmentStore();
  const engine = new ChatEngine({
    repo,
    settings,
    selection: new ModelSelection(settings, new ModelCatalog(client)),
    client,
    prompts: new PromptBuilder(clock, () => [dateTimeSection, attachmentsSection]),
    listeners: [],
    attachments: store,
    throttleMs: 0,
  });
  const chat = await repo.createConversation();
  const chats = () => requests.filter((r) => r.url.endsWith('/api/chat'));
  return { repo, engine, store, chat, chats };
}

describe('ChatEngine with attachments', () => {
  it('stores numbered rows, holds the bytes, and sends the picture with its label', async () => {
    const { repo, engine, store, chat, chats } = await setup(['completion', 'vision']);
    await engine.send(chat, 'What is this?', 'typed', [pendingImage('a.jpg'), pendingImage('b.jpg')]);
    const rows = await repo.attachments(chat);
    expect(rows.map((r) => [r.number, r.fileName, r.kind, r.direction])).toEqual([
      [1, 'a.jpg', 'image', 'from_user'],
      [2, 'b.jpg', 'image', 'from_user'],
    ]);
    expect(store.image(rows[0].id)).toBe('Zm9v');
    const body = JSON.parse(chats()[0].body!);
    const user = body.messages.find((m: { role: string }) => m.role === 'user');
    expect(user.content).toBe('What is this?\n[Attachment 1: a.jpg (image)]\n[Attachment 2: b.jpg (image)]');
    expect(user.images).toEqual(['Zm9v', 'Zm9v']);
    expect((await repo.messages(chat)).map((m) => m.role)).toEqual(['user', 'assistant']);
  });

  it('numbers attachments across chats', async () => {
    const { repo, engine, chat } = await setup(['completion', 'vision']);
    await engine.send(chat, 'one', 'typed', [pendingImage()]);
    const other = await repo.createConversation();
    await engine.send(other, 'two', 'typed', [pendingImage()]);
    expect((await repo.attachments(other)).map((a) => a.number)).toEqual([2]);
  });

  it('sends a photo with no text', async () => {
    const { repo, engine, chat, chats } = await setup(['completion', 'vision']);
    await engine.send(chat, '', 'typed', [pendingImage()]);
    expect(chats()).toHaveLength(1);
    expect((await repo.messages(chat))[0].content).toBe('');
  });

  it('does not call the model when it can\'t see images, and says which badge to look for', async () => {
    const { repo, engine, chat, chats } = await setup(['completion']);
    await engine.send(chat, 'look', 'typed', [pendingImage()]);
    expect(chats()).toHaveLength(0);
    const failed = (await repo.messages(chat)).at(-1)!;
    expect(failed.status).toBe('failed');
    expect(failed.errorMessage).toBe('gemma4:12b-it-q8_0 can\'t see images — pick a model marked "Sees images" in Settings.');
  });

  it('ignores an empty send', async () => {
    const { engine, chat, chats } = await setup(['completion', 'vision']);
    await engine.send(chat, '  ', 'typed', []);
    expect(chats()).toHaveLength(0);
  });
});

// --- views ----------------------------------------------------------------------------------------------------

describe('attachment views', () => {
  it('shows pending chips with Remove, thumbnails in the bubble that open, and "no longer available" after a restart', async () => {
    const onRemove = jest.fn();
    await render(<PendingChips items={[pendingImage('a.jpg')]} onRemove={onRemove} theme={lightTheme} />);
    await fireEvent.press(screen.getByLabelText('Remove a.jpg'));
    expect(onRemove).toHaveBeenCalledWith('a.jpg');

    const onOpen = jest.fn();
    await render(
      <MessageBubble
        message={msg({ id: 3, content: 'Look' })}
        theme={lightTheme}
        now={1}
        onRetry={jest.fn()}
        actions={null}
        attachments={[
          { id: 1, fileName: 'a.jpg', base64: 'Zm9v' },
          { id: 2, fileName: 'old.jpg', base64: null },
        ]}
        onOpenImage={onOpen}
      />,
    );
    await fireEvent.press(screen.getByLabelText('Open a.jpg'));
    expect(onOpen).toHaveBeenCalledWith('Zm9v');
    expect(screen.getByText('old.jpg — no longer available')).toBeTruthy();
    expect(screen.getByText('Look')).toBeTruthy();
  });

  it('closes the full-screen viewer', async () => {
    const onClose = jest.fn();
    await render(<ImageViewer base64="Zm9v" onClose={onClose} />);
    await fireEvent.press(screen.getByLabelText('Close'));
    expect(onClose).toHaveBeenCalled();
  });
});
