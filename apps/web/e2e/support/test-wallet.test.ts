/**
 * @jest-environment node
 */
import { verify } from 'node:crypto';
import { generateTestWallet, signTransactionBytes } from './test-wallet';

/**
 * 261113-trading-real-bot U6, Step 5, written before the code (infrastructure-specification.md "Carteira de teste",
 * U6-NFR8.2): the TEST-ONLY wallet of the e2e signs a transaction like a real wallet does with `solana:signTransaction`:
 * it finds its own key among the signers of the message, puts an Ed25519 signature over the message in that slot and
 * returns the whole serialized transaction, every other byte unchanged. Keys are generated here, never real ones.
 */
function compactU16(value: number): number[] {
  return value < 0x80 ? [value] : [(value & 0x7f) | 0x80, value >> 7];
}

/** A legacy message: header (signers, readonly signed, readonly unsigned), the keys, a blockhash, no instruction. */
function legacyMessage(keys: Uint8Array[], signers: number): Uint8Array {
  return Uint8Array.from([
    signers,
    0,
    1,
    ...compactU16(keys.length),
    ...keys.flatMap((key) => Array.from(key)),
    ...new Uint8Array(32).fill(9),
    0,
  ]);
}

function transaction(message: Uint8Array, signers: number): Uint8Array {
  return Uint8Array.from([...compactU16(signers), ...new Uint8Array(64 * signers), ...message]);
}

describe('test wallet signTransaction', () => {
  it('signs the message in the slot of its own key and keeps every other byte', () => {
    const botWallet = generateTestWallet();
    const main = generateTestWallet();
    const program = new Uint8Array(32).fill(3);
    const message = legacyMessage(
      [Uint8Array.from(botWallet.publicKey), Uint8Array.from(main.publicKey), program],
      2,
    );
    const unsigned = transaction(message, 2);

    const signed = signTransactionBytes(main, unsigned);

    expect(signed).toHaveLength(unsigned.length);
    expect(signed.subarray(1, 65)).toEqual(unsigned.subarray(1, 65));
    expect(signed.subarray(1 + 128)).toEqual(message);
    const signature = Buffer.from(signed.subarray(65, 129));
    expect(verify(null, Buffer.from(message), main.key, signature)).toBe(true);
  });

  it('reads a version 0 message (prefix 0x80) too', () => {
    const main = generateTestWallet();
    const legacy = legacyMessage([Uint8Array.from(main.publicKey)], 1);
    const v0 = Uint8Array.from([0x80, ...legacy, 0]);

    const signed = signTransactionBytes(main, transaction(v0, 1));

    expect(verify(null, Buffer.from(v0), main.key, Buffer.from(signed.subarray(1, 65)))).toBe(true);
  });

  it('refuses a transaction it is not a signer of', () => {
    const main = generateTestWallet();
    const stranger = generateTestWallet();
    const message = legacyMessage([Uint8Array.from(stranger.publicKey)], 1);

    expect(() => signTransactionBytes(main, transaction(message, 1))).toThrow(
      'not a signer of this transaction',
    );
  });
});
