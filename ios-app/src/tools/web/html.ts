/**
 * A small, forgiving HTML tokenizer for the two jobs the web tools have: reading DuckDuckGo's results page and turning a
 * web page into readable text. No regexes with lookbehind or `\p{}` (Hermes, L25); no DOM library (none runs in React Native).
 */

export type Token =
  | { type: 'open'; name: string; attrs: Record<string, string>; selfClosing: boolean }
  | { type: 'close'; name: string }
  | { type: 'text'; text: string };

const NAMED: Record<string, string> = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', ndash: '–', mdash: '—', hellip: '…', lsquo: '‘', rsquo: '’',
  ldquo: '“', rdquo: '”', copy: '©', reg: '®', trade: '™', bull: '•', middot: '·', laquo: '«', raquo: '»', euro: '€',
  pound: '£', cent: '¢', deg: '°', times: '×',
};

export function decodeEntities(s: string): string {
  if (s.indexOf('&') < 0) return s;
  let out = '';
  let i = 0;
  while (i < s.length) {
    const amp = s.indexOf('&', i);
    if (amp < 0) {
      out += s.slice(i);
      break;
    }
    out += s.slice(i, amp);
    const semi = s.indexOf(';', amp + 1);
    if (semi < 0 || semi - amp > 10) {
      out += '&';
      i = amp + 1;
      continue;
    }
    const body = s.slice(amp + 1, semi);
    let decoded: string | undefined;
    if (body[0] === '#') {
      const code = body[1] === 'x' || body[1] === 'X' ? parseInt(body.slice(2), 16) : parseInt(body.slice(1), 10);
      if (Number.isFinite(code) && code > 0 && code <= 0x10ffff) decoded = String.fromCodePoint(code);
    } else {
      decoded = NAMED[body.toLowerCase()];
    }
    if (decoded === undefined) {
      out += '&';
      i = amp + 1;
    } else {
      out += decoded;
      i = semi + 1;
    }
  }
  return out;
}

const VOID = new Set(['br', 'hr', 'img', 'input', 'meta', 'link', 'area', 'base', 'col', 'embed', 'source', 'track', 'wbr']);
const RAW_TEXT = new Set(['script', 'style']);
const isSpace = (c: string) => c === ' ' || c === '\n' || c === '\t' || c === '\r' || c === '\f';
const isNameChar = (c: string) => !isSpace(c) && c !== '=' && c !== '>' && c !== '/' && c !== '"' && c !== "'";

function parseTag(src: string, start: number): { token: Token; end: number } | null {
  // src[start] === '<'
  let i = start + 1;
  const closing = src[i] === '/';
  if (closing) i++;
  const nameStart = i;
  while (i < src.length && isNameChar(src[i])) i++;
  const name = src.slice(nameStart, i).toLowerCase();
  if (!name || !((name[0] >= 'a' && name[0] <= 'z') || (name[0] >= '0' && name[0] <= '9'))) return null;
  const attrs: Record<string, string> = {};
  let selfClosing = false;
  while (i < src.length && src[i] !== '>') {
    if (isSpace(src[i])) {
      i++;
    } else if (src[i] === '/') {
      selfClosing = true;
      i++;
    } else {
      selfClosing = false;
      const aStart = i;
      while (i < src.length && isNameChar(src[i])) i++;
      if (i === aStart) {
        i++;
        continue;
      }
      const key = src.slice(aStart, i).toLowerCase();
      while (i < src.length && isSpace(src[i])) i++;
      let value = '';
      if (src[i] === '=') {
        i++;
        while (i < src.length && isSpace(src[i])) i++;
        const quote = src[i];
        if (quote === '"' || quote === "'") {
          const close = src.indexOf(quote, i + 1);
          const stop = close < 0 ? src.length : close;
          value = src.slice(i + 1, stop);
          i = close < 0 ? src.length : close + 1;
        } else {
          const vStart = i;
          while (i < src.length && !isSpace(src[i]) && src[i] !== '>') i++;
          value = src.slice(vStart, i);
        }
      }
      if (!(key in attrs)) attrs[key] = decodeEntities(value);
    }
  }
  if (i >= src.length) return null;
  const token: Token = closing ? { type: 'close', name } : { type: 'open', name, attrs, selfClosing: selfClosing || VOID.has(name) };
  return { token, end: i + 1 };
}

