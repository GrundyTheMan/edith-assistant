import { fireEvent, render, screen } from '@testing-library/react-native';
import { CurrentConversation } from '../chat/currentConversation';
import { ChatEngine } from '../chat/engine';
import { PromptBuilder, attachmentsSection, dateTimeSection } from '../chat/promptBuilder';
import { ChatRepository, type StoredMessage } from '../data/chatRepository';
import { ModelCatalog } from '../ollama/catalog';
import { OllamaClient } from '../ollama/client';
import { ModelSelection } from '../settings/modelSelection';
import { SettingsRepository, memoryStore } from '../settings/settings';
import { fakeFetch, jsonResponse, ndjson, streamResponse, type RecordedRequest } from '../testing/fakeHttp';
import { FakeClock, migratedDb } from '../testing/nodeSqlite';
import { MessageBubble } from '../ui/ChatComponents';
import { themes } from '../ui/theme';
import { ok, type Tool, type ToolImage } from '../tools/framework/tool';
import { ToolRegistry } from '../tools/framework/toolRegistry';
import { MAX_IMAGE_BASE64, MAX_TURN_IMAGES, runToolTurn } from '../tools/framework/toolRunner';
import { AttachmentStore, type AttachmentFiles, type PendingAttachment } from './attachments';
import { applyRetention, countBeyondKeep, deleteAllAttachments, removeOrphans } from './retention';

const BASE = 'http://192.168.4.82:11434';
const clock = new FakeClock();

class MemoryFiles implements AttachmentFiles {
  readonly disk = new Map<number, string>();
  write = async (id: number, b64: string) => void this.disk.set(id, b64);
  read = async (id: number) => this.disk.get(id) ?? null;
  delete = async (id: number) => void this.disk.delete(id);
  list = async () => [...this.disk.keys()];
}
const shot = (n = 1): ToolImage => ({ fileName: `door-${n}.jpg`, mimeType: 'image/jpeg', base64: `UElD${n}` });
const cameraTool = (images: () => ToolImage[]): Tool => ({
  spec: { name: 'camera_snapshot', description: 'picture', parameters: [{ name: 'device', type: 'string', description: 'd' }] },
  statusLabel: 'Getting a picture…',
  displayName: 'Camera',
  execute: async () => ok('Showing the door, just now.', images()),
});
const owner = (name = 'p.jpg'): PendingAttachment => ({ localId: name, kind: 'image', fileName: name, mimeType: 'image/jpeg', base64: 'T1dORVI=', sizeBytes: 5, width: 1, height: 1 });

const toolCall = () =>
  streamResponse([
    JSON.stringify({ model: 'm', message: { role: 'assistant', content: '', tool_calls: [{ function: { name: 'camera_snapshot', arguments: { device: 'door' } } }] }, done: false }) + '\n',
    ndjson.done(),
  ]);
const text = (t: string) => streamResponse([ndjson.line(t), ndjson.done()]);

describe('pictures from tools in the tool loop', () => {
  const run = async (caps: string[], images: ToolImage[]) => {
    let i = 0;
    const { fetch, requests } = fakeFetch(() => [toolCall(), text('Here you go.')][Math.min(i++, 1)]);
    const out = await runToolTurn({
      client: new OllamaClient({ fetch }),
      baseUrl: BASE,
      model: { name: 'm', capabilities: ['completion', 'tools', ...caps] },
      messages: [{ role: 'user', content: 'show me the door' }],
      registry: new ToolRegistry([cameraTool(() => images)]),
      toolContext: { clock },
      hooks: { onDelta: () => {}, onTextReset: () => {}, onStatus: () => {} },
    });
    const second = JSON.parse(requests[1].body!).messages as { role: string; content: string; images?: string[] }[];
    return { out, tool: second.find((m) => m.role === 'tool')! };
  };

  it('gives the picture to a model that can see, and returns it with the turn', async () => {
    const { out, tool } = await run(['vision'], [shot()]);
    expect(out.images).toEqual([shot()]);
    expect(tool.images).toEqual(['UElD1']);
    expect(tool.content).toBe('Showing the door, just now.');
  });

  it("tells a model that can't see that the owner saw it, and sends no picture", async () => {
    const { out, tool } = await run([], [shot()]);
    expect(out.images).toHaveLength(1);
    expect(tool.images).toBeUndefined();
    expect(tool.content).toContain('shown to the owner; you cannot see it');
  });

  it('keeps at most four pictures a turn and drops one that is too large, saying so', async () => {
    const many = await run(['vision'], [1, 2, 3, 4, 5, 6].map((n) => shot(n)));
    expect(many.out.images).toHaveLength(MAX_TURN_IMAGES);
    expect(many.tool.images).toHaveLength(MAX_TURN_IMAGES);
    expect(many.tool.content).toContain('Only the first pictures could be shown');
    const big = await run(['vision'], [{ ...shot(), base64: 'A'.repeat(MAX_IMAGE_BASE64 + 1) }]);
    expect(big.out.images).toEqual([]);
    expect(big.tool.images).toBeUndefined();
    expect(big.tool.content).toContain('too large');
  });
});

