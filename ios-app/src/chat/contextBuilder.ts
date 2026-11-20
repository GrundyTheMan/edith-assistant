import { MEDIA_WINDOW } from '../attachments/attachments';
import type { StoredAttachment, StoredMessage } from '../data/chatRepository';
import { stripToolCalls } from '../data/chatRepository';
import { filePlaceholder } from './replyFormat';
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

/** What the model may see of the attachments, the owner's and Edith's own pictures (C15 §3, C49). */
export interface MediaContext {
  attachments: StoredAttachment[];
  /** The picture's Base64 if it is still held in this run of the app. */
  image(attachmentId: number): string | null;
  /** The text of a file Edith wrote, while it is held (C56). */
  fileText?(attachmentId: number): string | null;
  /** The current model has the "vision" capability. */
  canSee: boolean;
}

const labelFor = (a: StoredAttachment, available: boolean) =>
  `[Attachment ${a.number}: ${a.fileName} (${a.kind})${available ? '' : ' — no longer available'}]`;

/**
 * History → Ollama messages (C06 §2, C15 §3). Only complete user and assistant messages; `<tool_call>` text stripped;
 * the newest messages that fit the budget; never starting with an assistant message; the latest user message always
 * included (truncated in the middle if it is huge). A failed turn's error text never reaches the model (L3). A message
 * of attachments only is kept; its attachments appear as label lines, and pictures are sent for the newest
 * MEDIA_WINDOW messages that still have them.
 */
export function buildContext(
  history: StoredMessage[],
  system: string,
  budgetTokens = CONTEXT_BUDGET_TOKENS,
  media?: MediaContext,
): OllamaMessage[] {
  const byMessage = new Map<number, StoredAttachment[]>();
  for (const a of media?.attachments ?? []) {
    byMessage.set(a.messageId, [...(byMessage.get(a.messageId) ?? []), a]);
  }

  interface Usable {
    role: 'user' | 'assistant';
    content: string;
    attachments: StoredAttachment[];
  }
  const usable: Usable[] = [];
  for (const m of history) {
    if (m.status !== 'complete') continue;
    const text = m.role === 'assistant' ? stripToolCalls(m.content).trim() : m.content;
    const attachments = byMessage.get(m.id) ?? [];
    if (!text.trim() && attachments.length === 0) continue;
    usable.push({ role: m.role, content: text, attachments });
  }

  const windowStart = usable.length - MEDIA_WINDOW;
  const render = (m: Usable, index: number): OllamaMessage => {
    if (m.attachments.length === 0) return { role: m.role, content: m.content };
    const inWindow = index >= windowStart;
    const images: string[] = [];
    let body = m.content.trim();
    // A file Edith wrote is back in her message as she wrote it, within the media window and while kept; otherwise its placeholder stays (C16 §5).
    for (const a of m.attachments.filter((x) => x.kind === 'file')) {
      const text = inWindow && !a.removedAt ? (media?.fileText?.(a.id) ?? null) : null;
      if (text !== null) body = body.replace(filePlaceholder(a.fileName), `<file name="${a.fileName}">\n${text}\n</file>`);
    }
    const labels = m.attachments
      .filter((a) => a.kind !== 'file')
      .map((a) => {
        const source = a.resendOf ?? a.id; // a resend shows the original's bytes
        const data = inWindow && media?.canSee && a.kind === 'image' && !a.removedAt ? media.image(source) : null;
        if (data) {
          images.push(data);
          return labelFor(a, true);
        }
        const held = !a.removedAt && !!media?.image(source);
        return labelFor(a, held);
      });
    const content = [body, ...labels].filter(Boolean).join('\n');
    return images.length > 0 ? { role: m.role, content, images } : { role: m.role, content };
  };
  const costOf = (m: Usable) => estimateTokens(m.content) + m.attachments.length * 20;

  const kept: number[] = [];
  let total = 0;
  for (let i = usable.length - 1; i >= 0; i--) {
    const cost = costOf(usable[i]);
    if (kept.length > 0 && total + cost > budgetTokens) break;
    kept.unshift(i);
    total += cost;
  }

  const lastUser = usable.map((m) => m.role).lastIndexOf('user');
  if (lastUser >= 0 && !kept.includes(lastUser)) {
    kept.length = 0;
    kept.push(lastUser);
  }
  const rendered = kept.map((i) => ({ i, msg: render(usable[i], i) }));
  const big = rendered.find((r) => r.i === lastUser);
  if (big && estimateTokens(big.msg.content) > budgetTokens) big.msg = { ...big.msg, content: truncateMiddle(big.msg.content, budgetTokens) };
  while (rendered.length > 0 && rendered[0].msg.role === 'assistant') rendered.shift();

  return [{ role: 'system', content: system }, ...rendered.map((r) => r.msg)];
}
