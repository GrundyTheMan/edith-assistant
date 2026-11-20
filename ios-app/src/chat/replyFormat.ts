import type { PromptSection } from './promptBuilder';

/**
 * Edith's two reply conventions (C16 §3, C56): a file she writes, `<file name="packing.md">…</file>`, and sending the owner's attachment back,
 * `<attachment number="3"/>`. Parsing is plain string scanning (no regex lookbehind or Unicode classes: Hermes, L25).
 */
export type ReplyPart =
  | { kind: 'text'; text: string }
  /** `closed` is false for a block that never closed: it runs to the end of the reply. */
  | { kind: 'file'; name: string; content: string; closed: boolean }
  | { kind: 'resend'; number: number };

/** What the model is told about the two conventions (C16 §3; always on). */
export const edithFilesSection: PromptSection = {
  text: () =>
    [
      'When your reply contains something worth keeping as a file (a list, note, table, plan or code), put it in a file block:',
      '<file name="short-name.ext">the content</file>',
      'Use .md for notes and lists, .csv for tables, and the right extension for code. Keep the chat text around it to a sentence or two.',
      "To send one of the user's attachments back to them, write <attachment number=\"3\"/> with its number. Only use numbers from labels you've been given.",
    ].join('\n'),
};

export const MAX_NAME = 60;
export const DEFAULT_NAME = 'note.md';

/** Letters, digits, `.`, `_` and `-` only; no path, no leading dots; at most 60 characters (C16 §3). */
export function sanitizeFileName(raw: string | undefined): string {
  let s = (raw ?? '').replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^[.-]+/, '').replace(/-{2,}/g, '-');
  if (s.length > MAX_NAME) {
    const dot = s.lastIndexOf('.');
    const ext = dot > 0 && s.length - dot <= 10 ? s.slice(dot) : '';
    s = s.slice(0, MAX_NAME - ext.length) + ext;
  }
  return s && s !== '.' ? s : DEFAULT_NAME;
}

const MIME: Record<string, string> = {
  md: 'text/markdown',
  txt: 'text/plain',
  csv: 'text/csv',
  json: 'application/json',
  html: 'text/html',
  xml: 'text/xml',
  yaml: 'text/plain',
  yml: 'text/plain',
};
/** From the extension; code and anything unknown is `text/plain`. */
export function mimeForName(name: string): string {
  const ext = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : '';
  return MIME[ext] ?? 'text/plain';
}

/** `[Attached file: name]`: what the stored message says in place of a file block. */
export const filePlaceholder = (name: string) => `[Attached file: ${name}]`;
const PLACEHOLDER = /\[Attached file: [^\]\n]*\]/g;
/** The stored text without its placeholders (the cards show the files instead). */
export const stripPlaceholders = (text: string) => text.replace(PLACEHOLDER, '').replace(/\n{3,}/g, '\n\n').trim();

/** An attribute's value in a tag's text: `name="x"`, `name='x'`, `name=x`, with spaces around "=" allowed. */
function attribute(tag: string, key: string): string | undefined {
  const lower = tag.toLowerCase();
  let from = 0;
  for (;;) {
    const at = lower.indexOf(key, from);
    if (at < 0) return undefined;
    from = at + key.length;
    if (at === 0 || !/\s/.test(lower[at - 1])) continue; // part of another word
    let i = from;
    while (i < tag.length && /\s/.test(tag[i])) i++;
    if (tag[i] !== '=') continue;
    i++;
    while (i < tag.length && /\s/.test(tag[i])) i++;
    const q = tag[i];
    if (q === '"' || q === "'") {
      const end = tag.indexOf(q, i + 1);
      return tag.slice(i + 1, end < 0 ? tag.length : end);
    }
    let end = i;
    while (end < tag.length && !/[\s>/]/.test(tag[end])) end++;
    return tag.slice(i, end);
  }
}

const isTagStart = (lower: string, at: number, word: string) => {
  if (!lower.startsWith(word, at)) return false;
  const c = lower[at + word.length];
  return c === undefined || /[\s>/]/.test(c);
};