async function setup(files = new MemoryFiles(), keep = 10, caps = ['completion', 'tools', 'vision']) {
  const requests: RecordedRequest[] = [];
  const { fetch } = fakeFetch((req) => {
    if (req.url.endsWith('/api/tags')) return jsonResponse({ models: [{ name: 'm' }] });
    if (req.url.endsWith('/api/show')) return jsonResponse({ capabilities: caps });
    requests.push(req);
    const sent = (JSON.parse(req.body!) as { messages: { role: string; content: string }[] }).messages;
    const last = sent[sent.length - 1];
    // The first round of a message that asks for the door calls the tool; after the tool's answer, or for anything else, it replies.
    return last.role === 'user' && last.content.includes('door') ? toolCall() : text('Done.');
  });
  const { db } = await migratedDb();
  const repo = new ChatRepository(db, clock);
  const settings = new SettingsRepository(memoryStore());
  await settings.update((s) => ({ ...s, serverUrl: BASE, model: 'm', keepRecentAttachments: keep }));
  const client = new OllamaClient({ fetch });
  const store = new AttachmentStore(files);
  const retention = { repo, store, clock };
  const engine = new ChatEngine({
    repo,
    settings,
    selection: new ModelSelection(settings, new ModelCatalog(client)),
    client,
    prompts: new PromptBuilder(clock, () => [dateTimeSection, attachmentsSection]),
    listeners: [],
    tools: new ToolRegistry([cameraTool(() => [shot(1)])]),
    attachments: store,
    afterAttachmentsSent: async () => void (await applyRetention(retention, settings.snapshot().keepRecentAttachments)),
    throttleMs: 0,
  });
  const chat = await repo.createConversation();
  return { repo, store, engine, chat, files, retention, requests, settings };
}
const bodies = (requests: RecordedRequest[]) => requests.map((r) => JSON.parse(r.body!) as { messages: { role: string; content: string; images?: string[] }[] });

