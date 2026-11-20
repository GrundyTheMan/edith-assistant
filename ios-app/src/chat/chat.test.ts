import { AppError } from '../core/errors';
import type { StoredMessage } from '../data/chatRepository';
import { ChatRepository } from '../data/chatRepository';
import { ModelCatalog } from '../ollama/catalog';
import { OllamaClient, type HttpResponse } from '../ollama/client';
import { ModelSelection } from '../settings/modelSelection';
import { SettingsRepository, memoryStore } from '../settings/settings';
import { fakeFetch, jsonResponse, ndjson, streamResponse, type RecordedRequest } from '../testing/fakeHttp';
import { FakeClock, migratedDb } from '../testing/nodeSqlite';
import { CONTEXT_BUDGET_TOKENS, buildContext, estimateTokens } from './contextBuilder';
import { CurrentConversation } from './currentConversation';
import { ChatEngine, type EngineState, type ReplyListener } from './engine';
import { PERSONA } from './persona';
import { PromptBuilder, dateTimeSection, noToolsSection } from './promptBuilder';
import { ConversationTitler } from './titler';

const BASE = 'http://192.168.4.82:11434';
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

describe('PromptBuilder', () => {
  it("has Edith's persona verbatim, then the date line with the zone and UTC offset", () => {
    const clock = new FakeClock(Date.UTC(2026, 9, 29, 18, 30), 'America/Detroit', -240);
    const system = new PromptBuilder(clock, () => [dateTimeSection]).system({ name: 'm', capabilities: [] });
    expect(system.startsWith(PERSONA)).toBe(true);
    expect(system).toContain('You are Edith, a highly sophisticated, warm, and genuinely complimentary personal assistant.');
    expect(system.endsWith('\n\nCurrent date and time: Thursday, October 29, 2026, 2:30 PM (America/Detroit, UTC-04:00).')).toBe(true);
  });

  it('shows the winter offset after daylight saving ends (04-lessons L29), and omits null sections', () => {
    const clock = new FakeClock(Date.UTC(2026, 10, 2, 5, 5), 'America/Detroit', -300);
    const system = new PromptBuilder(clock, () => [dateTimeSection, { text: () => null }, noToolsSection]).system({ name: 'm', capabilities: [] });
    expect(system).toContain('Monday, November 2, 2026, 12:05 AM (America/Detroit, UTC-05:00).');
    expect(system).toContain("You have no tools in this chat");
    expect(new PromptBuilder(clock, () => [{ text: () => null }]).system({ name: 'm', capabilities: [] })).toBe(PERSONA);
  });

  it('writes half-hour and positive offsets, and 12 AM / 12 PM correctly', () => {
    const clock = new FakeClock(Date.UTC(2026, 5, 1, 6, 30), 'Asia/Kolkata', 330);
    expect(new PromptBuilder(clock, () => [dateTimeSection]).system({ name: 'm', capabilities: [] })).toContain(
      'Monday, June 1, 2026, 12:00 PM (Asia/Kolkata, UTC+05:30).',
    );
  });
});

