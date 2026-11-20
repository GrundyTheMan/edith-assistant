import type { OllamaClient } from './client';

/** What the "Hello Ollama" proof records (C40 §2). */
export interface ProbeResult {
  /** Milliseconds from sending the request to the first network chunk. */
  firstChunkMs?: number;
  /** ...to the first token of text. */
  firstTokenMs?: number;
  totalMs: number;
  networkChunks: number;
  tokens: number;
  /** Time between the first and the last network chunk. */
  spreadMs: number;
  /** True when the reply arrived over time rather than all at once. */
  incremental: boolean;
  text: string;
}

export async function runStreamProbe(
  client: OllamaClient,
  baseUrl: string,
  model: string,
  prompt: string,
  onText: (textSoFar: string) => void,
  now: () => number = Date.now,
  signal?: AbortSignal,
): Promise<ProbeResult> {
  const start = now();
  const chunkTimes: number[] = [];
  let text = '';
  let tokens = 0;
  let firstTokenAt: number | undefined;
  for await (const e of client.chatStream(
    baseUrl,
    { model, messages: [{ role: 'user', content: prompt }] },
    { signal, onRawChunk: () => chunkTimes.push(now()) },
  )) {
    if (e.type === 'delta') {
      firstTokenAt ??= now();
      tokens++;
      text += e.text;
      onText(text);
    }
  }
  const end = now();
  const spreadMs = chunkTimes.length > 1 ? chunkTimes[chunkTimes.length - 1] - chunkTimes[0] : 0;
  return {
    firstChunkMs: chunkTimes.length ? chunkTimes[0] - start : undefined,
    firstTokenMs: firstTokenAt === undefined ? undefined : firstTokenAt - start,
    totalMs: end - start,
    networkChunks: chunkTimes.length,
    tokens,
    spreadMs,
    incremental: chunkTimes.length >= 3 && spreadMs >= 100,
    text,
  };
}
