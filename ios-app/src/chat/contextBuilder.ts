import type { StoredMessage } from '../data/chatRepository';
import { stripToolCalls } from '../data/chatRepository';
import type { OllamaMessage } from '../ollama/types';

/** Leaves room within num_ctx 16384 for tools, media and the reply (C06 §2). */
export const CONTEXT_BUDGET_TOKENS = 10_000;
const CHARS_PER_TOKEN = 3.5;

export const estimateTokens = (text: string) => Math.ceil(text.length / CHARS_PER_TOKEN);

/** Cuts the middle out of text that alone exceeds the budget, keeping the start and the end. */
export function truncateMiddle(text: string, maxTokens: number): string {
  const maxChars = Math.floor(maxTokens * CHARS_PER_TOKEN);
  if (text.length <= maxChars) return text;
  const marker = '\n[…]\n';
  const keep = maxChars - marker.length;
  const head = Math.ceil(keep / 2);
  return text.slice(0, head) + marker + text.slice(text.length - (keep - head));
}

/**
 * History → Ollama messages (C06 §2). Only complete user and assistant messages; `<tool_call>` text stripped; the
 * newest messages that fit the budget; never starting with an assistant message; the latest user message always
 * included (truncated in the middle if it is huge). A failed turn's error text never reaches the model (L3).
 */
export function buildContext(history: StoredMessage[], system: string, budgetTokens = CONTEXT_BUDGET_TOKENS): OllamaMessage[] {
  const usable: { role: 'user' | 'assistant'; content: string }[] = [];
  for (const m of history) {
    if (m.status !== 'complete') continue;
    const content = m.role === 'assistant' ? stripToolCalls(m.content).trim() : m.content;
    if (!content.trim()) continue;
    usable.push({ role: m.role, content });
  }

  const kept: typeof usable = [];
  let total = 0;
  for (let i = usable.length - 1; i >= 0; i--) {
    const cost = estimateTokens(usable[i].content);
    if (kept.length > 0 && total + cost > budgetTokens) break;
    kept.unshift(usable[i]);
    total += cost;
  }

  const lastUser = usable.map((m) => m.role).lastIndexOf('user');
  if (lastUser >= 0 && !kept.includes(usable[lastUser])) {
    kept.length = 0;
    kept.push(usable[lastUser]);
  }
  const bigIdx = kept.findIndex((m) => m === usable[lastUser]);
  if (bigIdx >= 0 && estimateTokens(kept[bigIdx].content) > budgetTokens) {
    kept[bigIdx] = { ...kept[bigIdx], content: truncateMiddle(kept[bigIdx].content, budgetTokens) };
  }
  while (kept.length > 0 && kept[0].role === 'assistant') kept.shift();

  return [{ role: 'system', content: system }, ...kept.map((m): OllamaMessage => ({ role: m.role, content: m.content }))];
}