describe('buildContext', () => {
  it('starts with the system prompt, keeps only complete user and assistant messages', () => {
    const out = buildContext(
      [
        msg({ id: 1, content: 'hello' }),
        msg({ id: 2, role: 'assistant', content: 'hi' }),
        msg({ id: 3, content: 'a failed one' }),
        msg({ id: 4, role: 'assistant', content: '', status: 'failed', errorMessage: 'Edith took too long to answer. Try again.' }),
        msg({ id: 5, role: 'assistant', content: 'partial', status: 'cancelled' }),
        msg({ id: 6, content: 'weather?' }),
      ].map((m) => (m.id === 3 ? { ...m, status: 'failed' as const } : m)),
      'SYSTEM',
    );
    expect(out).toEqual([
      { role: 'system', content: 'SYSTEM' },
      { role: 'user', content: 'hello' },
      { role: 'assistant', content: 'hi' },
      { role: 'user', content: 'weather?' },
    ]);
  });

  it('strips <tool_call> text, an unclosed one too, and drops a message left empty', () => {
    const out = buildContext(
      [
        msg({ id: 1, content: 'q' }),
        msg({ id: 2, role: 'assistant', content: 'Checking <tool_call>get_weather{x}</tool_call> done' }),
        msg({ id: 3, content: 'q2' }),
        msg({ id: 4, role: 'assistant', content: '<tool_call>get_weather{location:' }),
        msg({ id: 5, content: 'q3' }),
      ],
      'S',
    );
    expect(out.map((m) => m.content)).toEqual(['S', 'q', 'Checking  done', 'q2', 'q3']);
  });

  it('never starts the kept window with an assistant message', () => {
    const out = buildContext([msg({ id: 1, role: 'assistant', content: 'welcome' }), msg({ id: 2, content: 'hi' })], 'S');
    expect(out.map((m) => m.role)).toEqual(['system', 'user']);
  });

  it('keeps the newest messages that fit the 10,000-token budget', () => {
    const big = 'a'.repeat(Math.floor(CONTEXT_BUDGET_TOKENS * 3.5 * 0.4)); // about 4,000 tokens
    const out = buildContext(
      [msg({ id: 1, content: 'oldest ' + big }), msg({ id: 2, role: 'assistant', content: 'old ' + big }), msg({ id: 3, content: 'new ' + big }), msg({ id: 4, role: 'assistant', content: 'newer ' + big }), msg({ id: 5, content: 'latest' })],
      'S',
    );
    // oldest and old don't fit; the window is [new, newer, latest]
    expect(out.map((m) => m.content.split(' ')[0])).toEqual(['S', 'new', 'newer', 'latest']);
    expect(estimateTokens(out.slice(1).map((m) => m.content).join(''))).toBeLessThanOrEqual(CONTEXT_BUDGET_TOKENS);
  });

  it('truncates the middle of a huge latest user message, keeping its start and end', () => {
    const huge = 'START ' + 'x'.repeat(100_000) + ' END';
    const out = buildContext([msg({ id: 1, content: 'earlier' }), msg({ id: 2, content: huge })], 'S');
    expect(out).toHaveLength(2);
    const text = out[1].content;
    expect(text.startsWith('START')).toBe(true);
    expect(text.endsWith('END')).toBe(true);
    expect(text).toContain('[…]');
    expect(estimateTokens(text)).toBeLessThanOrEqual(CONTEXT_BUDGET_TOKENS);
  });
});

// --- the engine, on a real SQLite and a fake network --------------------------------------------------------------------

interface Fixture {
  engine: ChatEngine;
  repo: ChatRepository;
  settings: SettingsRepository;
  current: CurrentConversation;
  requests: RecordedRequest[];
  listener: jest.Mocked<Required<ReplyListener>>;
  states: EngineState[];
  setChat: (r: (req: RecordedRequest, signal?: AbortSignal) => HttpResponse | Promise<HttpResponse>) => void;
  selection: ModelSelection;
  client: OllamaClient;
}

const MODELS = [
  { name: 'gemma4:12b-it-q8_0', caps: ['completion', 'tools', 'vision', 'audio', 'thinking'] },
  { name: 'nomic-embed-text:latest', caps: ['embedding'] },
];

async function fixture(chat: (req: RecordedRequest, signal?: AbortSignal) => HttpResponse | Promise<HttpResponse>): Promise<Fixture> {
  let chatHandler = chat;
  const { fetch, requests } = fakeFetch((req, signal) => {
    if (req.url.endsWith('/api/tags')) return jsonResponse({ models: MODELS.map((m) => ({ name: m.name })) });
    if (req.url.endsWith('/api/show')) {
      const model = JSON.parse(req.body!).model as string;
      return jsonResponse({ capabilities: MODELS.find((m) => m.name === model)?.caps ?? [] });
    }
    return chatHandler(req, signal);
  });
  const { db } = await migratedDb();
  const clock = new FakeClock();
  const repo = new ChatRepository(db, clock);
  const settings = new SettingsRepository(memoryStore());
  await settings.update((s) => ({ ...s, serverUrl: BASE }));
  const client = new OllamaClient({ fetch });
  const catalog = new ModelCatalog(client);
  const selection = new ModelSelection(settings, catalog);
  const listener = { onDelta: jest.fn(), onFinished: jest.fn(), onTextReset: jest.fn(), onCancelled: jest.fn(), onFailed: jest.fn() };
  const engine = new ChatEngine({
    repo,
    settings,
    selection,
    client,
    prompts: new PromptBuilder(clock, () => [dateTimeSection, noToolsSection]),
    listeners: [listener],
    throttleMs: 0,
  });
  const states: EngineState[] = [];
  engine.state.subscribe(() => states.push(engine.state.get()));
  const current = new CurrentConversation(repo, settings, engine);
  return { engine, repo, settings, current, requests, listener, states, setChat: (r) => void (chatHandler = r), selection, client };
}

