/** Emoji and joiners, by code point (no `\p{…}` escapes: regex engines differ, 04-lessons L25). */
function isEmojiCodePoint(cp: number): boolean {
  return (
    (cp >= 0x1f000 && cp <= 0x1faff) ||
    (cp >= 0x2600 && cp <= 0x27bf) ||
    (cp >= 0x1f1e6 && cp <= 0x1f1ff) ||
    cp === 0x2b50 ||
    cp === 0x2b55 ||
    cp === 0xfe0f ||
    cp === 0x200d ||
    (cp >= 0x1f3fb && cp <= 0x1f3ff)
  );
}

function stripEmoji(text: string): string {
  let out = '';
  for (const ch of text) if (!isEmojiCodePoint(ch.codePointAt(0)!)) out += ch;
  return out;
}

/**
 * What the voice should read (C13 §1 and As built): Markdown removed, link syntax → its text, URLs → "a link"
 * (keeping the sentence's full stop), emoji, bullets and `_emphasis_` removed, whitespace collapsed.
 */
export function cleanForSpeech(text: string): string {
  let s = text;
  s = s.replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1');
  s = s.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1');
  s = s.replace(/(?:https?:\/\/|www\.)[^\s)>\]]*[^\s)>\].,;:!?]/g, 'a link');
  s = s
    .split('\n')
    .map((line) => line.replace(/^\s*(?:#{1,6}\s+|>+\s?|[-*•]\s+)/, ''))
    .join('\n');
  s = s.replace(/(\*\*|__|~~|`)/g, '');
  s = s.replace(/(^|\s)[*_]([^*_\n]+)[*_](?=\s|[.,!?;:]|$)/g, '$1$2');
  s = stripEmoji(s);
  return s.replace(/\s+/g, ' ').trim();
}
