import { LineSplitter, Utf8StreamDecoder } from './utf8';

const enc = (s: string) => new TextEncoder().encode(s);

describe('Utf8StreamDecoder', () => {
  it('decodes plain ASCII and whole multi-byte characters', () => {
    const d = new Utf8StreamDecoder();
    expect(d.decode(enc('héllo wörld — 😀'))).toBe('héllo wörld — 😀');
  });

  it('holds back a character split between chunks, at every possible split point', () => {
    const text = 'a€b😀c é';
    const bytes = enc(text);
    for (let cut = 0; cut <= bytes.length; cut++) {
      const d = new Utf8StreamDecoder();
      const out = d.decode(bytes.slice(0, cut)) + d.decode(bytes.slice(cut)) + d.flush();
      expect(out).toBe(text);
    }
  });

  it('survives one byte at a time', () => {
    const text = 'Zoë said “hi” 👋🏽';
    const d = new Utf8StreamDecoder();
    let out = '';
    for (const b of enc(text)) out += d.decode(Uint8Array.of(b));
    expect(out + d.flush()).toBe(text);
  });

  it('turns invalid bytes and a dangling lead byte into U+FFFD', () => {
    const d = new Utf8StreamDecoder();
    expect(d.decode(Uint8Array.of(0x61, 0xff, 0x62))).toBe('a�b');
    expect(d.decode(Uint8Array.of(0xe2, 0x82))).toBe('');
    expect(d.flush()).toBe('�');
    expect(d.decode(Uint8Array.of(0xe2, 0x61))).toBe('�a');
  });
});

describe('LineSplitter', () => {
  it('joins a line split across chunks and drops blank lines', () => {
    const s = new LineSplitter();
    expect(s.push('{"a":')).toEqual([]);
    expect(s.push('1}\n{"b":2}\r\n\n{"c"')).toEqual(['{"a":1}', '{"b":2}']);
    expect(s.push(':3}')).toEqual([]);
    expect(s.flush()).toEqual(['{"c":3}']);
  });
});