/** Tokens in document order. Comments and doctype are dropped; script and style contents are dropped. */
export function tokenize(src: string): Token[] {
  const tokens: Token[] = [];
  let i = 0;
  const pushText = (raw: string) => {
    if (raw) tokens.push({ type: 'text', text: decodeEntities(raw) });
  };
  while (i < src.length) {
    const lt = src.indexOf('<', i);
    if (lt < 0) {
      pushText(src.slice(i));
      break;
    }
    pushText(src.slice(i, lt));
    if (src.startsWith('<!--', lt)) {
      const end = src.indexOf('-->', lt + 4);
      i = end < 0 ? src.length : end + 3;
      continue;
    }
    if (src[lt + 1] === '!' || src[lt + 1] === '?') {
      const end = src.indexOf('>', lt);
      i = end < 0 ? src.length : end + 1;
      continue;
    }
    const parsed = parseTag(src, lt);
    if (!parsed) {
      pushText('<');
      i = lt + 1;
      continue;
    }
    tokens.push(parsed.token);
    i = parsed.end;
    if (parsed.token.type === 'open' && RAW_TEXT.has(parsed.token.name) && !parsed.token.selfClosing) {
      const lower = src.toLowerCase();
      const close = lower.indexOf(`</${parsed.token.name}`, i);
      const skipTo = close < 0 ? src.length : close;
      i = skipTo; // the close tag is tokenized next
    }
  }
  return tokens;
}

export const collapse = (s: string) => s.split(/\s+/).filter(Boolean).join(' ');

const BLOCKS = new Set([
  'p', 'div', 'section', 'article', 'main', 'li', 'ul', 'ol', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'tr', 'table', 'blockquote',
  'pre', 'br', 'dt', 'dd', 'figcaption', 'details', 'summary',
]);
const DROPPED = new Set(['script', 'style', 'nav', 'footer', 'aside', 'header', 'form', 'iframe', 'svg', 'noscript', 'template', 'head']);

/** Readable text: one line per block, boilerplate dropped, `article` or `main` preferred when the page has one. */
export function readableText(html: string): { title: string; text: string } {
  const tokens = tokenize(html);
  let title = '';
  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i];
    if (t.type === 'open' && t.name === 'title') {
      const next = tokens[i + 1];
      if (next?.type === 'text') title = collapse(next.text);
      break;
    }
  }
  const extract = (only: string | null): string => {
    const lines: string[] = [];
    let line = '';
    const flush = () => {
      const c = collapse(line);
      if (c) lines.push(c);
      line = '';
    };
    let dropDepth = 0;
    const dropStack: string[] = [];
    let inside = only === null ? 1 : 0;
    const scope: string[] = [];
    for (const t of tokens) {
      if (t.type === 'open') {
        if (t.selfClosing) {
          if (dropDepth === 0 && inside > 0 && BLOCKS.has(t.name)) flush();
          continue;
        }
        if (DROPPED.has(t.name)) {
          dropDepth++;
          dropStack.push(t.name);
          continue;
        }
        if (only !== null && t.name === only) {
          inside++;
          scope.push(t.name);
        }
        if (dropDepth === 0 && inside > 0 && BLOCKS.has(t.name)) flush();
      } else if (t.type === 'close') {
        if (dropDepth > 0) {
          if (t.name === dropStack[dropStack.length - 1]) {
            dropStack.pop();
            dropDepth--;
          }
          continue;
        }
        if (only !== null && t.name === only && inside > 0) {
          flush();
          inside--;
          scope.pop();
        } else if (inside > 0 && BLOCKS.has(t.name)) flush();
      } else if (dropDepth === 0 && inside > 0) {
        line += t.text;
      }
    }
    flush();
    return lines.join('\n');
  };
  for (const preferred of ['article', 'main']) {
    const text = extract(preferred);
    if (text.length > 200) return { title, text };
  }
  return { title, text: extract(null) };
}
