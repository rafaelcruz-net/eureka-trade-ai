/**
 * 261113-trading-real-bot U6 (BR3.1, U6-NFR1.1): own base64 (RFC 4648, with padding), with no library, for the
 * transaction of the signing session. Decoding is strict: any character outside the alphabet, a wrong length or a
 * misplaced padding is an error, never a guess.
 */
const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
const VALUES = new Map<string, number>([...ALPHABET].map((char, index) => [char, index]));
const STRICT = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

export function encodeBase64(bytes: Uint8Array): string {
  let out = '';
  for (let index = 0; index < bytes.length; index += 3) {
    const a = bytes[index] as number;
    const b = bytes[index + 1];
    const c = bytes[index + 2];
    const chunk = (a << 16) | ((b ?? 0) << 8) | (c ?? 0);
    out += ALPHABET[(chunk >> 18) & 63];
    out += ALPHABET[(chunk >> 12) & 63];
    out += b === undefined ? '=' : ALPHABET[(chunk >> 6) & 63];
    out += c === undefined ? '=' : ALPHABET[chunk & 63];
  }
  return out;
}

export function decodeBase64(text: string): Uint8Array {
  if (!STRICT.test(text)) {
    throw new TypeError('Not a base64 text');
  }
  const padding = text.endsWith('==') ? 2 : text.endsWith('=') ? 1 : 0;
  const out = new Uint8Array((text.length / 4) * 3 - padding);
  let position = 0;
  for (let index = 0; index < text.length; index += 4) {
    let chunk = 0;
    for (let offset = 0; offset < 4; offset++) {
      const char = text[index + offset] as string;
      chunk = (chunk << 6) | (char === '=' ? 0 : (VALUES.get(char) as number));
    }
    for (const shift of [16, 8, 0]) {
      if (position < out.length) {
        out[position++] = (chunk >> shift) & 255;
      }
    }
  }
  return out;
}
