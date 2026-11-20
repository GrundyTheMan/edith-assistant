import { createHmac } from 'crypto';
import { hmacSha256, hmacSha256Hex, toHex } from './hmac';
import { utf8Bytes } from './md5';

describe('HMAC-SHA256', () => {
  it('matches RFC 4231 test cases 1, 2 and 6 (the last has a key longer than the block)', () => {
    expect(toHex(hmacSha256(new Uint8Array(20).fill(0x0b), utf8Bytes('Hi There')))).toBe('b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7');
    expect(hmacSha256Hex('Jefe', 'what do ya want for nothing?')).toBe('5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843');
    expect(toHex(hmacSha256(new Uint8Array(131).fill(0xaa), utf8Bytes('Test Using Larger Than Block-Size Key - Hash Key First')))).toBe('60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54');
  });

  it('agrees with Node on keys and messages of awkward lengths and non-ASCII text', () => {
    for (const keyLen of [0, 1, 31, 32, 63, 64, 65, 100]) {
      for (const msg of ['', 'a', 'tailscale:ben-pc.tail1234.ts.net', 'é😀 日本語', 'x'.repeat(200)]) {
        const key = 'k'.repeat(keyLen);
        expect(hmacSha256Hex(key, msg)).toBe(createHmac('sha256', key).update(msg, 'utf8').digest('hex'));
      }
    }
  });
});
