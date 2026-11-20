export const TITLE_MAX = 40;

/** The first message as a title (D12): trimmed, whitespace collapsed, at most 40 characters, cut at a word with "…". */
export function firstMessageTitle(text: string): string {
  const s = text.replace(/\s+/g, ' ').trim();
  if (s.length <= TITLE_MAX) return s;
  let cut = s.slice(0, TITLE_MAX);
  if (s[TITLE_MAX] !== ' ') {
    const space = cut.lastIndexOf(' ');
    if (space > 0) cut = cut.slice(0, space);
  }
  return `${cut.trimEnd()}…`;
}

/** Cleans Edith's own title (C06b As built): first line, no quotes, markdown, "Title:" or ending punctuation, ≤ 60. */
export function cleanEdithTitle(raw: string): string {
  let s = (raw.split('\n').find((l) => l.trim().length > 0) ?? '').trim();
  s = s.replace(/^title\s*[:\-–]\s*/i, '');
  s = s.replace(/[*_`#>]+/g, '');
  s = s.replace(/^["'“”‘’]+|["'“”‘’]+$/g, '').trim();
  s = s.replace(/[.!?,;:]+$/, '').trim();
  if (s.length > 60) {
    s = s.slice(0, 60);
    const space = s.lastIndexOf(' ');
    if (space > 20) s = s.slice(0, space);
    s = s.trimEnd();
  }
  return s;
}
