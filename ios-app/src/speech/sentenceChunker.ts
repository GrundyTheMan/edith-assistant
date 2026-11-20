const TERMINALS = '.!?…';
const CLOSERS = '"\'”’)]';
const ABBREVIATIONS = new Set([
  'mr', 'mrs', 'ms', 'dr', 'st', 'jr', 'sr', 'prof', 'vs', 'e.g', 'i.e', 'a.m', 'p.m', 'no', 'inc', 'ltd', 'co', 'mt', 'ft',
]);
const LONG = 100;
const MIN_BEFORE_CUT = 25;

const wordCount = (s: string) => s.trim().split(/\s+/).filter(Boolean).length;
const isSpace = (c: string | undefined) => c === ' ' || c === '\n' || c === '\t' || c === '\r';

/** The word that ends just before index `dot` (a '.'): letters and inner dots, e.g. "a.m" or "Dr". */
function wordBefore(text: string, dot: number): string {
  let i = dot;
  while (i > 0 && !isSpace(text[i - 1]) && !'([“"\''.includes(text[i - 1])) i--;
  return text.slice(i, dot);
}

/**
 * Cuts a streaming reply into sentences to speak (C13 §1 and As built). Sentences end at . ! ? … followed by
 * whitespace (not inside numbers such as 3.5, not after Mr. Mrs. Dr. St. vs. e.g. i.e. a.m. p.m. or an initial); a line
 * break also ends one; a sentence longer than ~100 characters is cut at the first comma, semicolon, colon or dash
 * after 25 characters (L15); a one-word piece ("Sure.", "1.") is joined to the next sentence.
 */
export class SentenceChunker {
  private buffer = '';
  private held = '';

  /** Feeds streamed text; returns the sentences completed by it. */
  push(delta: string): string[] {
    this.buffer += delta;
    const out: string[] = [];
    for (;;) {
      const end = this.findBoundary();
      if (end === -1) break;
      this.emit(this.buffer.slice(0, end), out);
      this.buffer = this.buffer.slice(end).replace(/^\s+/, '');
    }
    for (;;) {
      if (this.buffer.length <= LONG) break;
      const cut = findCut(this.buffer);
      if (cut === -1) break;
      this.emit(this.buffer.slice(0, cut), out);
      this.buffer = this.buffer.slice(cut).replace(/^\s+/, '');
    }
    return out;
  }

  /** The end of the reply: whatever is left, even a single word. */
  flush(): string[] {
    const out: string[] = [];
    const rest = (this.held + (this.held && this.buffer ? ' ' : '') + this.buffer).trim();
    this.held = '';
    this.buffer = '';
    if (rest) splitLong(rest).forEach((p) => out.push(p));
    return out;
  }

  reset() {
    this.buffer = '';
    this.held = '';
  }

  private emit(sentence: string, out: string[]) {
    const text = (this.held ? `${this.held} ${sentence.trim()}` : sentence.trim()).trim();
    if (!text) return;
    if (wordCount(text) < 2) {
      this.held = text;
      return;
    }
    this.held = '';
    splitLong(text).forEach((p) => out.push(p));
  }

  /** Index just after a confirmed sentence end, or -1. */
  private findBoundary(): number {
    const b = this.buffer;
    for (let i = 0; i < b.length; i++) {
      const c = b[i];
      if (c === '\n') {
        if (b.slice(0, i).trim()) return i;
        continue;
      }
      if (!TERMINALS.includes(c)) continue;
      let j = i + 1;
      while (j < b.length && (TERMINALS.includes(b[j]) || CLOSERS.includes(b[j]))) j++;
      if (j >= b.length) return -1; // can't tell yet: wait for the next character
      if (!isSpace(b[j])) {
        i = j - 1;
        continue; // 3.5, a.m., example.com ...
      }
      if (c === '.' && j === i + 1) {
        const word = wordBefore(b, i);
        if (ABBREVIATIONS.has(word.toLowerCase())) continue;
        if (/^[A-Z]$/.test(word)) continue; // an initial: "J. R. R. Tolkien"
      }
      return j;
    }
    return -1;
  }
}

/** Where to cut a long text: just after the first , ; : or dash at or after 25 characters that is followed by a space. */
function findCut(text: string): number {
  for (let i = MIN_BEFORE_CUT; i < text.length - 1; i++) {
    const c = text[i];
    if ((c === ',' || c === ';' || c === ':' || c === '—' || c === '–') && isSpace(text[i + 1])) return i + 1;
  }
  return -1;
}

function splitLong(text: string): string[] {
  const out: string[] = [];
  let rest = text;
  while (rest.length > LONG) {
    const cut = findCut(rest);
    if (cut === -1) break;
    out.push(rest.slice(0, cut).trim());
    rest = rest.slice(cut).trim();
  }
  if (rest) out.push(rest);
  return out;
}
