import type { ReactNode } from 'react';
import { ApiProvider } from '@/lib/api/ApiProvider';
import type { AdminApi, AdminSession, KillSwitch, SystemStatus } from '@/lib/api/admin-api';
import { AdminSessionProvider } from '@/features/auth/AdminSessionProvider';
import type { WalletPort } from '@/features/auth/wallets';

export const SESSION: AdminSession = {
  accessToken: 'admin-access-token',
  accessTokenExpiresAt: '2026-11-05T12:15:00Z',
  sessionExpiresAt: '2026-11-05T20:00:00Z',
  role: 'ADMIN',
  walletMasked: 'AdmW…1111',
};

export const KILL_SWITCH_OFF: KillSwitch = {
  state: 'OFF',
  pendingBots: 0,
  reconcileNeeded: false,
  version: 0,
  activeBotsToPause: 12,
};

export const STATUS: SystemStatus = {
  generatedAt: '2026-11-05T15:30:00Z',
  guestCount: { status: 'OK', value: 42 },
  inviteCounts: { status: 'OK', value: { PENDING: 3, USED: 40, EXPIRED: 1, REVOKED: 2 } },
  botCountsByState: { status: 'OK', value: { ACTIVE: 12, PAUSED: 4, CLOSING_PENDING: 0 } },
  killSwitch: { status: 'OK', value: { state: 'OFF', pendingBots: 0, reconcileNeeded: false } },
  sources: {
    status: 'OK',
    value: [
      {
        sourceId: 'sim-a',
        name: 'Simulada A',
        simulated: true,
        status: 'OK',
        lastUpdatedAt: '2026-11-05T15:29:00Z',
      },
      { sourceId: 'sim-b', name: 'Simulada B', simulated: true, status: 'PENDING' },
    ],
  },
  auditOutboxPending: { status: 'OK', value: 0 },
  rejectedOrdersToday: { status: 'OK', value: 7 },
};

/** API double typed with the generated contract types; signed in by default (the refresh answers a session). */
export function fakeAdminApi(overrides: Partial<AdminApi> = {}): jest.Mocked<AdminApi> {
  let key = 0;
  return {
    newKey: jest.fn(() => `key-${++key}`),
    requestChallenge: jest.fn(async (walletAddress: string) => ({
      message: `localhost:3001 pede que você entre com a sua conta Solana\n${walletAddress}`,
      nonce: 'nonce-123',
      domain: 'localhost:3001',
      chain: 'solana:mainnet',
      issuedAt: '2026-11-05T12:00:00Z',
      expiresAt: '2026-11-05T12:05:00Z',
    })),
    verifyLogin: jest.fn(async () => SESSION),
    refreshSession: jest.fn(async () => SESSION),
    logout: jest.fn(async () => undefined),
    getSystemStatus: jest.fn(async () => STATUS),
    getKillSwitch: jest.fn(async () => KILL_SWITCH_OFF),
    changeKillSwitch: jest.fn(async () => ({
      ...KILL_SWITCH_OFF,
      state: 'ON' as const,
      version: 1,
    })),
    listInvites: jest.fn(async () => ({ items: [], nextCursor: null })),
    issueInvite: jest.fn(async () => {
      throw new Error('no invitation configured');
    }),
    revokeInvite: jest.fn(async () => {
      throw new Error('no invitation configured');
    }),
    listTenants: jest.fn(async () => [{ tenantId: 't-1', name: 'Beta' }]),
    listFeedback: jest.fn(async () => ({ items: [], nextCursor: null })),
    ...overrides,
  } as jest.Mocked<AdminApi>;
}

export function Providers({ api, children }: { api: AdminApi; children: ReactNode }) {
  return (
    <ApiProvider apiOrigin="http://api" api={api}>
      <AdminSessionProvider>{children}</AdminSessionProvider>
    </ApiProvider>
  );
}

/** Wallet double: never holds a real key; the signature is fixed bytes. */
export function fakeWallet(overrides: Partial<WalletPort> = {}): WalletPort {
  return {
    name: 'Phantom',
    provider: 'PHANTOM',
    connect: jest.fn(async () => ({ address: 'AdmWa11et1111111111111111111111111111111111' })),
    signMessage: jest.fn(async () => new Uint8Array([0, 1, 2, 3])),
    ...overrides,
  };
}

export function walletsOf(wallets: WalletPort[]) {
  return (onChange: (w: WalletPort[]) => void) => {
    onChange(wallets);
    return () => undefined;
  };
}
