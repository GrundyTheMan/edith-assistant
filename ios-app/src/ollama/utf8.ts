/**
 * A stream-aware UTF-8 decoder. A network chunk can end in the middle of a character, so the trailing incomplete
 * bytes are held back until the next chunk. C40 asks for `TextDecoder` with `{stream: true}`; this is a small decoder
 * of our own because Hermes' `TextDecoder` support for `stream` isn't something to rely on, and this one is easy to
 * test. Invalid bytes become U+FFFD.
 */
export class Utf8StreamDecoder {
  private pending: number[] = [];

  /** Decodes `bytes`, holding back an incomplete character at the end. */
  decode(bytes: Uint8Array): string {
    const buf = this.pending.length ? Uint8Array.from([...this.pending, ...bytes]) : bytes;
    this.pending = [];
    let out = '';
    let i = 0;
    while (i < buf.length) {
      const b = buf[i];
      if (b < 0x80) {
        out += String.fromCharCode(b);
        i++;
        continue;
      }
      let need: number;
      let cp: number;
      let min: number;
      if (b >= 0xc2 && b <= 0xdf) {
        need = 1;
        cp = b & 0x1f;
        min = 0x80;
      } else if (b >= 0xe0 && b <= 0xef) {
        need = 2;
        cp = b & 0x0f;
        min = 0x800;
      } else if (b >= 0xf0 && b <= 0xf4) {
        need = 3;
        cp = b & 0x07;
        min = 0x10000;
      } else {
        out += '�';
        i++;
        continue;
      }
      const available = buf.length - i - 1;
      if (available < need) {
        // The character is cut off by the end of this chunk: keep it if what we have so far is valid.
        let valid = true;
        for (let k = 1; k <= available; k++) if ((buf[i + k] & 0xc0) !== 0x80) valid = false;
        if (valid) {
          this.pending = Array.from(buf.slice(i));
          return out;
        }
      }
      let ok = true;
      for (let k = 1; k <= need; k++) {
        const c = buf[i + k];
        if (c === undefined || (c & 0xc0) !== 0x80) {
          ok = false;
          break;
        }
        cp = (cp << 6) | (c & 0x3f);
      }
      if (!ok || cp < min || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) {
        out += '�';
        i++;
        continue;
      }
      out += String.fromCodePoint(cp);
      i += need + 1;
    }
    return out;
  }

  /** At the end of the stream: an incomplete character left over becomes U+FFFD. */
  flush(): string {
    const leftover = this.pending.length > 0;
    this.pending = [];
    return leftover ? '�' : '';
  }
}

/** Splits decoded text into lines across chunks. */
export class LineSplitter {
  private rest = '';

  push(text: string): string[] {
    this.rest += text;
    const lines = this.rest.split('\n');
    this.rest = lines.pop() ?? '';
    return lines.map((l) => (l.endsWith('\r') ? l.slice(0, -1) : l)).filter((l) => l.trim().length > 0);
  }

  flush(): string[] {
    const last = this.rest.trim();
    this.rest = '';
    return last ? [last] : [];
  }
}