const reply = (...parts: string[]) => streamResponse([...parts.map((p) => ndjson.line(p)), ndjson.done()]);
const chatRequests = (f: Fixture) => f.requests.filter((r) => r.url.endsWith('/api/chat'));

describe('ChatEngine', () => {
  it('send: stores the user message, streams a growing reply, stores Edith\'s reply, then idles', async () => {
    const f = await fixture(() => reply('Hello', ' there', '!'));
    const id = await f.repo.createConversation();
    await f.engine.send(id, 'Hi Edith');
    const partials = f.states.flatMap((s) => (s.kind === 'working' ? [s.partialText] : []));
    expect(partials).toEqual(expect.arrayContaining(['', 'Hello', 'Hello there', 'Hello there!']));
    expect(f.engine.state.get()).toEqual({ kind: 'idle' });
    const stored = await f.repo.messages(id);
    expect(stored.map((m) => [m.role, m.content, m.status])).toEqual([
      ['user', 'Hi Edith', 'complete'],
      ['assistant', 'Hello there!', 'complete'],
    ]);
    expect(stored[1].model).toBe('gemma4:12b-it-q8_0');
    expect(f.listener.onDelta).toHaveBeenCalledTimes(3);
    expect(f.listener.onFinished).toHaveBeenCalledWith(id, 'Hello there!', stored[1].id);
    expect((await f.settings.current()).model).toBe('gemma4:12b-it-q8_0');
  });

  it('sends the persona, the date line and the history, with think:false', async () => {
    const f = await fixture(() => reply('ok'));
    const id = await f.repo.createConversation();
    await f.engine.send(id, 'first');
    await f.engine.send(id, 'second');
    const body = JSON.parse(chatRequests(f)[1].body!);
    expect(body.model).toBe('gemma4:12b-it-q8_0');
    expect(body.think).toBe(false);
    expect(body.messages.map((m: { role: string }) => m.role)).toEqual(['system', 'user', 'assistant', 'user']);
    expect(body.messages[0].content).toContain('Current date and time:');
    expect(body.messages[0].content.startsWith(PERSONA)).toBe(true);
    expect(body.messages[3].content).toBe('second');
  });

  it('ignores a blank message and a send while a turn is running', async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const f = await fixture(() => streamResponse([ndjson.line('slow')], 200, gate.then(() => undefined)));
    const id = await f.repo.createConversation();
    await f.engine.send(id, '   ');
    expect(await f.repo.messages(id)).toHaveLength(0);
    // The streamed reply never gets a "done" line, so the first turn is held open.
    const first = f.engine.send(id, 'one');
    void f.engine.send(id, 'two'); // refused: a turn is running
    expect(f.engine.isBusy).toBe(true);
    release();
    await first;
    expect((await f.repo.messages(id)).filter((m) => m.role === 'user').map((m) => m.content)).toEqual(['one']);
  });

  it('server unreachable: a Failed state and a failed row; the next send clears it and the failed row is not sent as context', async () => {
    let up = false;
    const f = await fixture(() => {
      if (!up) throw new TypeError('Network request failed');
      return reply('Back online');
    });
    const id = await f.repo.createConversation();
    await f.engine.send(id, 'hello?');
    expect(f.engine.state.get()).toMatchObject({ kind: 'failed', conversationId: id });
    const failed = (await f.repo.messages(id))[1];
    expect(failed).toMatchObject({ role: 'assistant', status: 'failed', content: '' });
    expect(failed.errorMessage).toContain("Can't reach your Ollama server");

    up = true;
    await f.engine.send(id, 'try again');
    expect(f.engine.state.get()).toEqual({ kind: 'idle' });
    const body = JSON.parse(chatRequests(f)[chatRequests(f).length - 1].body!);
    expect(body.messages.map((m: { content: string }) => m.content).slice(1)).toEqual(['hello?', 'try again']);
    expect(JSON.stringify(body)).not.toContain("Can't reach");
  });

  it('cancel mid-stream: keeps the partial text as a cancelled message and aborts the request', async () => {
    let signal: AbortSignal | undefined;
    const never = new Promise<void>(() => undefined);
    const f = await fixture((_req, s) => {
      signal = s;
      return streamResponse([ndjson.line('Once upon')], 200, never, s);
    });
    const id = await f.repo.createConversation();
    const turn = f.engine.send(id, 'tell me a story');
    for (let i = 0; i < 50 && f.listener.onDelta.mock.calls.length === 0; i++) await new Promise((r) => setTimeout(r, 5));
    f.engine.cancel();
    await turn;
    expect(signal!.aborted).toBe(true);
    const last = (await f.repo.messages(id))[1];
    expect(last).toMatchObject({ role: 'assistant', status: 'cancelled', content: 'Once upon' });
    expect(f.engine.state.get()).toEqual({ kind: 'idle' });
    expect(f.listener.onCancelled).toHaveBeenCalledWith(id);
  });

  it('regenerate: replaces the last reply, without sending the old one', async () => {
    let n = 0;
    const f = await fixture(() => reply(n++ === 0 ? 'first answer' : 'second answer'));
    const id = await f.repo.createConversation();
    await f.engine.send(id, 'question');
    await f.engine.regenerateLast(id);
    expect((await f.repo.messages(id)).map((m) => m.content)).toEqual(['question', 'second answer']);
    const body = JSON.parse(chatRequests(f)[1].body!);
    expect(body.messages.map((m: { content: string }) => m.content).slice(1)).toEqual(['question']);
  });

  it('regenerate does nothing when the newest message is the owner\'s', async () => {
    const f = await fixture(() => reply('x'));
    const id = await f.repo.createConversation();
    await f.repo.addUserMessage(id, 'unanswered');
    await f.engine.regenerateLast(id);
    expect(chatRequests(f)).toHaveLength(0);
    expect(f.engine.isBusy).toBe(false);
  });

  it('retry after a failure replaces the failed row', async () => {
    let up = false;
    const f = await fixture(() => (up ? reply('Fine now') : jsonResponse({ error: 'boom' }, 500)));
    const id = await f.repo.createConversation();
    await f.engine.send(id, 'hello');
    expect((await f.repo.messages(id))[1].status).toBe('failed');
    up = true;
    await f.engine.regenerateLast(id);
    expect((await f.repo.messages(id)).map((m) => [m.role, m.status, m.content])).toEqual([
      ['user', 'complete', 'hello'],
      ['assistant', 'complete', 'Fine now'],
    ]);
  });

  it('a blank reply becomes a failed row, not an empty bubble', async () => {
    const f = await fixture(() => streamResponse([ndjson.line('   '), ndjson.done()]));
    const id = await f.repo.createConversation();
    await f.engine.send(id, 'hello');
    const last = (await f.repo.messages(id))[1];
    expect(last).toMatchObject({ status: 'failed', content: '', errorMessage: "Edith didn't reply. Try again." });
    expect(f.engine.state.get()).toMatchObject({ kind: 'failed' });
  });

  it('a send made the moment a reply finishes is accepted (the turn is released before Idle is published)', async () => {
    const f = await fixture(() => reply('done'));
    const id = await f.repo.createConversation();
    let second: Promise<void> | undefined;
    f.engine.state.subscribe(() => {
      if (f.engine.state.get().kind === 'idle' && !second && f.listener.onFinished.mock.calls.length === 0) second = f.engine.send(id, 'second');
    });
    await f.engine.send(id, 'first');
    await second;
    expect((await f.repo.messages(id)).filter((m) => m.role === 'user')).toHaveLength(2);
  });

  it('a turn started in chat A finishes in A after the owner selects B, and state names its chat', async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const f = await fixture(() => ({
      ...streamResponse([ndjson.line('Reply for A')]),
      body: {
        getReader: (() => {
          const parts = [new TextEncoder().encode(ndjson.line('Reply for A')), new TextEncoder().encode(ndjson.done())];
          let i = 0;
          return () => ({
            read: async () => {
              if (i === 1) await gate;
              return i < parts.length ? { done: false, value: parts[i++] } : { done: true };
            },
          });
        })(),
      },
    }));
    const a = await f.repo.createConversation();
    const b = await f.repo.createConversation();
    await f.current.select(a);
    const turn = f.engine.send(a, 'hi A');
    for (let i = 0; i < 50 && f.engine.state.get().kind !== 'working'; i++) await new Promise((r) => setTimeout(r, 5));
    await f.current.select(b);
    expect(f.engine.state.get()).toMatchObject({ kind: 'working', conversationId: a });
    expect(f.engine.activeConversationId).toBe(a);
    await f.engine.send(b, 'while A runs'); // one turn at a time across all chats
    release();
    await turn;
    expect((await f.repo.messages(a)).map((m) => m.content)).toEqual(['hi A', 'Reply for A']);
    expect(await f.repo.messages(b)).toHaveLength(0);
  });

  it('cancelAndJoin then delete: no crash, nothing left behind', async () => {
    const never = new Promise<void>(() => undefined);
    const f = await fixture((_req, s) => streamResponse([ndjson.line('partial')], 200, never, s));
    const a = await f.repo.createConversation();
    await f.current.select(a);
    const turn = f.engine.send(a, 'hello');
    for (let i = 0; i < 50 && f.listener.onDelta.mock.calls.length === 0; i++) await new Promise((r) => setTimeout(r, 5));
    await f.current.delete(a);
    await turn;
    // The cancelled reply was stored first, then the chat (and with it every message) was deleted. SQLite may hand the
    // freed id to the new empty chat (C06b As built), so check for what was in it, not for the id.
    expect(await f.repo.messages(a)).toEqual([]);
    expect(f.engine.isBusy).toBe(false);
    expect(await f.repo.isEmpty(f.current.id.get()!)).toBe(true);
  });

  it('an unknown model on the server stays a plain error', async () => {
    const f = await fixture(() => jsonResponse({ error: "model 'gemma4:12b-it-q8_0' not found" }, 404));
    const id = await f.repo.createConversation();
    await f.engine.send(id, 'hello');
    const s = f.engine.state.get();
    expect(s.kind === 'failed' && s.error).toBeInstanceOf(AppError);
    expect(s.kind === 'failed' && s.error.kind).toBe('model_not_found');
  });
});

