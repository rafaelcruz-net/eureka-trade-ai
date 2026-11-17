import type { Wallet } from '@wallet-standard/base';
import { toWalletPort, type WalletPort } from '@/features/auth/wallets';
import { decodeBase64, encodeBase64 } from '@/lib/encoding/base64';
import {
  messageOf,
  SignedTransactionMismatch,
  signServerTransaction,
  WalletCannotSignTransaction,
} from './sign-transaction';

/**
 * 261113-trading-real-bot U6, Step 5, written before the code (BR3.1, BR3.6; U6-NFR1.1, U6-NFR8.2): the browser only
 * signs. The transaction built by the server goes to `signTransaction` of the Wallet Standard byte for byte, the wallet
 * fills its signature, and what comes back carries the same message (every byte after the signatures) or is refused
 * before anything is sent. A wallet without `solana:signTransaction` never gets a transaction.
 */
const MAIN = new Uint8Array(32).fill(7);

/** A wire transaction: compact-u16 count of signatures, 64 bytes each, then the message (synthetic bytes). */
function wireTransaction(signatures: number, message: Uint8Array): Uint8Array {
  return Uint8Array.from([signatures, ...new Uint8Array(64 * signatures), ...message]);
}

const MESSAGE = Uint8Array.from({ length: 180 }, (_, index) => (index * 13 + 5) % 256);
const UNSIGNED = wireTransaction(2, MESSAGE);

function signedBy(transaction: Uint8Array, slot: number): Uint8Array {
  const copy = Uint8Array.from(transaction);
  copy.set(new Uint8Array(64).fill(0xab), 1 + 64 * slot);
  return copy;
}

function standardWallet(signTransaction?: jest.Mock, versions: unknown[] = ['legacy', 0]): Wallet {
  const account = {
    address: 'MainWa11et111111111111111111111111111111111',
    publicKey: MAIN,
    chains: ['solana:mainnet'],
    features: ['solana:signMessage', 'solana:signTransaction'],
  };
  const features: Record<string, unknown> = {
    'standard:connect': {
      version: '1.0.0',
      connect: jest.fn(async () => ({ accounts: [account] })),
    },
    'solana:signMessage': { version: '1.0.0', signMessage: jest.fn() },
  };
  if (signTransaction) {
    features['solana:signTransaction'] = {
      version: '1.0.0',
      supportedTransactionVersions: versions,
      signTransaction,
    };
  }
  return {
    version: '1.0.0',
    name: 'Phantom',
    icon: 'data:image/svg+xml;base64,AA==',
    chains: ['solana:mainnet'],
    accounts: [],
    features,
  } as unknown as Wallet;
}

async function connected(wallet: Wallet): Promise<WalletPort> {
  const port = toWalletPort(wallet);
  if (!port) {
    throw new Error('not a wallet port');
  }
  await port.connect();
  return port;
}

describe('WalletPort.signTransaction', () => {
  it('declares the support only with solana:signTransaction for legacy or version 0', () => {
    expect(toWalletPort(standardWallet())?.canSignTransaction).toBe(false);
    expect(toWalletPort(standardWallet(jest.fn()))?.canSignTransaction).toBe(true);
    expect(toWalletPort(standardWallet(jest.fn(), [0]))?.canSignTransaction).toBe(true);
    expect(toWalletPort(standardWallet(jest.fn(), []))?.canSignTransaction).toBe(false);
  });

  it('gives the wallet the exact bytes, with the connected account, and returns what it signed', async () => {
    const sign = jest.fn(async (input: { transaction: Uint8Array }) => [
      { signedTransaction: signedBy(input.transaction, 1) },
    ]);
    const port = await connected(standardWallet(sign));

    const signed = await port.signTransaction(UNSIGNED);

    expect(sign).toHaveBeenCalledTimes(1);
    const input = sign.mock.calls[0]?.[0] as {
      transaction: Uint8Array;
      account: { publicKey: Uint8Array };
    };
    expect(input.transaction).toEqual(UNSIGNED);
    expect(input.account.publicKey).toEqual(MAIN);
    expect(signed).toEqual(signedBy(UNSIGNED, 1));
  });

  it('refuses to sign before connecting and without the feature', async () => {
    const before = toWalletPort(standardWallet(jest.fn()));
    const without = await connected(standardWallet());

    await expect(before?.signTransaction(UNSIGNED)).rejects.toThrow('Connect the wallet');
    await expect(without.signTransaction(UNSIGNED)).rejects.toBeInstanceOf(
      WalletCannotSignTransaction,
    );
  });

  it('a wallet that returns no transaction is an error, never an empty signature', async () => {
    const port = await connected(standardWallet(jest.fn(async () => [])));

    await expect(port.signTransaction(UNSIGNED)).rejects.toThrow('no signed transaction');
  });
});

describe('signServerTransaction', () => {
  it('passes the base64 of the server to the wallet unchanged and returns the signed transaction in base64 (S13)', async () => {
    const sign = jest.fn(async (input: { transaction: Uint8Array }) => [
      { signedTransaction: signedBy(input.transaction, 1) },
    ]);
    const port = await connected(standardWallet(sign));
    const given = encodeBase64(UNSIGNED);

    const result = await signServerTransaction(port, given);

    const handed = (sign.mock.calls[0]?.[0] as { transaction: Uint8Array }).transaction;
    expect(encodeBase64(handed)).toBe(given);
    expect(result).toBe(encodeBase64(signedBy(UNSIGNED, 1)));
    // Byte by byte from the first byte after the signatures: the message is the one the server built.
    expect(messageOf(decodeBase64(result))).toEqual(MESSAGE);
  });

  it('refuses a transaction whose message the wallet changed, before anything is sent', async () => {
    const tampered = jest.fn(async (input: { transaction: Uint8Array }) => {
      const changed = signedBy(input.transaction, 1);
      changed[changed.length - 1] = (changed[changed.length - 1] ?? 0) ^ 0xff;
      return [{ signedTransaction: changed }];
    });
    const port = await connected(standardWallet(tampered));

    await expect(signServerTransaction(port, encodeBase64(UNSIGNED))).rejects.toBeInstanceOf(
      SignedTransactionMismatch,
    );
  });

  it('refuses a transaction with another number of signatures', async () => {
    const reshaped = jest.fn(async () => [{ signedTransaction: wireTransaction(1, MESSAGE) }]);
    const port = await connected(standardWallet(reshaped));

    await expect(signServerTransaction(port, encodeBase64(UNSIGNED))).rejects.toBeInstanceOf(
      SignedTransactionMismatch,
    );
  });

  it('a wallet without signTransaction never receives the transaction (S16)', async () => {
    const port = await connected(standardWallet());

    await expect(signServerTransaction(port, encodeBase64(UNSIGNED))).rejects.toBeInstanceOf(
      WalletCannotSignTransaction,
    );
  });

  it('reads the compact-u16 count of signatures of two bytes', () => {
    // 128 signatures: 0x80 0x01, then 128 * 64 bytes, then the message.
    const many = Uint8Array.from([0x80, 0x01, ...new Uint8Array(128 * 64), 1, 2, 3]);

    expect(messageOf(many)).toEqual(Uint8Array.from([1, 2, 3]));
    expect(() => messageOf(Uint8Array.from([2, 1, 2]))).toThrow();
  });
});