describe("Edith's pictures in the engine", () => {
  it("stores a tool's picture as Edith's attachment, numbered after the owner's, with its bytes on disk", async () => {
    const f = await setup();
    await f.engine.send(f.chat, 'here is my list', 'typed', [owner()]);
    await f.engine.send(f.chat, 'show me the door');
    const rows = await f.repo.attachments(f.chat);
    expect(rows.map((r) => [r.number, r.direction, r.kind])).toEqual([
      [1, 'from_user', 'image'],
      [2, 'from_edith', 'image'],
    ]);
    const edith = rows[1];
    expect(edith.fileName).toBe('door-1.jpg');
    expect(f.store.image(edith.id)).toBe('UElD1');
    expect(f.files.disk.get(edith.id)).toBe('UElD1');
    const reply = (await f.repo.messages(f.chat)).find((m) => m.role === 'assistant' && m.id === edith.messageId) as StoredMessage;
    expect(reply.content).toBe('Done.');
  });

  it('labels her picture in later requests, and sends it to a model that can see (within the media window)', async () => {
    const f = await setup();
    await f.engine.send(f.chat, 'show me the door');
    await f.engine.send(f.chat, 'what colour was it?');
    const last = bodies(f.requests).at(-1)!;
    const assistant = last.messages.find((m) => m.role === 'assistant')!;
    expect(assistant.content).toBe('Done.\n[Attachment 1: door-1.jpg (image)]');
    expect(assistant.images).toEqual(['UElD1']);
  });

  it('after the file is gone, the label says so and no picture is sent', async () => {
    const f = await setup(new MemoryFiles(), 0);
    await f.engine.send(f.chat, 'show me the door');
    expect(await applyRetention(f.retention, 0, true)).toBe(1); // 0 is applied at the next start
    await f.engine.send(f.chat, 'what colour was it?');
    const assistant = bodies(f.requests).at(-1)!.messages.find((m) => m.role === 'assistant')!;
    expect(assistant.content).toContain('— no longer available');
    expect(assistant.images).toBeUndefined();
  });

  it('keeps the newest N of each direction separately, across chats', async () => {
    const f = await setup(new MemoryFiles(), 3);
    const other = await f.repo.createConversation();
    for (let i = 0; i < 5; i++) await f.engine.send(i % 2 ? other : f.chat, 'show me the door');
    for (let i = 0; i < 4; i++) await f.engine.send(f.chat, `photo ${i}`, 'typed', [owner(`o${i}.jpg`)]);
    expect((await f.repo.keptAttachments('from_edith')).length).toBe(3);
    expect((await f.repo.keptAttachments('from_user')).length).toBe(3);
    expect(await countBeyondKeep(f.repo, 1)).toBe(4); // 2 + 2 more would go if the number fell to 1
  });

  it('lowering the number removes both kinds; delete all clears both; orphans of both directions are kept apart', async () => {
    const f = await setup();
    await f.engine.send(f.chat, 'show me the door');
    await f.engine.send(f.chat, 'mine', 'typed', [owner()]);
    expect(await removeOrphans(f.retention)).toBe(0);
    f.files.disk.set(9999, 'stray');
    expect(await removeOrphans(f.retention)).toBe(1);
    expect(await deleteAllAttachments(f.retention)).toBe(2);
    expect(f.files.disk.size).toBe(0);
  });

  it('Regenerate removes the old reply\'s picture and its file', async () => {
    const f = await setup();
    await f.engine.send(f.chat, 'show me the door');
    const [row] = await f.repo.keptAttachments('from_edith');
    expect(f.files.disk.has(row.id)).toBe(true);
    await f.engine.regenerateLast(f.chat);
    expect(f.files.disk.has(row.id)).toBe(false);
    expect((await f.repo.keptAttachments('from_edith')).length).toBe(1); // the new reply made a new picture
    expect((await f.repo.keptAttachments('from_edith'))[0].id).not.toBe(row.id);
  });

  it("deleting a chat deletes Edith's picture files too", async () => {
    const f = await setup();
    const current = new CurrentConversation(f.repo, f.settings, f.engine, (ids) => f.store.forget(ids));
    await f.engine.send(f.chat, 'show me the door');
    await f.repo.createConversation();
    await current.delete(f.chat);
    expect(f.files.disk.size).toBe(0);
  });
});

describe("Edith's pictures in the bubble", () => {
  const message: StoredMessage = {
    id: 9,
    conversationId: 1,
    role: 'assistant',
    content: 'Here is the door.',
    createdAt: 0,
    status: 'complete',
    source: 'typed',
    model: 'm',
    toolsUsed: ['camera_snapshot'],
    errorMessage: null,
  };
  it('shows the picture above her words and opens it, or says it is gone', async () => {
    const onOpen = jest.fn();
    await render(
      <MessageBubble message={message} theme={themes.light} now={0} actions={null} onRetry={() => {}} onOpenImage={onOpen} attachments={[{ id: 1, fileName: 'door.jpg', base64: 'UElD1' }]} />,
    );
    expect(screen.getByText('Here is the door.')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Open door.jpg'));
    expect(onOpen).toHaveBeenCalledWith('UElD1');
  });
  it('says "no longer available" for a removed picture', async () => {
    await render(<MessageBubble message={message} theme={themes.light} now={0} actions={null} onRetry={() => {}} attachments={[{ id: 1, fileName: 'door.jpg', base64: null }]} />);
    expect(screen.getByText('door.jpg — no longer available')).toBeTruthy();
  });
});