describe('ModelSelection', () => {
  it('picks gemma4:12b first, then any gemma4, then a model with tools, then the first; hides embeddings', async () => {
    const f = await fixture(() => reply('x'));
    const chosen = await f.selection.ensureModel();
    expect(chosen.name).toBe('gemma4:12b-it-q8_0');
  });

  it('switches from a model that is no longer installed, with a one-time notice (04-lessons L21)', async () => {
    const f = await fixture(() => reply('x'));
    await f.settings.update((s) => ({ ...s, model: 'old-model:latest' }));
    const chosen = await f.selection.ensureModel();
    expect(chosen.name).toBe('gemma4:12b-it-q8_0');
    expect(f.selection.takeNotice()).toBe('Your model old-model:latest is no longer installed; switched to gemma4:12b-it-q8_0.');
    expect(f.selection.takeNotice()).toBeNull();
    expect((await f.settings.current()).model).toBe('gemma4:12b-it-q8_0');
  });

  it('keeps an installed saved model', async () => {
    const f = await fixture(() => reply('x'));
    await f.settings.update((s) => ({ ...s, model: 'gemma4:12b-it-q8_0' }));
    expect((await f.selection.ensureModel()).name).toBe('gemma4:12b-it-q8_0');
    expect(f.selection.takeNotice()).toBeNull();
  });

  it('no chat models at all is its own error; a down server stays a server error', async () => {
    const empty = new OllamaClient({ fetch: async () => jsonResponse({ models: [] }) });
    const settings = new SettingsRepository(memoryStore());
    await expect(new ModelSelection(settings, new ModelCatalog(empty)).ensureModel()).rejects.toMatchObject({ kind: 'no_chat_models' });
    const down = new OllamaClient({
      fetch: async () => {
        throw new TypeError('Network request failed');
      },
    });
    await expect(new ModelSelection(settings, new ModelCatalog(down)).ensureModel()).rejects.toMatchObject({ kind: 'server_unreachable' });
  });
});