export function parseReply(text: string): ReplyPart[] {
  const lower = text.toLowerCase();
  const parts: ReplyPart[] = [];
  let p = 0;
  let textStart = 0;
  const pushText = (end: number) => {
    if (end > textStart) parts.push({ kind: 'text', text: text.slice(textStart, end) });
  };
  while (p < text.length) {
    const lt = lower.indexOf('<', p);
    if (lt < 0) break;
    const isFile = isTagStart(lower, lt, '<file');
    const isResend = isTagStart(lower, lt, '<attachment');
    if (!isFile && !isResend) {
      p = lt + 1;
      continue;
    }
    const gt = text.indexOf('>', lt);
    if (gt < 0) {
      p = lt + 1; // a tag that never ends is just text
      continue;
    }
    const tag = text.slice(lt, gt + 1);
    if (isResend) {
      const n = Number(/^\d+$/.test(attribute(tag, 'number') ?? '') ? attribute(tag, 'number') : NaN);
      if (!Number.isFinite(n)) {
        p = gt + 1;
        continue;
      }
      pushText(lt);
      parts.push({ kind: 'resend', number: n });
      p = textStart = gt + 1;
      continue;
    }
    const close = lower.indexOf('</file>', gt + 1);
    pushText(lt);
    const closed = close >= 0;
    const content = text.slice(gt + 1, closed ? close : text.length);
    parts.push({ kind: 'file', name: sanitizeFileName(attribute(tag, 'name')), content: content.replace(/^\n/, '').replace(/\n$/, ''), closed });
    p = textStart = closed ? close + '</file>'.length : text.length;
  }
  pushText(text.length);
  return parts;
}

/** A reply as stored text without making anything of its files or resends: each file block becomes its placeholder, resend tags go. */
export function plainReply(text: string): string {
  return parseReply(text)
    .map((p) => (p.kind === 'text' ? p.text : p.kind === 'file' ? filePlaceholder(p.name) : ''))
    .join('')
    .trim();
}

/**
 * What a reply that is still being written shows (C16 §4): each file block is replaced by "Writing <name>…" while it is open, or by its
 * placeholder when closed; resend tags are dropped (the picture appears once the reply is stored).
 */
export function displayPartial(text: string): string {
  const parts = parseReply(text);
  if (parts.every((p) => p.kind === 'text')) return text;
  return parts
    .map((p) => (p.kind === 'text' ? p.text : p.kind === 'file' ? (p.closed ? filePlaceholder(p.name) : `Writing ${p.name}…`) : ''))
    .join('');
}

/** Is the start of `lower` a file or attachment tag, might it become one with more text, or neither? */
function tagKind(lower: string): 'file' | 'attachment' | 'maybe' | null {
  for (const [word, kind] of [['<file', 'file'], ['<attachment', 'attachment']] as const) {
    if (word.startsWith(lower)) return 'maybe'; // "<", "<fi", "<file" itself (the next character decides)
    if (lower.startsWith(word)) return /[\s>/]/.test(lower[word.length]) ? kind : null;
  }
  return null;
}

/**
 * Strips file blocks and tags from a reply that arrives in pieces (spoken replies, C13): a tag split across two pieces is held back until
 * it is clear whether it is one. `push` returns what may be spoken now; `flush` the held rest.
 */
export class SpeechFilter {
  private buf = '';
  private inFile = false;

  push(delta: string): string {
    this.buf += delta;
    let out = '';
    for (;;) {
      if (this.inFile) {
        const close = this.buf.toLowerCase().indexOf('</file>');
        if (close >= 0) {
          this.buf = this.buf.slice(close + '</file>'.length);
          this.inFile = false;
          continue;
        }
        // Everything in the file is dropped; keep only a tail that could be the start of "</file>".
        this.buf = this.buf.slice(Math.max(0, this.buf.length - ('</file>'.length - 1)));
        return out;
      }
      const lt = this.buf.indexOf('<');
      if (lt < 0) return out + this.take(this.buf.length);
      out += this.take(lt);
      const kind = tagKind(this.buf.toLowerCase());
      if (kind === 'maybe') return out; // wait for more text
      if (kind === null) {
        out += this.take(1); // an ordinary "<"
        continue;
      }
      const gt = this.buf.indexOf('>');
      if (gt < 0) return out; // the tag isn't finished yet
      this.inFile = kind === 'file';
      this.buf = this.buf.slice(gt + 1);
    }
  }

  /** At the end of the reply: whatever was held that turned out not to be a tag. An unclosed file or an unfinished tag stays unspoken. */
  flush(): string {
    const kind = this.buf ? tagKind(this.buf.toLowerCase()) : null;
    const rest = this.inFile || kind === 'file' || kind === 'attachment' ? '' : this.buf;
    this.buf = '';
    this.inFile = false;
    return rest;
  }

  private take(n: number): string {
    const s = this.buf.slice(0, n);
    this.buf = this.buf.slice(n);
    return s;
  }
}
