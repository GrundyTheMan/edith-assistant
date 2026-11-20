import { CurrentConversation } from '../chat/currentConversation';
import { ChatEngine } from '../chat/engine';
import { PromptBuilder, attachmentsSection, dateTimeSection } from '../chat/promptBuilder';
import { ChatRepository } from '../data/chatRepository';
import { ModelCatalog } from '../ollama/catalog';
import { OllamaClient } from '../ollama/client';
import { ModelSelection } from '../settings/modelSelection';
import { SettingsRepository, memoryStore } from '../settings/settings';
import { fakeFetch, jsonResponse, ndjson, streamResponse, type RecordedRequest } from '../testing/fakeHttp';
import { FakeClock, migratedDb } from '../testing/nodeSqlite';
import { AttachmentStore, type AttachmentFiles, type PendingAttachment } from './attachments';
import { applyRetention, deleteAllAttachments, removeOrphans, usageText } from './retention';

const BASE = 'http://192.168.4.82:11434';
const clock = new FakeClock();

class MemoryFiles implements AttachmentFiles {
  readonly disk = new Map<number, string>();
  failWrites = false;
  write = async (id: number, b64: string) => {
    if (this.failWrites) throw new Error('disk full');
    this.disk.set(id, b64);
  };
  read = async (id: number) => this.disk.get(id) ?? null;
  delete = async (id: number) => void this.disk.delete(id);
  list = async () => [...this.disk.keys()];
}

const photo = (name = 'p.jpg', b64 = 'Zm9v'): PendingAttachment => ({
  localId: name,
  kind: 'image',
  fileName: name,
  mimeType: 'image/jpeg',
  base64: b64,
  sizeBytes: 3,
  width: 10,
  height: 10,
});

async function setup(files = new MemoryFiles(), keep = 10) {
  const requests: RecordedRequest[] = [];
  const { fetch } = fakeFetch((req) => {
    requests.push(req);
    if (req.url.endsWith('/api/tags')) return jsonResponse({ models: [{ name: 'm' }] });
    if (req.url.endsWith('/api/show')) return jsonResponse({ capabilities: ['completion', 'vision'] });
    return streamResponse([ndjson.line('Seen.'), ndjson.done()]);
  });
  const { db } = await migratedDb();
  const repo = new ChatRepository(db, clock);
  const settings = new SettingsRepository(memoryStore());
  await settings.update((s) => ({ ...s, serverUrl: BASE, keepRecentAttachments: keep }));
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
    attachments: store,
    afterAttachmentsSent: async () => void (await applyRetention(retention, settings.snapshot().keepRecentAttachments)),
    throttleMs: 0,
  });
  const chat = await repo.createConversation();
  return { repo, store, engine, chat, files, retention, settings, requests, db, client };
}
const chatBodies = (requests: RecordedRequest[]) => requests.filter((r) => r.url.endsWith('/api/chat')).map((r) => JSON.parse(r.body!));

describe('the picture store on disk', () => {
  it('writes a sent picture to a file and loads it again in a new run of the app', async () => {
    const f = await setup();
    await f.engine.send(f.chat, 'look', 'typed', [photo('a.jpg', 'QUJD')]);
    const [row] = await f.repo.attachments(f.chat);
    expect(f.files.disk.get(row.id)).toBe('QUJD');
    const reopened = new AttachmentStore(f.files); // a restart: memory is empty
    expect(reopened.image(row.id)).toBeNull();
    await reopened.hydrate([row.id]);
    expect(reopened.image(row.id)).toBe('QUJD');
    expect(reopened.version.get()).toBe(1);
  });

  it('a full disk keeps the picture for this run only, without failing the send', async () => {
    const files = new MemoryFiles();
    files.failWrites = true;
    const f = await setup(files);
    await f.engine.send(f.chat, 'look', 'typed', [photo()]);
    const [row] = await f.repo.attachments(f.chat);
    expect(f.store.image(row.id)).toBe('Zm9v');
    expect(files.disk.size).toBe(0);
    expect((await f.repo.messages(f.chat)).map((m) => m.role)).toEqual(['user', 'assistant']);
  });

  it('after a restart the model still gets the picture from an earlier message', async () => {
    const f = await setup();
    await f.engine.send(f.chat, 'first', 'typed', [photo('a.jpg', 'QUJD')]);
    // new run: same database and files, empty memory
    const store2 = new AttachmentStore(f.files);
    const engine2 = new ChatEngine({
      repo: f.repo,
      settings: f.settings,
      selection: new ModelSelection(f.settings, new ModelCatalog(f.client)),
      client: f.client,
      prompts: new PromptBuilder(clock, () => [dateTimeSection, attachmentsSection]),
      listeners: [],
      attachments: store2,
      throttleMs: 0,
    });
    await engine2.send(f.chat, 'and what colour was it?');
    const second = chatBodies(f.requests).at(-1);
    const firstUser = second.messages.find((m: { role: string; content: string }) => m.role === 'user' && m.content.startsWith('first'));
    expect(firstUser.images).toEqual(['QUJD']);
  });
});

