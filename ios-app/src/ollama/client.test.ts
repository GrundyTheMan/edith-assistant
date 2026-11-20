import { AppError } from '../core/errors';
import { fakeFetch, jsonResponse, ndjson, streamResponse } from '../testing/fakeHttp';
import { OllamaClient } from './client';
import type { ChatEvent } from './types';

const BASE = 'http://192.168.4.82:11434';
const call = { model: 'gemma4:e4b-it-q8_0', messages: [{ role: 'user' as const, content: 'hi' }] };

async function collect(gen: AsyncGenerator<ChatEvent>): Promise<ChatEvent[]> {
  const out: ChatEvent[] = [];
  for await (const e of gen) out.push(e);
  return out;
}
const texts = (events: ChatEvent[]) => events.flatMap((e) => (e.type === 'delta' ? [e.text] : []));

describe('chat request', () => {
  it('sends think:false, num_ctx 16384, stream:true, tools when given, and no keep_alive', async () => {
    const { fetch, requests } = fakeFetch(() => streamResponse([ndjson.done()]));
    const client = new OllamaClient({ fetch });
    const tool = { type: 'function' as const, function: { name: 'x', description: 'd', parameters: { type: 'object' } } };
    await collect(client.chatStream(BASE, { ...call, tools: [tool] }));
    const body = JSON.parse(requests[0].body!);
    expect(requests[0].url).toBe(`${BASE}/api/chat`);
    expect(body.think).toBe(false);
    expect(body.options).toEqual({ num_ctx: 16384 });
    expect(body.stream).toBe(true);
    expect(body.tools).toHaveLength(1);
    expect('keep_alive' in body).toBe(false);
  });

  it('omits tools when there are none, and asks gpt-oss for think "low"', async () => {
    const { fetch, requests } = fakeFetch(() => streamResponse([ndjson.done()]));
    const client = new OllamaClient({ fetch });
    await collect(client.chatStream(BASE, { model: 'gpt-oss:20b', messages: call.messages }));
    const body = JSON.parse(requests[0].body!);
    expect(body.think).toBe('low');
    expect('tools' in body).toBe(false);
  });
});

describe('streaming', () => {
  it('turns a text stream into deltas and a done event', async () => {
    const lines = ['The', ' weather', ' in', ' Boston'].map((t) => ndjson.line(t));
    const { fetch } = fakeFetch(() => streamResponse([...lines, ndjson.done()]));
    const events = await collect(new OllamaClient({ fetch }).chatStream(BASE, call));
    expect(texts(events).join('')).toBe('The weather in Boston');
    expect(events[events.length - 1]).toEqual({ type: 'done', stats: { totalMs: 258, evalCount: 29, promptEvalCount: 118 } });
  });

  it('puts a tool call before done, with its arguments as an object', async () => {
    const { fetch } = fakeFetch(() => streamResponse([ndjson.toolCall(), ndjson.done()]));
    const events = await collect(new OllamaClient({ fetch }).chatStream(BASE, call));
    expect(events.map((e) => e.type)).toEqual(['tool_calls', 'done']);
    const e = events[0];
    if (e.type !== 'tool_calls') throw new Error('expected tool_calls');
    expect(e.message.tool_calls![0].function.name).toBe('get_weather');
    expect(e.message.tool_calls![0].function.arguments.location).toBe('Boston');
  });

  it('ignores a thinking block (it has no content)', async () => {
    const thinking = JSON.stringify({ message: { role: 'assistant', content: '', thinking: 'Let me think' }, done: false }) + '\n';
    const { fetch } = fakeFetch(() => streamResponse([thinking, ndjson.line('Hello'), ndjson.done()]));
    const events = await collect(new OllamaClient({ fetch }).chatStream(BASE, call));
    expect(texts(events)).toEqual(['Hello']);
  });

  it('puts together a line split across network chunks', async () => {
    const full = ndjson.line('Hello there') + ndjson.done();
    const cut = 30;
    const { fetch } = fakeFetch(() => streamResponse([full.slice(0, cut), full.slice(cut, 80), full.slice(80)]));
    const events = await collect(new OllamaClient({ fetch }).chatStream(BASE, call));
    expect(texts(events)).toEqual(['Hello there']);
  });

  it('survives a UTF-8 character split between network chunks', async () => {
    const bytes = new TextEncoder().encode(ndjson.line('Caf€ 😀') + ndjson.done());
    const euro = bytes.findIndex((b) => b === 0xe2);
    const { fetch } = fakeFetch(() => streamResponse([bytes.slice(0, euro + 1), bytes.slice(euro + 1, euro + 2), bytes.slice(euro + 2)]));
    const events = await collect(new OllamaClient({ fetch }).chatStream(BASE, call));
    expect(texts(events).join('')).toBe('Caf€ 😀');
  });

  it('reports each network chunk to the probe hook', async () => {
    const sizes: number[] = [];
    const { fetch } = fakeFetch(() => streamResponse([ndjson.line('a'), ndjson.line('b'), ndjson.done()]));
    await collect(new OllamaClient({ fetch }).chatStream(BASE, call, { onRawChunk: (n) => sizes.push(n) }));
    expect(sizes).toHaveLength(3);
  });
});

