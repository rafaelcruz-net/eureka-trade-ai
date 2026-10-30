import type { Wallet } from '@wallet-standard/base';
import { ApiError } from '@/lib/api/client';
import { rejectionKey, safeOrigin, signInWithWallet } from '@/features/auth/login-flow';
import { providerOf, toWalletPort, watchWallets } from '@/features/auth/wallets';
import { fakeApi, fakeWallet } from '@/features/auth/test-support';

function standardWallet(name: string, features: string[]): Wallet {
  const account = { address: 'Addr1111', publicKey: new Uint8Array(32), chains: [], features: [] };
  const all: Record<string, unknown> = {
    'standard:connect': {
      version: '1.0.0',
      connect: jest.fn(async () => ({ accounts: [account] })),
    },
    'solana:signMessage': {
      version: '1.0.0',
      signMessage: jest.fn(async () => [
        { signedMessage: new Uint8Array(), signature: new Uint8Array([9, 9]) },
      ]),
    },
  };
  return {
    version: '1.0.0',
    name,
    icon: 'data:image/svg+xml;base64,AA==',
    chains: ['solana:mainnet'],
    accounts: [],
    features: Object.fromEntries(features.map((f) => [f, all[f]])),
  } as unknown as Wallet;
}

describe('wallet standard discovery', () => {
  it('maps Phantom and Solflare and ignores other wallets', () => {
    expect(providerOf('Phantom')).toBe('PHANTOM');
    expect(providerOf('Solflare')).toBe('SOLFLARE');
    expect(providerOf('Other Wallet')).toBeNull();
  });

  it('requires connect and Solana signMessage', () => {
    expect(toWalletPort(standardWallet('Phantom', ['standard:connect']))).toBeNull();
    expect(
      toWalletPort(standardWallet('Phantom', ['standard:connect', 'solana:signMessage'])),
    ).not.toBeNull();
  });

  it('connects, then signs with the connected account', async () => {
    const port = toWalletPort(
      standardWallet('Solflare', ['standard:connect', 'solana:signMessage']),
    );

    await expect(port?.signMessage(new Uint8Array([1]))).rejects.toThrow('Connect the wallet');
    await expect(port?.connect()).resolves.toEqual({ address: 'Addr1111' });
    await expect(port?.signMessage(new Uint8Array([1]))).resolves.toEqual(new Uint8Array([9, 9]));
  });

  it('publishes wallets now and when they register later', () => {
    const wallets: Wallet[] = [];
    const listeners: Record<string, () => void> = {};
    const registry = {
      get: () => wallets,
      on: (event: string, listener: () => void) => {
        listeners[event] = listener;
        return () => undefined;
      },
    };
    const seen: number[] = [];

    const stop = watchWallets((w) => seen.push(w.length), registry as never);
    wallets.push(standardWallet('Phantom', ['standard:connect', 'solana:signMessage']));
    listeners.register?.();
    stop();

    expect(seen).toEqual([0, 1]);
  });
});

describe('login flow', () => {
  it('signs exactly the challenge message and sends the Base58 signature', async () => {
    const api = fakeApi();
    const wallet = fakeWallet();

    await signInWithWallet(wallet, api);

    const challenge = await api.requestChallenge.mock.results[0]?.value;
    expect(wallet.signMessage).toHaveBeenCalledWith(new TextEncoder().encode(challenge.message));
    expect(api.verifyLogin).toHaveBeenCalledWith({
      walletAddress: 'TestWa11et1111111111111111111111111111111111',
      message: challenge.message,
      signature: 'Ldp',
      provider: 'PHANTOM',
    });
  });

  it('maps failures to message keys', () => {
    expect(rejectionKey(new ApiError(401, 'LOGIN_CHALLENGE_REJECTED', 'EXPIRED'))).toBe('EXPIRED');
    expect(rejectionKey(new ApiError(403, 'FORBIDDEN_ROLE'))).toBe('GENERIC');
    expect(rejectionKey(new ApiError(429, 'RATE_LIMITED'))).toBe('RATE_LIMITED');
    expect(rejectionKey(new ApiError(0, 'TIMEOUT'))).toBe('NETWORK');
    expect(rejectionKey(new Error('User denied'))).toBe('USER_CANCELLED');
    expect(rejectionKey('boom')).toBe('GENERIC');
  });

  it('accepts only same-app relative origins', () => {
    expect(safeOrigin('/scanner')).toBe('/scanner');
    expect(safeOrigin('//evil.example')).toBe('/scanner');
    expect(safeOrigin('https://evil.example')).toBe('/scanner');
    expect(safeOrigin('/\\evil')).toBe('/scanner');
    expect(safeOrigin(null)).toBe('/scanner');
  });
});