describe('retention', () => {
  it('keeps the newest 10 pictures: older files go and their rows say removed', async () => {
    const f = await setup();
    for (let i = 1; i <= 12; i++) await f.engine.send(f.chat, `m${i}`, 'typed', [photo(`p${i}.jpg`)]);
    const rows = await f.repo.attachments(f.chat);
    expect(rows).toHaveLength(12);
    expect(rows.filter((r) => r.removedAt !== null).map((r) => r.number)).toEqual([1, 2]);
    expect(f.files.disk.size).toBe(10);
    expect(f.store.image(rows[0].id)).toBeNull();
    expect(f.store.image(rows[11].id)).toBe('Zm9v');
  });

  it('counts across all chats', async () => {
    const f = await setup(new MemoryFiles(), 2);
    const other = await f.repo.createConversation();
    await f.engine.send(f.chat, 'a', 'typed', [photo()]);
    await f.engine.send(other, 'b', 'typed', [photo()]);
    await f.engine.send(f.chat, 'c', 'typed', [photo()]);
    expect((await f.repo.keptAttachments('from_user')).map((a) => a.number)).toEqual([3, 2]);
  });

  it('with 0 a picture just sent stays visible, and goes at the next start', async () => {
    const f = await setup(new MemoryFiles(), 0);
    await f.engine.send(f.chat, 'a', 'typed', [photo()]);
    expect((await f.repo.keptAttachments('from_user')).length).toBe(1);
    expect(await applyRetention(f.retention, 0, true)).toBe(1);
    expect((await f.repo.keptAttachments('from_user')).length).toBe(0);
    expect(f.files.disk.size).toBe(0);
  });

  it('lowering the number removes the older ones; raising it removes nothing', async () => {
    const f = await setup();
    for (let i = 1; i <= 5; i++) await f.engine.send(f.chat, `m${i}`, 'typed', [photo()]);
    expect(await applyRetention(f.retention, 20)).toBe(0);
    expect(await applyRetention(f.retention, 3)).toBe(2);
    expect((await f.repo.keptAttachments('from_user')).map((a) => a.number)).toEqual([5, 4, 3]);
  });

  it('delete all removes every picture now but keeps the chat text', async () => {
    const f = await setup();
    await f.engine.send(f.chat, 'hello', 'typed', [photo(), photo('b.jpg')]);
    expect(await deleteAllAttachments(f.retention)).toBe(2);
    expect(f.files.disk.size).toBe(0);
    expect((await f.repo.messages(f.chat))[0].content).toBe('hello');
  });

  it('startup deletes files no kept row points to', async () => {
    const f = await setup();
    await f.engine.send(f.chat, 'a', 'typed', [photo()]);
    f.files.disk.set(9999, 'stray');
    expect(await removeOrphans(f.retention)).toBe(1);
    expect([...f.files.disk.keys()]).toHaveLength(1);
  });

  it("deleting a chat deletes its pictures' files", async () => {
    const f = await setup();
    const current = new CurrentConversation(f.repo, f.settings, f.engine, (ids) => f.store.forget(ids));
    await f.engine.send(f.chat, 'a', 'typed', [photo(), photo('b.jpg')]);
    await f.repo.createConversation();
    await current.delete(f.chat);
    expect(f.files.disk.size).toBe(0);
  });
});

describe('settings and text', () => {
  it('remembers the number, defaults to 10, and clamps what it reads', async () => {
    const store = memoryStore({ keep_recent_attachments: '999' });
    expect((await new SettingsRepository(store).current()).keepRecentAttachments).toBe(50);
    expect((await new SettingsRepository(memoryStore()).current()).keepRecentAttachments).toBe(10);
    const repo = new SettingsRepository(memoryStore());
    await repo.update((s) => ({ ...s, keepRecentAttachments: 4 }));
    expect(repo.snapshot().keepRecentAttachments).toBe(4);
  });
  it('writes the usage line', () => {
    expect(usageText(0, 0)).toBe('No photos kept');
    expect(usageText(1, 3)).toBe('1 photo, <0.1 MB');
    expect(usageText(12, 8_400_000)).toBe('12 photos, 8.4 MB');
  });
});
