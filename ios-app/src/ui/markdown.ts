/** A small Markdown parser for Edith's replies (C07 §3): no dependency, and unrecognised text stays as written. */
export type Inline =
  | { type: 'text'; text: string }
  | { type: 'bold'; children: Inline[] }
  | { type: 'italic'; children: Inline[] }
  | { type: 'code'; text: string }
  | { type: 'link'; text: string; url: string };

export type Block =
  | { type: 'paragraph'; inlines: Inline[] }
  | { type: 'heading'; level: number; inlines: Inline[] }
  | { type: 'bullet'; inlines: Inline[] }
  | { type: 'numbered'; number: string; inlines: Inline[] }
  | { type: 'quote'; inlines: Inline[] }
  | { type: 'code'; text: string };

export function parseInline(text: string): Inline[] {
  const out: Inline[] = [];
  let buf = '';
  const flush = () => {
    if (buf) out.push({ type: 'text', text: buf });
    buf = '';
  };
  let i = 0;
  while (i < text.length) {
    const rest = text.slice(i);
    let m: RegExpExecArray | null;
    if ((m = /^`([^`\n]+)`/.exec(rest))) {
      flush();
      out.push({ type: 'code', text: m[1] });
      i += m[0].length;
    } else if ((m = /^\[([^\]\n]+)\]\((https?:\/\/[^)\s]+)\)/.exec(rest))) {
      flush();
      out.push({ type: 'link', text: m[1], url: m[2] });
      i += m[0].length;
    } else if ((m = /^(\*\*|__)(\S(?:[\s\S]*?\S)?)\1/.exec(rest))) {
      flush();
      out.push({ type: 'bold', children: parseInline(m[2]) });
      i += m[0].length;
    } else if ((m = /^\*([^\s*](?:[^*\n]*?[^\s*])?)\*(?!\*)/.exec(rest))) {
      flush();
      out.push({ type: 'italic', children: parseInline(m[1]) });
      i += m[0].length;
    } else if (
      (m = /^_([^\s_](?:[^_\n]*?[^\s_])?)_(?![A-Za-z0-9_])/.exec(rest)) &&
      (i === 0 || !/[A-Za-z0-9]/.test(text[i - 1]))
    ) {
      flush();
      out.push({ type: 'italic', children: parseInline(m[1]) });
      i += m[0].length;
    } else if ((m = /^https?:\/\/[^\s)<>]*[^\s)<>.,;:!?]/.exec(rest))) {
      flush();
      out.push({ type: 'link', text: m[0], url: m[0] });
      i += m[0].length;
    } else {
      buf += text[i];
      i++;
    }
  }
  flush();
  return out;
}

export function parseMarkdown(source: string): Block[] {
  const blocks: Block[] = [];
  const lines = source.replace(/\r\n/g, '\n').split('\n');
  let para: string[] = [];
  const endParagraph = () => {
    if (para.length) blocks.push({ type: 'paragraph', inlines: parseInline(para.join('\n')) });
    para = [];
  };
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    let m: RegExpExecArray | null;
    if (/^\s*```/.test(line)) {
      endParagraph();
      const code: string[] = [];
      i++;
      while (i < lines.length && !/^\s*```/.test(lines[i])) code.push(lines[i++]);
      blocks.push({ type: 'code', text: code.join('\n') });
    } else if (!line.trim()) {
      endParagraph();
    } else if ((m = /^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$/.exec(line))) {
      endParagraph();
      blocks.push({ type: 'heading', level: m[1].length, inlines: parseInline(m[2]) });
    } else if ((m = /^\s*[-*•]\s+(.*)$/.exec(line))) {
      endParagraph();
      blocks.push({ type: 'bullet', inlines: parseInline(m[1]) });
    } else if ((m = /^\s*(\d{1,3})[.)]\s+(.*)$/.exec(line))) {
      endParagraph();
      blocks.push({ type: 'numbered', number: m[1], inlines: parseInline(m[2]) });
    } else if ((m = /^\s*>\s?(.*)$/.exec(line))) {
      endParagraph();
      blocks.push({ type: 'quote', inlines: parseInline(m[1]) });
    } else {
      para.push(line);
    }
  }
  endParagraph();
  return blocks;
}

/** The text without its markers, for the conversation list's one-line preview. */
export function stripMarkdown(source: string): string {
  const flat = (inl: Inline[]): string =>
    inl.map((n) => (n.type === 'text' || n.type === 'code' || n.type === 'link' ? n.text : flat(n.children))).join('');
  return parseMarkdown(source)
    .map((b) => (b.type === 'code' ? b.text : flat(b.inlines)))
    .join(' ')
    .replace(/\s+/g, ' ')
    .trim();
}
