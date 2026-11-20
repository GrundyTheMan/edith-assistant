import { log } from '../../core/log';
import type { OllamaClient } from '../../ollama/client';
import type { OllamaMessage, OllamaTool } from '../../ollama/types';
import { DEFAULT_MAX_RESULT_CHARS, toOllama, type Tool, type ToolContext, type ToolResult } from './tool';
import { ArgumentError, ToolArgs } from './toolArgs';
import type { ToolRegistry } from './toolRegistry';

export const MAX_ROUNDS = 5;
export const TOOL_TIMEOUT_MS = 20_000;
const THINKING = 'Edith is thinking…';

export interface TurnHooks {
  /** A piece of the reply text. */
  onDelta(text: string): void;
  /** The running text was said before a tool call: clear it, the next round writes the real answer. */
  onTextReset(): void;
  onStatus(label: string): void;
}
export interface TurnInput {
  client: OllamaClient;
  baseUrl: string;
  model: { name: string; capabilities: string[] };
  messages: OllamaMessage[];
  registry: ToolRegistry;
  toolContext: ToolContext;
  hooks: TurnHooks;
  signal?: AbortSignal;
  timeoutMs?: number;
}
export interface TurnOutput {
  /** The last round's text only: "Let me check…" before a tool call is not kept. */
  text: string;
  /** Tool names used, once each, in order. */
  toolsUsed: string[];
}

/** Runs one tool, turning every failure into text for the model. Throws only when the turn itself is cancelled. */
async function runTool(tool: Tool, args: ToolArgs, ctx: ToolContext, timeoutMs: number): Promise<ToolResult> {
  const timeout = new AbortController();
  const timer = setTimeout(() => timeout.abort(), timeoutMs);
  const onAbort = () => timeout.abort();
  ctx.signal?.addEventListener('abort', onAbort);
  try {
    return await Promise.race([
      tool.execute(args, { ...ctx, signal: timeout.signal }),
      new Promise<never>((_, reject) => {
        if (timeout.signal.aborted) reject(new Error('aborted'));
        else timeout.signal.addEventListener('abort', () => reject(new Error('aborted')));
      }),
    ]);
  } catch (e) {
    if (ctx.signal?.aborted) throw e; // the owner stopped the turn
    if (e instanceof ArgumentError) return { ok: false, text: `Error: missing ${e.argName} for ${tool.spec.name}.` };
    if (timeout.signal.aborted) return { ok: false, text: `The ${tool.displayName} tool took too long. Try again shortly.` };
    log.warn('Tools', `${tool.spec.name} threw`);
    return { ok: false, text: `The ${tool.displayName} tool failed unexpectedly.` };
  } finally {
    clearTimeout(timer);
    ctx.signal?.removeEventListener('abort', onAbort);
  }
}

/**
 * C08 §5: the tool loop. Streams a round; if the model asks for tools, runs them and asks again (up to MAX_ROUNDS), then
 * a last round with no tools so she answers with what she has. The exchange is never stored (L3): only the final text
 * and the tool names leave this function.
 */
export async function runToolTurn(input: TurnInput): Promise<TurnOutput> {
  const { client, baseUrl, model, registry, hooks, signal } = input;
  const offered = registry.available({ name: model.name, capabilities: model.capabilities });
  const toolsJson: OllamaTool[] = offered.map((t) => toOllama(t.spec));
  const messages = [...input.messages];
  const toolsUsed: string[] = [];
  const timeoutMs = input.timeoutMs ?? TOOL_TIMEOUT_MS;

  const round = async (tools: OllamaTool[] | undefined) => {
    let text = '';
    let calls: OllamaMessage | null = null;
    for await (const e of client.chatStream(baseUrl, { model: model.name, messages, tools }, { signal })) {
      if (e.type === 'delta') {
        text += e.text;
        hooks.onDelta(e.text);
      } else if (e.type === 'tool_calls') {
        calls = e.message;
      }
    }
    return { text, calls };
  };

  for (let i = 0; i < MAX_ROUNDS; i++) {
    hooks.onStatus(THINKING);
    const { text, calls } = await round(toolsJson.length > 0 ? toolsJson : undefined);
    const requested = calls?.tool_calls ?? [];
    if (!calls || requested.length === 0) return { text, toolsUsed };

    hooks.onTextReset();
    messages.push(calls); // exactly as received (03 §4 rule 1)
    for (const call of requested) {
      const rawName = call.function.name;
      const tool = registry.resolve(rawName, offered);
      let result: ToolResult;
      if (!tool) {
        result = { ok: false, text: `Error: there's no tool called '${rawName}'. Available: ${offered.map((t) => t.spec.name).join(', ')}.` };
      } else {
        hooks.onStatus(tool.statusLabel);
        result = await runTool(tool, new ToolArgs(call.function.arguments, tool.spec), input.toolContext, timeoutMs);
        if (!toolsUsed.includes(tool.spec.name)) toolsUsed.push(tool.spec.name);
      }
      log.info('Tools', `${tool?.spec.name ?? 'unknown tool'} -> ${result.ok ? 'ok' : 'error'}`);
      log.sensitive('Tools', `${rawName}(${JSON.stringify(call.function.arguments)}) -> ${result.text.slice(0, 120)}`);
      messages.push({
        role: 'tool',
        tool_name: tool?.spec.name ?? rawName,
        content: result.text.slice(0, tool?.maxResultChars ?? DEFAULT_MAX_RESULT_CHARS),
      });
    }
  }

  // Cap reached: answer with what she has, with no tools.
  hooks.onStatus(THINKING);
  hooks.onTextReset();
  const final = await round(undefined);
  return { text: final.text, toolsUsed };
}
