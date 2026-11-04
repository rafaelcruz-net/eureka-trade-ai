import type { ReactNode } from 'react';
import { ApiProvider } from '@/lib/api/ApiProvider';
import type {
  Account,
  GuestApi,
  GuestSession,
  InviteLookup,
  ScannerPage,
} from '@/lib/api/guest-api';
import { SessionProvider } from '@/features/auth/SessionProvider';
import type { WalletPort } from '@/features/auth/wallets';

export const SESSION: GuestSession = {
  accessToken: 'access-token-value',
  accessTokenExpiresAt: '2026-10-28T12:15:00Z',
  sessionExpiresAt: '2026-10-29T00:00:00Z',
  role: 'BETA_USER',
};

/** U3: a pending invitation as C3 answers it (plain-text terms in paragraphs). */
export const INVITE: InviteLookup = {
  status: 'PENDING',
  expiresAt: '2026-10-29T12:00:00Z',
  termsVersion: 'v1',
  termsText: 'Versão provisória, pendente de revisão jurídica.\n\nPrimeiro parágrafo dos termos.',
  riskNoticeText: 'Tokens de memes são voláteis.\n\nO modo REAL está indisponível no beta.',
};

/** U3: the Account screen as C3 answers it. */
export const ACCOUNT: Account = {
  walletAddressMasked: 'TestW…1111',
  provider: 'SOLFLARE',
  sessionExpiresAt: '2026-10-29T00:00:00Z',
  acceptedTerms: {
    version: 'v1',
    acceptedAt: '2026-10-28T12:00:00Z',
    termsText: 'Texto dos termos v1.\n\nSegundo parágrafo.',
    riskNoticeText: 'Texto do aviso de risco v1.',
  },
  realMode: 'UNAVAILABLE',
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
    getAssessment: jest.fn(async () => {
      throw new Error('no assessment configured');
    }),
    lookupInvite: jest.fn(async () => INVITE),
    getAccount: jest.fn(async () => ACCOUNT),
    // U7 (C5): an account without operations unless a test says otherwise.
    getPnl: jest.fn(async () => ({
      from: '2026-10-22T03:00:00Z',
      to: '2026-10-29T03:00:00Z',
      realized: '0.000000',
      unrealized: { status: 'KNOWN' as const, value: '0.000000' },
      fees: '0.000000',
      partial: false,
      empty: true,
      unrealizedAsOf: '2026-10-28T12:00:00Z',
      byToken: [],
    })),
    listLedger: jest.fn(async () => ({ items: [], nextCursor: null, total: 0 })),
    listBotTrades: jest.fn(async () => ({ items: [], nextCursor: null, total: 0 })),
    getDayUsage: jest.fn(async () => ({
      exposure: '0.000000',
      dayLoss: '0.000000',
      utcDay: '2026-10-28',
    })),
    getReconciliation: jest.fn(async () => ({
      status: 'RECONCILED' as const,
      divergenceCount: 0,
      divergences: [],
    })),
    runReconciliation: jest.fn(async () => ({
      status: 'RECONCILED' as const,
      trigger: 'ON_DEMAND' as const,
      checkedAt: '2026-10-28T12:00:00Z',
      divergenceCount: 0,
      divergences: [],
    })),
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
