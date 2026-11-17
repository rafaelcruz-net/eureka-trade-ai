import type { ReactNode } from 'react';
import type {
  GuestApi,
  RealBotWallet,
  RealOrder,
  RealPnl,
  RealSigningSession,
  RealWithdrawal,
} from '@/lib/api/guest-api';
import { ApiError } from '@/lib/api/client';
import { fakeApi, fakeWallet, Providers } from '@/features/auth/test-support';
import type { WalletPort } from '@/features/auth/wallets';
import { LiveChannelProvider } from '@/features/live/LiveChannelProvider';
import { fakeFetch } from '@/features/dashboard/test-support';
import { encodeBase64 } from '@/lib/encoding/base64';

/**
 * 261113-trading-real-bot U6: test utilities of the REAL screens. Factories built from the generated contract types (an
 * error test starts from a valid value and changes only the field under test); addresses, mints and ids are
 * synthetic, never real ones.
 */
export const BOT_ADDRESS = 'BotWa11et1111111111111111111111111111111111';
export const MAIN_ADDRESS = 'MainWa11et111111111111111111111111111111111';
export const MINT = 'TestMint111111111111111111111111111111111111';
export const ORDER_ID = '0dde0000-0000-4000-8000-0000000000a1';
export const CORRELATION = 'corr-0000-4000-8000-000000000001';

export function walletDto(overrides: Partial<RealBotWallet> = {}): RealBotWallet {
  return {
    address: BOT_ADDRESS,
    mainWalletAddress: MAIN_ADDRESS,
    status: 'ACTIVE',
    mode: 'SIGNED',
    capLamports: '3000000000',
    maxCapLamports: '10000000000',
    maxSlippageBps: 200,
    balance: {
      totalLamports: '1460000000',
      freeLamports: '1250000000',
      reservedLamports: '200000000',
      feeReserveLamports: '10000000',
      aboveCapLamports: '0',
      operableLamports: '1250000000',
      asOf: '2026-11-16T11:56:00Z',
    },
    pausedReason: 'NONE',
    network: 'LOCALNET',
    policyResetRequired: false,
    capSetBy: 'USER',
    release: { state: 'ACTIVE', effectiveAt: '2026-11-10T12:00:00Z' },
    ...overrides,
  };
}

export function orderDto(overrides: Partial<RealOrder> = {}): RealOrder {
  return {
    realOrderId: ORDER_ID,
    state: 'QUOTED',
    side: 'BUY',
    operationMode: 'SIGNED',
    origin: 'BOT',
    input: {
      mint: 'So11111111111111111111111111111111111111112',
      baseUnits: '200000000',
      decimals: 9,
    },
    output: { mint: MINT, baseUnits: '16260000000', decimals: 6 },
    feeLamports: '5000',
    createdAt: '2026-11-16T13:02:00Z',
    updatedAt: '2026-11-16T13:02:05Z',
    signingDeadline: '2026-11-16T12:00:10Z',
    ...overrides,
  };
}

/** A synthetic wire transaction: 2 empty signatures and a message of known bytes. */
export const UNSIGNED_BYTES = Uint8Array.from([
  2,
  ...new Uint8Array(128),
  ...Array.from({ length: 96 }, (_, index) => (index * 7 + 1) % 256),
]);

export function sessionDto(overrides: Partial<RealSigningSession> = {}): RealSigningSession {
  return {
    transactionBase64: encodeBase64(UNSIGNED_BYTES),
    messageDigest: 'a'.repeat(64),
    deadline: '2026-11-16T12:00:10Z',
    serverTime: '2026-11-16T12:00:00Z',
    display: {
      side: 'BUY',
      input: {
        mint: 'So11111111111111111111111111111111111111112',
        baseUnits: '200000000',
        decimals: 9,
      },
      expectedOutput: { mint: MINT, baseUnits: '16260000000', decimals: 6 },
      minOutput: { mint: MINT, baseUnits: '15934800000', decimals: 6 },
      feeLamports: '5000',
      reason: 'volume 24h subiu 40% e liquidez acima do mínimo',
    },
    ...overrides,
  };
}

export function pnlDto(overrides: Partial<RealPnl> = {}): RealPnl {
  return {
    realizedLamports: '84000000',
    unrealizedLamports: '12000000',
    unrealizedStatus: 'KNOWN',
    feesLamports: '15000',
    positions: [],
    ...overrides,
  };
}

export function withdrawalDto(overrides: Partial<RealWithdrawal> = {}): RealWithdrawal {
  return {
    withdrawalId: '0dde0000-0000-4000-8000-0000000000b1',
    kind: 'PARTIAL',
    lamports: '500000000',
    feeLamports: '5000',
    state: 'REQUESTED',
    requestedAt: '2026-11-16T12:00:00Z',
    ...overrides,
  };
}

/** A problem of the API as the client raises it, with a correlationId and optional detail. */
export function problem(status: number, code: string, detail?: string): ApiError {
  return new ApiError(status, code, undefined, CORRELATION, [], [], undefined, undefined, detail);
}

/** An API double with the REAL on and an active bot wallet in SIGNED, unless overridden. */
export function realApi(overrides: Partial<GuestApi> = {}): jest.Mocked<GuestApi> {
  return fakeApi({
    getRealRelease: jest.fn(async () => ({
      network: 'LOCALNET',
      release: { state: 'ACTIVE' as const, effectiveAt: '2026-11-10T12:00:00Z' },
      exitsEnabled: false,
    })),
    getBotWallet: jest.fn(async () => walletDto()),
    getRealPnl: jest.fn(async () => pnlDto()),
    ...overrides,
  });
}

/** A wallet double that signs transactions: it fills the second signature slot with 0xab and keeps the message. */
export function signingWallet(overrides: Partial<WalletPort> = {}): WalletPort {
  return fakeWallet({
    canSignTransaction: true,
    signTransaction: jest.fn(async (transaction: Uint8Array) => {
      const signed = Uint8Array.from(transaction);
      signed.set(new Uint8Array(64).fill(0xab), 1 + 64);
      return signed;
    }),
    ...overrides,
  });
}

/** The providers of the app with the live channel of the root layout on a fake fetch. */
export function RealProviders({
  api,
  net = fakeFetch(),
  children,
}: {
  api: GuestApi;
  net?: ReturnType<typeof fakeFetch>;
  children: ReactNode;
}) {
  return (
    <Providers api={api}>
      <LiveChannelProvider fetchImpl={net.impl}>{children}</LiveChannelProvider>
    </Providers>
  );
}

/** Every value kept by the browser storage and the cookies, to prove nothing of the REAL is there (U6-NFR1.2). */
export function browserStorageText(): string {
  const texts: string[] = [];
  for (const storage of [window.localStorage, window.sessionStorage]) {
    for (let index = 0; index < storage.length; index++) {
      const key = storage.key(index) ?? '';
      texts.push(key, storage.getItem(key) ?? '');
    }
  }
  texts.push(document.cookie);
  return texts.join('\n');
}
