import { AppError } from '../core/errors';
import { Utf8StreamDecoder, LineSplitter } from './utf8';
import {
  CONTEXT_LENGTH,
  type ChatCall,
  type ChatEvent,
  type OllamaChatChunk,
  type OllamaMessage,
  type OllamaModelTag,
  type OllamaShow,
  type OllamaToolCall,
} from './types';

/** The slice of `expo/fetch` this client uses, so tests can pass a fake. */
export interface HttpResponse {
  ok: boolean;
  status: number;
  /** Present on real responses; the page reader uses them (C42). */
  headers?: { get(name: string): string | null };
  url?: string;
  text(): Promise<string>;
  body: { getReader(): { read(): Promise<{ done: boolean; value?: Uint8Array }>; cancel?(): Promise<void> } } | null;
}
export type HttpFetch = (
  url: string,
  init: { method: string; headers?: Record<string, string>; body?: string; signal?: AbortSignal; redirect?: 'follow' | 'manual' },
) => Promise<HttpResponse>;

export interface ClientOptions {
  fetch: HttpFetch;
  /** Wait for the first byte of a chat reply; a cold model load can take ~20 s (C06 As built). */
  headerTimeoutMs?: number;
  /** Longest silence between two chunks of a reply. */
  stallTimeoutMs?: number;
  /** Quick calls: tags, show, version. */
  quickTimeoutMs?: number;
}
export interface StreamHooks {
  signal?: AbortSignal;
  /** Called with the size of every network chunk (the Hello Ollama proof records when chunks arrive). */
  onRawChunk?: (bytes: number) => void;
}

/** 03-ollama-reference §2: gemma4 etc. accept `false`; gpt-oss can't switch thinking off and expects a level. */
export function thinkFor(model: string): false | 'low' {
  return model.startsWith('gpt-oss') ? 'low' : false;
}

export function chatRequestBody(call: ChatCall, stream: boolean): string {
  return JSON.stringify({
    model: call.model,
    messages: call.messages,
    stream,
    think: thinkFor(call.model),
    options: { num_ctx: CONTEXT_LENGTH },
    ...(call.tools && call.tools.length > 0 ? { tools: call.tools } : {}),
  });
}

function abortError(): Error {
  const e = new Error('Aborted');
  e.name = 'AbortError';
  return e;
}

export class OllamaClient {
  private readonly fetchFn: HttpFetch;
  private readonly headerTimeoutMs: number;
  private readonly stallTimeoutMs: number;
  private readonly quickTimeoutMs: number;

  constructor(options: ClientOptions) {
    this.fetchFn = options.fetch;
    this.headerTimeoutMs = options.headerTimeoutMs ?? 180_000;
    this.stallTimeoutMs = options.stallTimeoutMs ?? 180_000;
    this.quickTimeoutMs = options.quickTimeoutMs ?? 8_000;
  }

  /** Streams a reply: deltas as they arrive, then tool calls (if any), then done. Cancel with `hooks.signal`. */
  async *chatStream(baseUrl: string, call: ChatCall, hooks: StreamHooks = {}): AsyncGenerator<ChatEvent> {
    const ctl = new AbortController();
    const unlink = link(hooks.signal, ctl);
    let timedOut = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const arm = (ms: number) => {
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => {
        timedOut = true;
        ctl.abort();
      }, ms);
    };
    const mapFailure = (e: unknown): Error => {
      if (hooks.signal?.aborted) return abortError();
      if (timedOut) return AppError.timeout();
      if (e instanceof AppError) return e;
      return AppError.serverUnreachable(baseUrl, e);
    };

    try {
      arm(this.headerTimeoutMs);
      let res: HttpResponse;
      try {
        res = await this.fetchFn(`${baseUrl}/api/chat`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: chatRequestBody(call, true),
          signal: ctl.signal,
        });
      } catch (e) {
        throw mapFailure(e);
      }
      if (!res.ok) throw await httpError(res, call.model);
      if (!res.body) throw AppError.malformed();

      const reader = res.body.getReader();
      const decoder = new Utf8StreamDecoder();
      const lines = new LineSplitter();
      const state: ParseState = { text: '', toolCalls: [], done: false };

      const handle = function* (ls: string[]): Generator<ChatEvent> {
        for (const line of ls) yield* parseLine(line, call.model, state);
      };

