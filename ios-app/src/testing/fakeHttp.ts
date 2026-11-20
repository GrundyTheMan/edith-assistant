import type { HttpFetch, HttpResponse } from '../ollama/client';

const enc = new TextEncoder();

export interface RecordedRequest {
  url: string;
  method: string;
  body?: string;
}

/** A body that yields `chunks` one by one (strings are UTF-8 encoded; Uint8Arrays are used as they are). */
export function streamResponse(chunks: (string | Uint8Array)[], status = 200, hold?: Promise<void>, signal?: AbortSignal): HttpResponse {
  const parts = chunks.map((c) => (typeof c === 'string' ? enc.encode(c) : c));
  let i = 0;
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => parts.map((p) => new TextDecoder().decode(p)).join(''),
    body: {
      getReader: () => ({
        read: async () => {
          if (i >= parts.length) {
            if (hold) {
              await Promise.race([
                hold,
                new Promise<never>((_, reject) => {
                  const fail = () => reject(Object.assign(new Error("Aborted"), { name: "AbortError" }));
                  if (signal?.aborted) fail();
                  else signal?.addEventListener("abort", fail);
                }),
              ]);
            }
            return { done: true };
          }
          return { done: false, value: parts[i++] };
        },
      }),
    },
  };
}

export function jsonResponse(body: unknown, status = 200): HttpResponse {
  return streamResponse([JSON.stringify(body)], status);
}

/** A fake `fetch` that records requests and answers with `respond`. */
export function fakeFetch(respond: (req: RecordedRequest, signal?: AbortSignal) => HttpResponse | Promise<HttpResponse>) {
  const requests: RecordedRequest[] = [];
  const fetch: HttpFetch = async (url, init) => {
    const req = { url, method: init.method, body: init.body };
    requests.push(req);
    return respond(req, init.signal);
  };
  return { fetch, requests };
}

/** Real Ollama NDJSON lines (03-ollama-reference §3). */
export const ndjson = {
  line: (content: string, extra: Record<string, unknown> = {}) =>
    JSON.stringify({ model: 'gemma4:e4b-it-q8_0', created_at: '2026-10-29T18:30:33Z', message: { role: 'assistant', content }, done: false, ...extra }) + '\n',
  done: () =>
    JSON.stringify({
      model: 'gemma4:e4b-it-q8_0',
      message: { role: 'assistant', content: '' },
      done: true,
      done_reason: 'stop',
      total_duration: 257684900,
      prompt_eval_count: 118,
      eval_count: 29,
    }) + '\n',
  toolCall: () =>
    JSON.stringify({
      model: 'gemma4:e4b-it-q8_0',
      message: {
        role: 'assistant',
        content: '',
        tool_calls: [{ id: 'call_rf7td9i2', function: { index: 0, name: 'get_weather', arguments: { location: 'Boston' } } }],
      },
      done: false,
    }) + '\n',
};