describe('ModelCatalog', () => {
  it('keeps a model whose show failed, hides embedding models, and caches capabilities', async () => {
    let shows = 0;
    const { fetch } = fakeFetch((req) => {
      if (req.url.endsWith('/api/tags')) return jsonResponse({ models: [{ name: 'a' }, { name: 'b' }, { name: 'nomic-embed-text' }] });
      shows++;
      const model = JSON.parse(req.body!).model;
      if (model === 'b') return jsonResponse({ error: 'boom' }, 500);
      return jsonResponse({ capabilities: model === 'a' ? ['completion', 'tools'] : ['embedding'] });
    });
    const catalog = new ModelCatalog(new OllamaClient({ fetch }));
    const first = await catalog.chatModels(BASE);
    expect(first).toEqual([
      { name: 'a', capabilities: ['completion', 'tools'] },
      { name: 'b', capabilities: [] },
    ]);
    const before = shows;
    await catalog.chatModels(BASE);
    expect(shows - before).toBe(1); // only `b` (failed, so not cached) is asked again
    catalog.invalidate();
    await catalog.chatModels(BASE);
    expect(shows - before).toBe(1 + 3);
  });
});

describe('CurrentConversation', () => {
  it('restores the saved chat, falls back when it was deleted, reuses an empty chat, and prunes empty chats at startup', async () => {
    const f = await fixture(() => reply('x'));
    const a = await f.repo.createConversation();
    await f.repo.addUserMessage(a, 'keep me');
    const b = await f.repo.createConversation();
    await f.repo.addUserMessage(b, 'newer');
    const emptyOne = await f.repo.createConversation();
    await f.settings.update((s) => ({ ...s, currentConversationId: a }));
    expect(await f.current.resolve()).toBe(a);
    expect(await f.repo.conversation(emptyOne)).toBeNull(); // pruned

    await f.repo.deleteConversation(a);
    const second = new CurrentConversation(f.repo, f.settings, f.engine);
    expect(await second.resolve()).toBe(b); // the saved chat is gone: the most recent remaining one

    const reused = await second.newChat();
    expect(reused).not.toBe(b);
    expect(await second.newChat()).toBe(reused); // the current chat is already empty: reuse it
  });

  it('creates the first chat on a fresh install, and remembers the selection', async () => {
    const f = await fixture(() => reply('x'));
    const id = await f.current.resolve();
    expect(f.current.id.get()).toBe(id);
    expect((await f.settings.current()).currentConversationId).toBe(id);
  });

  it('deleting the open chat opens the next most recent one', async () => {
    const f = await fixture(() => reply('x'));
    const a = await f.repo.createConversation();
    await f.repo.addUserMessage(a, 'a');
    const b = await f.repo.createConversation();
    await f.repo.addUserMessage(b, 'b');
    await f.current.select(b);
    await f.current.delete(b);
    expect(f.current.id.get()).toBe(a);
  });
});