      arm(this.stallTimeoutMs);
      while (!state.done) {
        let r: { done: boolean; value?: Uint8Array };
        try {
          r = await reader.read();
        } catch (e) {
          throw mapFailure(e);
        }
        if (r.done) break;
        arm(this.stallTimeoutMs);
        const value = r.value ?? new Uint8Array(0);
        hooks.onRawChunk?.(value.length);
        yield* handle(lines.push(decoder.decode(value)));
      }
      if (!state.done) {
        yield* handle(lines.push(decoder.flush()));
        yield* handle(lines.flush());
      }
      // A reply that ends without Ollama's final "done" line was cut off (the PC went to sleep, say).
      if (!state.done) throw AppError.serverUnreachable(baseUrl);
    } finally {
      if (timer) clearTimeout(timer);
      unlink();
      ctl.abort(); // closes the connection when the caller stops early, so Ollama stops generating
    }
  }

  /** One non-streamed reply (used for titles). */
  async chat(baseUrl: string, call: ChatCall, signal?: AbortSignal): Promise<OllamaMessage> {
    const text = await this.quick(baseUrl, '/api/chat', 'POST', chatRequestBody(call, false), signal, call.model, this.headerTimeoutMs);
    const chunk = parseJson<OllamaChatChunk>(text);
    if (chunk.error) throw errorFromLine(chunk.error, call.model);
    if (!chunk.message) throw AppError.malformed();
    return chunk.message;
  }

  async listModels(baseUrl: string, signal?: AbortSignal): Promise<OllamaModelTag[]> {
    const text = await this.quick(baseUrl, '/api/tags', 'GET', undefined, signal);
    return parseJson<{ models?: OllamaModelTag[] }>(text).models ?? [];
  }

  async show(baseUrl: string, model: string, signal?: AbortSignal): Promise<OllamaShow> {
    const text = await this.quick(baseUrl, '/api/show', 'POST', JSON.stringify({ model }), signal, model);
    return parseJson<OllamaShow>(text);
  }

  async version(baseUrl: string, signal?: AbortSignal): Promise<string> {
    const text = await this.quick(baseUrl, '/api/version', 'GET', undefined, signal);
    const v = parseJson<{ version?: string }>(text).version;
    if (!v) throw AppError.malformed();
    return v;
  }

  private async quick(
    baseUrl: string,
    path: string,
    method: string,
    body: string | undefined,
    signal: AbortSignal | undefined,
    model?: string,
    timeoutMs: number = this.quickTimeoutMs,
  ): Promise<string> {
    const ctl = new AbortController();
    const unlink = link(signal, ctl);
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      ctl.abort();
    }, timeoutMs);
    try {
      let res: HttpResponse;
      try {
        res = await this.fetchFn(`${baseUrl}${path}`, {
          method,
          headers: body ? { 'Content-Type': 'application/json' } : undefined,
          body,
          signal: ctl.signal,
        });
        if (!res.ok) throw await httpError(res, model);
        return await res.text();
      } catch (e) {
        if (signal?.aborted) throw abortError();
        if (timedOut) throw baseUrl && path !== '/api/chat' ? AppError.serverUnreachable(baseUrl, e) : AppError.timeout();
        if (e instanceof AppError) throw e;
        throw AppError.serverUnreachable(baseUrl, e);
      }
    } finally {
      clearTimeout(timer);
      unlink();
    }
  }
}

/** Aborts `ctl` when `signal` aborts. Returns a function that removes the link. */
function link(signal: AbortSignal | undefined, ctl: AbortController): () => void {
  if (!signal) return () => {};
  if (signal.aborted) {
    ctl.abort();
    return () => {};
  }
  const onAbort = () => ctl.abort();
  signal.addEventListener('abort', onAbort);
  return () => signal.removeEventListener('abort', onAbort);
}

interface ParseState {
  text: string;
  toolCalls: OllamaToolCall[];
  done: boolean;
}

function parseJson<T>(text: string): T {
  try {
    return JSON.parse(text) as T;
  } catch {
    throw AppError.malformed();
  }
}

function errorFromLine(message: string, model?: string): AppError {
  if (/not found/i.test(message) && model) return AppError.modelNotFound(model);
  return AppError.http(500, message);
}

async function httpError(res: HttpResponse, model?: string): Promise<AppError> {
  let body = '';
  try {
    body = await res.text();
  } catch {
    // keep the empty body
  }
  let message = body.trim();
  try {
    const parsed = JSON.parse(body) as { error?: string };
    if (parsed.error) message = parsed.error;
  } catch {
    // not JSON: use the raw text
  }
  if (res.status === 404 && /not found/i.test(message) && model) return AppError.modelNotFound(model);
  return AppError.http(res.status, message || 'no details');
}

function* parseLine(line: string, model: string, state: ParseState): Generator<ChatEvent> {
  let chunk: OllamaChatChunk;
  try {
    chunk = JSON.parse(line) as OllamaChatChunk;
  } catch {
    throw AppError.malformed();
  }
  if (chunk === null || typeof chunk !== 'object') throw AppError.malformed();
  if (chunk.error) throw errorFromLine(chunk.error, model);
  const content = chunk.message?.content ?? '';
  if (content) {
    state.text += content;
    yield { type: 'delta', text: content };
  }
  if (chunk.message?.tool_calls?.length) state.toolCalls.push(...chunk.message.tool_calls);
  if (chunk.done) {
    state.done = true;
    if (state.toolCalls.length > 0) {
      yield { type: 'tool_calls', message: { role: 'assistant', content: state.text, tool_calls: state.toolCalls } };
    }
    yield {
      type: 'done',
      stats: {
        totalMs: chunk.total_duration !== undefined ? Math.round(chunk.total_duration / 1e6) : undefined,
        evalCount: chunk.eval_count,
        promptEvalCount: chunk.prompt_eval_count,
      },
    };
  }
}

