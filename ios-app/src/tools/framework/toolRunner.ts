import { log } from '../../core/log';
import type { OllamaClient } from '../../ollama/client';
import type { OllamaMessage, OllamaTool } from '../../ollama/types';
import { DEFAULT_MAX_RESULT_CHARS, toOllama, type ConfirmationRequest, type Tool, type ToolContext, type ToolImage, type ToolResult } from './tool';
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
  /**
   * Asks the owner to approve an action; resolves true (confirm) or false (cancel), and rejects when the turn is
   * cancelled while waiting (nothing runs). Missing: a tool that needs approval is not run.
   */
  confirm?: (request: ConfirmationRequest) => Promise<boolean>;
}
export interface TurnOutput {
  /** The last round's text only: "Let me check…" before a tool call is not kept. */
  text: string;
  /** Tool names used, once each, in order. */
  toolsUsed: string[];
  /** Pictures the tools produced this turn (at most MAX_TURN_IMAGES), to be stored as Edith's attachments (C49). */
  images: ToolImage[];
}

/** At most this many pictures a turn, each no larger than this many Base64 characters (about 1 MB of JPEG). */
export const MAX_TURN_IMAGES = 4;
export const MAX_IMAGE_BASE64 = 1_400_000;

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

let confirmationCounter = 0;

/**
 * Asks for approval when the tool wants it. `true`: go ahead; otherwise the result to send the model instead of running
 * the tool. A declined call is an ordinary result, not an error (C08 As built). A cancelled turn throws.
 */
async function approve(tool: Tool, args: ToolArgs, input: TurnInput): Promise<true | ToolResult> {
  let spec;
  try {
    spec = (await tool.confirmation?.(args, input.toolContext)) ?? null;
  } catch (e) {
    if (e instanceof ArgumentError) return { ok: false, text: `Error: missing ${e.argName} for ${tool.spec.name}.` };
    log.warn('Tools', `${tool.spec.name} confirmation threw`);
    return { ok: false, text: `The ${tool.displayName} tool failed unexpectedly.` };
  }
  if (!spec) return true;
  if (!input.confirm) return { ok: false, text: `${tool.displayName} needs the owner's approval, which isn't available here, so it was not done.` };
  confirmationCounter += 1;
  const request: ConfirmationRequest = {
    ...spec,
    id: `c${confirmationCounter}`,
    toolName: tool.spec.name,
    confirmLabel: spec.confirmLabel ?? 'Confirm',
    denyLabel: spec.denyLabel ?? 'Cancel',
  };
  input.hooks.onStatus('Waiting for your OK…');
  const yes = await input.confirm(request);
  if (yes) return true;
  return { ok: true, text: `The user declined: ${spec.title} Don't retry unless they ask.` };
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
  const images: ToolImage[] = [];
  const canSee = model.capabilities.includes('vision');
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
    if (!calls || requested.length === 0) return { text, toolsUsed, images };

    hooks.onTextReset();
    messages.push(calls); // exactly as received (03 §4 rule 1)
    for (const call of requested) {
      const rawName = call.function.name;
      const tool = registry.resolve(rawName, offered);
      let result: ToolResult;
      if (!tool) {
        result = { ok: false, text: `Error: there's no tool called '${rawName}'. Available: ${offered.map((t) => t.spec.name).join(', ')}.` };
      } else {
        const approved = await approve(tool, new ToolArgs(call.function.arguments, tool.spec), input);
        if (approved === true) {
          hooks.onStatus(tool.statusLabel);
          result = await runTool(tool, new ToolArgs(call.function.arguments, tool.spec), input.toolContext, timeoutMs);
          if (!toolsUsed.includes(tool.spec.name)) toolsUsed.push(tool.spec.name);
        } else {
          result = approved;
        }
      }
      log.info('Tools', `${tool?.spec.name ?? 'unknown tool'} -> ${result.ok ? 'ok' : 'error'}`);
      log.sensitive('Tools', `${rawName}(${JSON.stringify(call.function.arguments)}) -> ${result.text.slice(0, 120)}`);
      // Pictures: keep a few that are not too big; only a model that can see is given them (the exchange is never stored, L3).
      const kept: ToolImage[] = [];
      let note = '';
      for (const img of result.images ?? []) {
        if (img.base64.length > MAX_IMAGE_BASE64) note += ' A picture was too large to show and was left out.';
        else if (images.length >= MAX_TURN_IMAGES) note += ' Only the first pictures could be shown.';
        else {
          images.push(img);
          kept.push(img);
        }
      }
      if (kept.length > 0 && !canSee) note += ' The picture was shown to the owner; you cannot see it.';
      messages.push({
        role: 'tool',
        tool_name: tool?.spec.name ?? rawName,
        content: (result.text.slice(0, tool?.maxResultChars ?? DEFAULT_MAX_RESULT_CHARS) + note).trim(),
        ...(kept.length > 0 && canSee ? { images: kept.map((i) => i.base64) } : {}),
      });
    }
  }

  // Cap reached: answer with what she has, with no tools.
  hooks.onStatus(THINKING);
  hooks.onTextReset();
  const final = await round(undefined);
  return { text: final.text, toolsUsed, images };
}
