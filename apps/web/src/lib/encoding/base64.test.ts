import { decodeBase64, encodeBase64 } from '@/lib/encoding/base64';

/**
 * 261113-trading-real-bot U6, Step 5, written before the code (BR3.1, U6-NFR1.1, U6-NFR8.2): the base64 of the
 * transaction of the signing session, own code with no library, exact on every byte.
 */
describe('base64', () => {
  it('matches the RFC 4648 vectors', () => {
    const text = (value: string) => new TextEncoder().encode(value);

    expect(encodeBase64(text(''))).toBe('');
    expect(encodeBase64(text('f'))).toBe('Zg==');
    expect(encodeBase64(text('fo'))).toBe('Zm8=');
    expect(encodeBase64(text('foo'))).toBe('Zm9v');
    expect(encodeBase64(text('foobar'))).toBe('Zm9vYmFy');
    expect(decodeBase64('Zm9vYmE=')).toEqual(text('fooba'));
  });

  it('keeps every byte from 0 to 255 through a round trip, also on large inputs', () => {
    const all = Uint8Array.from({ length: 256 }, (_, index) => index);
    const large = Uint8Array.from({ length: 1_232 * 3 + 1 }, (_, index) => (index * 31) % 256);

    expect(decodeBase64(encodeBase64(all))).toEqual(all);
    expect(decodeBase64(encodeBase64(large))).toEqual(large);
  });

  it('refuses text that is not base64 instead of guessing', () => {
    expect(() => decodeBase64('abc')).toThrow();
    expect(() => decodeBase64('ab$=')).toThrow();
    expect(() => decodeBase64('a===')).toThrow();
    expect(() => decodeBase64('Zm9v\nYmFy')).toThrow();
  });
});
