import { AppError } from '../core/errors';
import { FakeClock, migratedDb, openMemoryDb } from '../testing/nodeSqlite';
import { migrate, LATEST_VERSION, MIGRATIONS } from './migrations';
import { ChatRepository, stripToolCalls } from './chatRepository';
import { cleanEdithTitle, firstMessageTitle } from './conversationTitle';

async function repoFixture() {
  const { db, raw } = await migratedDb();
  const clock = new FakeClock();
  return { db, raw, clock, repo: new ChatRepository(db, clock) };
}

describe('migrations', () => {
  it('creates the schema at version 2 on a fresh database, with no conversations', async () => {
    const { raw } = await migratedDb();
    expect((raw.prepare('PRAGMA user_version').get() as { user_version: number }).user_version).toBe(2);
    const tables = (raw.prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").all() as { name: string }[]).map((t) => t.name);
    expect(tables).toEqual(expect.arrayContaining(['attachments', 'conversations', 'messages']));
    expect(raw.prepare('SELECT COUNT(*) AS n FROM conversations').get()).toEqual({ n: 0 });
  });

  it('migrates a real version 1 database: same ids and messages, titles kept, updated_at from the newest message', async () => {
    const { db, raw } = openMemoryDb();
    await db.exec('PRAGMA foreign_keys = ON');
    raw.exec(MIGRATIONS[0].sql);
    raw.exec('PRAGMA user_version = 1');
    raw.exec(`INSERT INTO conversations (id, title, created_at) VALUES (1, 'Edith', 1000), (2, '', 5000)`);
    raw.exec(`INSERT INTO messages (conversation_id, role, content, created_at, status, source)
              VALUES (1, 'user', 'hello', 2000, 'complete', 'typed'), (1, 'assistant', 'hi', 3000, 'complete', 'typed')`);
    raw.exec(`INSERT INTO attachments (message_id, number, direction, kind, file_name, mime_type, size_bytes, created_at)
              VALUES (1, 1, 'from_user', 'image', 'a.png', 'image/png', 10, 2000)`);

    await migrate(db);

    expect((raw.prepare('PRAGMA user_version').get() as { user_version: number }).user_version).toBe(LATEST_VERSION);
    expect(raw.prepare('SELECT id, title, title_source, updated_at FROM conversations ORDER BY id').all()).toEqual([
      { id: 1, title: 'Edith', title_source: 'owner', updated_at: 3000 },
      { id: 2, title: '', title_source: 'none', updated_at: 5000 },
    ]);
    // The danger C06b found: dropping `conversations` would cascade-delete every message. Nothing may be lost.
    expect(raw.prepare('SELECT COUNT(*) AS n FROM messages').get()).toEqual({ n: 2 });
    expect(raw.prepare('SELECT COUNT(*) AS n FROM attachments').get()).toEqual({ n: 1 });
    expect(raw.prepare('PRAGMA foreign_key_check').all()).toEqual([]);
  });

  it('is a no-op when already at the latest version, and refuses a database from the future', async () => {
    const { db, raw } = await migratedDb();
    await migrate(db);
    raw.exec('PRAGMA user_version = 99');
    await expect(migrate(db)).rejects.toThrow(/version 99/);
  });
});

describe('ChatRepository', () => {
  it('keeps two conversations separate and orders messages oldest first', async () => {
    const { repo, clock } = await repoFixture();
    const a = await repo.createConversation();
    const b = await repo.createConversation();
    await repo.addUserMessage(a, 'one');
    clock.advance();
    await repo.addAssistantMessage(a, 'two', 'm');
    clock.advance();
    await repo.addUserMessage(b, 'other');
    expect((await repo.messages(a)).map((m) => m.content)).toEqual(['one', 'two']);
    expect((await repo.messages(b)).map((m) => m.content)).toEqual(['other']);
    expect((await repo.recent(a, 1)).map((m) => m.content)).toEqual(['two']);
    expect((await repo.latest(a))?.content).toBe('two');
  });

  it('bumps updated_at on every insert, so the list sorts by the newest message', async () => {
    const { repo, clock } = await repoFixture();
    const a = await repo.createConversation();
    clock.advance();
    const b = await repo.createConversation();
    expect((await repo.conversations()).map((c) => c.id)).toEqual([b, a]);
    clock.advance();
    await repo.addUserMessage(a, 'hello');
    expect((await repo.conversations()).map((c) => c.id)).toEqual([a, b]);
  });

  it('titles a chat from its first message only, 40 characters at a word boundary, and never over an owner rename', async () => {
    const { repo } = await repoFixture();
    const a = await repo.createConversation();
    await repo.addUserMessage(a, '  What   should I pack for a long weekend in Chicago in March?  ');
    expect((await repo.conversation(a))?.title).toBe('What should I pack for a long weekend in…');
    await repo.addUserMessage(a, 'second message');
    expect((await repo.conversation(a))?.title).toBe('What should I pack for a long weekend in…');
    await repo.rename(a, 'Chicago trip');
    expect(await repo.setTitle(a, 'Packing list', 'edith')).toBe(false);
    expect((await repo.conversation(a))).toMatchObject({ title: 'Chicago trip', titleSource: 'owner' });
  });

  it("lets Edith's title replace a first-message title, but not the other way round", async () => {
    const { repo } = await repoFixture();
    const a = await repo.createConversation();
    await repo.addUserMessage(a, 'hello there');
    expect(await repo.setTitle(a, 'Friendly Greeting', 'edith')).toBe(true);
    expect(await repo.setTitle(a, 'hello there', 'first_message')).toBe(false);
    expect((await repo.conversation(a))?.title).toBe('Friendly Greeting');
  });

  it('inserting into a deleted conversation stores nothing and does not throw', async () => {
    const { repo } = await repoFixture();
    const a = await repo.createConversation();
    await repo.deleteConversation(a);
    expect(await repo.addAssistantMessage(a, 'late reply', 'm')).toBeNull();
    expect(await repo.addUserMessage(a, 'late', 'typed')).toBeNull();
  });

  it('deleting a conversation removes its messages (and their attachments)', async () => {
    const { repo, raw } = await repoFixture();
    const a = await repo.createConversation();
    const id = await repo.addUserMessage(a, 'hi');
    raw.exec(`INSERT INTO attachments (message_id, number, direction, kind, file_name, mime_type, size_bytes, created_at)
              VALUES (${id}, 1, 'from_user', 'file', 'a.txt', 'text/plain', 1, 1)`);
    await repo.deleteConversation(a);
    expect(raw.prepare('SELECT COUNT(*) AS n FROM messages').get()).toEqual({ n: 0 });
    expect(raw.prepare('SELECT COUNT(*) AS n FROM attachments').get()).toEqual({ n: 0 });
  });

  it('prunes empty conversations except the one kept', async () => {
    const { repo } = await repoFixture();
    const keep = await repo.createConversation();
    const full = await repo.createConversation();
    await repo.createConversation();
    await repo.addUserMessage(full, 'hi');
    await repo.deleteEmptyExcept(keep);
    expect((await repo.conversations()).map((c) => c.id).sort()).toEqual([keep, full].sort());
  });

  it('stores enum strings exactly as the Kotlin schema does, and tool names as JSON', async () => {
    const { repo, raw } = await repoFixture();
    const a = await repo.createConversation();
    await repo.addUserMessage(a, 'memo', 'voice_memo');
    await repo.addAssistantMessage(a, 'ok', 'gemma4', ['get_weather']);
    expect(raw.prepare('SELECT role, status, source, tools_used FROM messages ORDER BY id').all()).toEqual([
      { role: 'user', status: 'complete', source: 'voice_memo', tools_used: null },
      { role: 'assistant', status: 'complete', source: 'typed', tools_used: '["get_weather"]' },
    ]);
  });

  it('strips <tool_call> text from a reply (04-lessons L3), even an unclosed one', async () => {
    expect(stripToolCalls('Sure. <tool_call>get_weather{x}</tool_call> Done.')).toBe('Sure.  Done.');
    expect(stripToolCalls('Sure. <tool_call>get_weather{x')).toBe('Sure. ');
    const { repo } = await repoFixture();
    const a = await repo.createConversation();
    await repo.addAssistantMessage(a, '  Hi <tool_call>x</tool_call> there ', 'm');
    expect((await repo.latest(a))?.content).toBe('Hi  there');
  });

  it('stores failed and cancelled replies with their status, and the error text for a failed one', async () => {
    const { repo } = await repoFixture();
    const a = await repo.createConversation();
    await repo.addFailedAssistantMessage(a, 'gemma4', AppError.timeout());
    await repo.addCancelledAssistantMessage(a, 'partial', 'gemma4');
    const [failed, cancelled] = await repo.messages(a);
    expect(failed).toMatchObject({ status: 'failed', content: '', errorMessage: 'Edith took too long to answer. Try again.' });
    expect(cancelled).toMatchObject({ status: 'cancelled', content: 'partial' });
  });

  it('notifies subscribers when something changes', async () => {
    const { repo } = await repoFixture();
    const seen = jest.fn();
    repo.subscribe(seen);
    const a = await repo.createConversation();
    await repo.addUserMessage(a, 'hi');
    expect(seen).toHaveBeenCalledTimes(2);
  });
});

describe('titles', () => {
  it('cuts at a word boundary with an ellipsis, and keeps short text whole', () => {
    expect(firstMessageTitle('Hello')).toBe('Hello');
    expect(firstMessageTitle('a'.repeat(40))).toBe('a'.repeat(40));
    expect(firstMessageTitle('word '.repeat(20))).toBe('word word word word word word word word…');
    expect(firstMessageTitle('x'.repeat(60))).toBe('x'.repeat(40) + '…');
  });

  it("cleans Edith's title: first line, no quotes, markdown, 'Title:' or ending punctuation", () => {
    expect(cleanEdithTitle('"Friendly Greeting."')).toBe('Friendly Greeting');
    expect(cleanEdithTitle('Title: **Packing for Chicago**\nBecause it is cold')).toBe('Packing for Chicago');
    expect(cleanEdithTitle('\n\n  Golden Retriever Puppy Names!  ')).toBe('Golden Retriever Puppy Names');
    expect(cleanEdithTitle('')).toBe('');
    expect(cleanEdithTitle('word '.repeat(30)).length).toBeLessThanOrEqual(60);
  });
});