describe('errors', () => {
  it('maps an error line to a plain error, and 404 model-not-found to ModelNotFound', async () => {
    const { fetch } = fakeFetch(() => jsonResponse({ error: "model 'no-such-model' not found" }, 404));
    await expect(collect(new OllamaClient({ fetch }).chatStream(BASE, { ...call, model: 'no-such-model' }))).rejects.toMatchObject({
      kind: 'model_not_found',
      message: expect.stringContaining('no-such-model'),
    });
    const { fetch: f2 } = fakeFetch(() => streamResponse([ndjson.line('Hi'), JSON.stringify({ error: 'the model crashed' }) + '\n']));
    await expect(collect(new OllamaClient({ fetch: f2 }).chatStream(BASE, call))).rejects.toMatchObject({
      kind: 'http',
      message: expect.stringContaining('the model crashed'),
    });
  });

  it('maps a refused connection to ServerUnreachable', async () => {
    const client = new OllamaClient({
      fetch: async () => {
        throw new TypeError('Network request failed');
      },
    });
    await expect(collect(client.chatStream(BASE, call))).rejects.toMatchObject({ kind: 'server_unreachable' });
  });

  it('maps a line that is not JSON to MalformedResponse', async () => {
    const { fetch } = fakeFetch(() => streamResponse(['this is not json\n']));
    await expect(collect(new OllamaClient({ fetch }).chatStream(BASE, call))).rejects.toMatchObject({ kind: 'malformed' });
  });

  it('treats a stream that ends without "done" as a lost connection', async () => {
    const { fetch } = fakeFetch(() => streamResponse([ndjson.line('Hello')]));
    await expect(collect(new OllamaClient({ fetch }).chatStream(BASE, call))).rejects.toMatchObject({ kind: 'server_unreachable' });
  });
});

describe('cancel and timeout', () => {
  it('aborts the request when the caller cancels, and throws an AbortError', async () => {
    let requestSignal: AbortSignal | undefined;
    const never = new Promise<void>(() => undefined);
    const { fetch } = fakeFetch((_req, signal) => {
      requestSignal = signal;
      return streamResponse([ndjson.line('Hi')], 200, never, signal);
    });
    const ctl = new AbortController();
    const gen = new OllamaClient({ fetch }).chatStream(BASE, call, { signal: ctl.signal });
    const first = await gen.next();
    expect(first.value).toEqual({ type: 'delta', text: 'Hi' });
    const pending = gen.next();
    ctl.abort();
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    expect(requestSignal!.aborted).toBe(true);
  });

  it('times out when nothing arrives', async () => {
    const fetch = (_url: string, init: { signal?: AbortSignal }) =>
      new Promise<never>((_, reject) => init.signal?.addEventListener('abort', () => reject(new Error('aborted by timer'))));
    const client = new OllamaClient({ fetch, headerTimeoutMs: 20 });
    await expect(collect(client.chatStream(BASE, call))).rejects.toMatchObject({ kind: 'timeout' });
  });
});

describe('other endpoints', () => {
  it('parses a non-streamed reply with a tool call (arguments of every JSON type)', async () => {
    const args = { location: 'Boston', days: 3, metric: true, nested: { a: [1, 'x'] } };
    const reply = { message: { role: 'assistant', content: '', tool_calls: [{ id: 'c1', function: { index: 0, name: 'f', arguments: args } }] }, done: true };
    const { fetch } = fakeFetch(() => jsonResponse(reply));
    const message = await new OllamaClient({ fetch }).chat(BASE, call);
    expect(message.tool_calls![0].function.arguments).toEqual(args);
  });

  it('lists models, reads capabilities and the version', async () => {
    const { fetch, requests } = fakeFetch((req) =>
      req.url.endsWith('/api/tags')
        ? jsonResponse({ models: [{ name: 'a' }, { name: 'b' }] })
        : req.url.endsWith('/api/show')
          ? jsonResponse({ capabilities: ['completion', 'tools'] })
          : jsonResponse({ version: '0.40.16' }),
    );
    const client = new OllamaClient({ fetch });
    expect((await client.listModels(BASE)).map((m) => m.name)).toEqual(['a', 'b']);
    expect((await client.show(BASE, 'a')).capabilities).toEqual(['completion', 'tools']);
    expect(await client.version(BASE)).toBe('0.40.16');
    expect(requests.map((r) => r.method)).toEqual(['GET', 'POST', 'GET']);
  });

  it('turns a failed quick call into a plain error', async () => {
    const client = new OllamaClient({
      fetch: async () => {
        throw new TypeError('Network request failed');
      },
    });
    const e = await client.version(BASE).catch((x: unknown) => x);
    expect(e).toBeInstanceOf(AppError);
    expect((e as AppError).kind).toBe('server_unreachable');
  });
});