describe('ConversationTitler', () => {
  async function titled(titleReply: () => HttpResponse) {
    const f = await fixture(() => reply('Sure, here are a few ideas.'));
    f.setChat((req) => {
      const body = JSON.parse(req.body!);
      return body.stream ? reply('Sure, here are a few ideas.') : titleReply();
    });
    const titler = new ConversationTitler(f.repo, f.client, f.settings);
    return { f, titler };
  }

  it("asks the model for a short title once, cleans it, and doesn't overwrite an owner rename", async () => {
    const { f, titler } = await titled(() => jsonResponse({ message: { role: 'assistant', content: '"Golden Retriever Puppy Names."' }, done: true }));
    const id = await f.repo.createConversation();
    await f.engine.send(id, 'Name ideas for a golden retriever puppy?');
    await titler.title(id, 'Sure, here are a few ideas.');
    expect((await f.repo.conversation(id))).toMatchObject({ title: 'Golden Retriever Puppy Names', titleSource: 'edith' });
    const titleCalls = chatRequests(f).filter((r) => !JSON.parse(r.body!).stream);
    expect(titleCalls).toHaveLength(1);
    await titler.title(id, 'again');
    expect(chatRequests(f).filter((r) => !JSON.parse(r.body!).stream)).toHaveLength(1); // one attempt per chat

    const other = await f.repo.createConversation();
    await f.repo.addUserMessage(other, 'hello');
    await f.repo.rename(other, 'My own title');
    await titler.title(other, 'hi');
    expect((await f.repo.conversation(other))?.title).toBe('My own title');
  });

  it('keeps the first-message title when the title call fails', async () => {
    const { f, titler } = await titled(() => jsonResponse({ error: 'boom' }, 500));
    const id = await f.repo.createConversation();
    await f.engine.send(id, 'Name ideas for a golden retriever puppy?');
    await titler.title(id, 'Sure');
    expect((await f.repo.conversation(id))).toMatchObject({ title: 'Name ideas for a golden retriever puppy?', titleSource: 'first_message' });
  });
});
