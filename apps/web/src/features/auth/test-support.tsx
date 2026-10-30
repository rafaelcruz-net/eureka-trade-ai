import type { ReactNode } from 'react';
import { ApiProvider } from '@/lib/api/ApiProvider';
import type { GuestApi, GuestSession, ScannerPage } from '@/lib/api/guest-api';
import { SessionProvider } from '@/features/auth/SessionProvider';
import type { WalletPort } from '@/features/auth/wallets';

export const SESSION: GuestSession = {
  accessToken: 'access-token-value',
  accessTokenExpiresAt: '2026-10-28T12:15:00Z',
  sessionExpiresAt: '2026-10-29T00:00:00Z',
  role: 'BETA_USER',
};

/** API double typed with the generated contract types. */
export function fakeApi(overrides: Partial<GuestApi> = {}): jest.Mocked<GuestApi> {
  const empty: ScannerPage = {
    items: [],
    nextCursor: null,
    total: 0,
    sources: [],
    excludedByUnknown: 0,
    pendingNewTokens: 0,
  };
  return {
    requestChallenge: jest.fn(async (walletAddress: string) => ({
      message: `localhost:3000 pede que você entre com a sua conta Solana\n${walletAddress}`,
      nonce: 'nonce-123',
      domain: 'localhost:3000',
      chain: 'solana:mainnet',
      issuedAt: '2026-10-28T12:00:00Z',
      expiresAt: '2026-10-28T12:05:00Z',
    })),
    verifyLogin: jest.fn(async () => SESSION),
    refreshSession: jest.fn(async () => {
      throw new Error('no session');
    }),
    logout: jest.fn(async () => undefined),
    listTokens: jest.fn(async () => empty),
    ...overrides,
  } as jest.Mocked<GuestApi>;
}

/** Wallet double: never holds a real key; signatures are fixed bytes. */
export function fakeWallet(overrides: Partial<WalletPort> = {}): WalletPort {
  return {
    name: 'Phantom',
    provider: 'PHANTOM',
    connect: jest.fn(async () => ({ address: 'TestWa11et1111111111111111111111111111111111' })),
    signMessage: jest.fn(async () => new Uint8Array([1, 2, 3])),
    ...overrides,
  };
}

export function Providers({ api, children }: { api: GuestApi; children: ReactNode }) {
  return (
    <ApiProvider apiOrigin="http://api" api={api}>
      <SessionProvider>{children}</SessionProvider>
    </ApiProvider>
  );
}
