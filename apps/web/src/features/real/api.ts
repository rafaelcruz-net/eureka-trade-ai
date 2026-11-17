import { ApiError } from '@/lib/api/client';
import type {
  GuestApi,
  RealBotWallet,
  RealOrder as RealOrderDto,
  RealPnl,
  RealReleaseStatus,
  RealSigningSession,
  RealTokenAmount,
  RealWithdrawal,
} from '@/lib/api/guest-api';
import {
  formatSol,
  isUnknown,
  toBaseUnits,
  toBaseUnitsOrUnknown,
  UNKNOWN,
  type Unknown,
} from '@/features/real/money';
import { realMessages as text } from '@/features/real/messages';

/**
 * 261113-trading-real-bot U6 (logical-components.md `api.ts`; U6-NFR7.1, U6-NFR7.5, BR8.8, U6-NFR5.1): the REAL calls of
 * the guest API typed by the generated contract, with every lamport and base unit turned into bigint by the one
 * {@link toBaseUnits}, a missing market value into the explicit Unknown, and every failure into a {@link RealError}
 * with its text of BR8.8 and the `correlationId` for support.
 */
export type Mode = 'SIGNED' | 'AUTO_EXITS' | 'AUTO_FULL';
export const MODES: readonly Mode[] = ['SIGNED', 'AUTO_EXITS', 'AUTO_FULL'];
export type ReleaseState = RealReleaseStatus['state'];

export interface Balance {
  total: bigint;
  free: bigint;
  reserved: bigint;
  feeReserve: bigint;
  aboveCap: bigint;
  operable: bigint;
  asOf: string | null;
}

export interface Release {
  state: ReleaseState;
  effectiveAt: string | null;
  blockedReason: string | null;
}

export interface BotWallet {
  address: string;
  mainWalletAddress: string;
  status: string;
  mode: Mode;
  pausedReason: string;
  cap: bigint;
  maxCap: bigint;
  capSetBy: 'USER' | 'ADMIN';
  network: string;
  balance: Balance | null;
  release: Release | null;
}

export interface TokenAmount {
  mint: string;
  baseUnits: bigint;
  decimals: number | undefined;
}

export interface Order {
  id: string;
  state: RealOrderDto['state'];
  side: RealOrderDto['side'];
  input: TokenAmount;
  output: TokenAmount | null;
  fee: bigint | Unknown;
  createdAt: string;
  txSignature: string | null;
  failureCode: string | null;
  liquidationId: string | null;
}

export interface Position {
  mint: string;
  baseUnits: bigint;
  cost: bigint;
  value: bigint | Unknown;
  unrealized: bigint | Unknown;
}

export interface Pnl {
  realized: bigint;
  unrealized: bigint | Unknown;
  fees: bigint;
  positions: Position[];
}

export interface SigningDisplay {
  side: 'BUY' | 'SELL';
  input: TokenAmount;
  expectedOutput: TokenAmount;
  minOutput: TokenAmount;
  fee: bigint;
  reason: string;
}

export interface SigningSession {
  transactionBase64: string;
  deadline: string;
  serverTime: string | null;
  display: SigningDisplay;
}

export interface Withdrawal {
  id: string;
  kind: string;
  state: string;
  lamports: bigint | Unknown;
  fee: bigint | Unknown;
  txSignature: string | null;
}

function release(value: RealReleaseStatus | undefined): Release | null {
  return value
    ? {
        state: value.state,
        effectiveAt: value.effectiveAt ?? null,
        blockedReason: value.blockedReason ?? null,
      }
    : null;
}

function mode(value: string | undefined): Mode {
  if (value === 'SIGNED' || value === 'AUTO_EXITS' || value === 'AUTO_FULL') {
    return value;
  }
  throw new TypeError('Unknown REAL mode');
}

export function toBotWallet(dto: RealBotWallet): BotWallet {
  const balance = dto.balance;
  return {
    address: dto.address ?? '',
    mainWalletAddress: dto.mainWalletAddress ?? '',
    status: dto.status ?? '',
    mode: mode(dto.mode),
    pausedReason: dto.pausedReason ?? 'NONE',
    cap: toBaseUnits(dto.capLamports),
    maxCap: toBaseUnits(dto.maxCapLamports),
    capSetBy: dto.capSetBy === 'ADMIN' ? 'ADMIN' : 'USER',
    network: dto.network ?? '',
    balance: balance
      ? {
          total: toBaseUnits(balance.totalLamports),
          free: toBaseUnits(balance.freeLamports),
          reserved: toBaseUnits(balance.reservedLamports),
          feeReserve: toBaseUnits(balance.feeReserveLamports),
          aboveCap: toBaseUnits(balance.aboveCapLamports),
          operable: toBaseUnits(balance.operableLamports),
          asOf: balance.asOf ?? null,
        }
      : null,
    release: release(dto.release),
  };
}

export function toTokenAmount(dto: RealTokenAmount): TokenAmount {
  return { mint: dto.mint, baseUnits: toBaseUnits(dto.baseUnits), decimals: dto.decimals };
}

export function toOrder(dto: RealOrderDto): Order {
  return {
    id: dto.realOrderId,
    state: dto.state,
    side: dto.side,
    input: toTokenAmount(dto.input),
    output: dto.output ? toTokenAmount(dto.output) : null,
    fee: toBaseUnitsOrUnknown(dto.feeLamports),
    createdAt: dto.createdAt,
    txSignature: dto.txSignature ?? null,
    failureCode: dto.failureCode ?? null,
    liquidationId: dto.liquidationId ?? null,
  };
}

export function toPnl(dto: RealPnl): Pnl {
  return {
    realized: toBaseUnits(dto.realizedLamports),
    // A missing price makes the unrealized unknown, never zero (U6-NFR7.5).
    unrealized:
      dto.unrealizedStatus === 'KNOWN' ? toBaseUnitsOrUnknown(dto.unrealizedLamports) : UNKNOWN,
    fees: toBaseUnits(dto.feesLamports),
    positions: dto.positions.map((position) => ({
      mint: position.mint,
      baseUnits: toBaseUnits(position.baseUnits),
      cost: toBaseUnits(position.costLamports),
      value:
        position.priceStatus === 'KNOWN' ? toBaseUnitsOrUnknown(position.valueLamports) : UNKNOWN,
      unrealized:
        position.priceStatus === 'KNOWN'
          ? toBaseUnitsOrUnknown(position.unrealizedLamports)
          : UNKNOWN,
    })),
  };
}

export function toSigningSession(dto: RealSigningSession): SigningSession {
  return {
    transactionBase64: dto.transactionBase64,
    deadline: dto.deadline,
    // Absent when the API is an older version (rollback of infrastructure-specification.md): the browser clock counts.
    serverTime: dto.serverTime ?? null,
    display: {
      side: dto.display.side,
      input: toTokenAmount(dto.display.input),
      expectedOutput: toTokenAmount(dto.display.expectedOutput),
      minOutput: toTokenAmount(dto.display.minOutput),
      fee: toBaseUnits(dto.display.feeLamports),
      reason: dto.display.reason,
    },
  };
}

export function toWithdrawal(dto: RealWithdrawal): Withdrawal {
  return {
    id: dto.withdrawalId ?? '',
    kind: dto.kind ?? '',
    state: dto.state ?? '',
    lamports: toBaseUnitsOrUnknown(dto.lamports),
    fee: toBaseUnitsOrUnknown(dto.feeLamports),
    txSignature: dto.txSignature ?? null,
  };
}

/** A failure of a REAL call: the code of the server (or of the transport), its text and the id for support. */
export class RealError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly text: string,
    readonly correlationId: string | null,
    /** The maximum the server named (WITHDRAWAL_LIMITED_BY_DIVERGENCE, INSUFFICIENT_FREE_BALANCE). */
    readonly maxLamports: bigint | null,
  ) {
    super(`${status} ${code}`);
    this.name = 'RealError';
  }
}

const MAX_IN_DETAIL = /(\d+) lamports/;

/** BR8.8: the text of a code; a code without one shows the `detail` of the ProblemDetail, never a stack trace. */
export function toRealError(failure: unknown, context: { maxCap?: bigint } = {}): RealError {
  if (failure instanceof RealError) {
    return failure;
  }
  if (!(failure instanceof ApiError)) {
    return new RealError(0, 'UNKNOWN', text.genericError, null, null);
  }
  const match = failure.detail ? MAX_IN_DETAIL.exec(failure.detail) : null;
  const max = match ? BigInt(match[1] as string) : null;
  const errors = text.errors;
  const known: Record<string, string | undefined> = {
    REAL_DISABLED: errors.REAL_DISABLED,
    REAL_NOT_RELEASED: errors.REAL_NOT_RELEASED,
    REAL_PAUSED: errors.REAL_PAUSED(failure.detail ?? text.paused.title),
    ORDER_AMBIGUOUS: errors.ORDER_AMBIGUOUS,
    ORDER_ALREADY_IN_PROGRESS: errors.ORDER_ALREADY_IN_PROGRESS,
    CONSENT_EXPIRED: errors.CONSENT_EXPIRED,
    CONSENT_INVALID: errors.CONSENT_INVALID,
    CAP_ABOVE_MAXIMUM: errors.CAP_ABOVE_MAXIMUM(formatSol(context.maxCap ?? 10_000_000_000n)),
    INSUFFICIENT_FREE_BALANCE: errors.INSUFFICIENT_FREE_BALANCE,
    WITHDRAWAL_LIMITED_BY_DIVERGENCE:
      max === null ? undefined : errors.WITHDRAWAL_LIMITED_BY_DIVERGENCE(formatSol(max)),
    BOT_WALLET_ALREADY_ACTIVE: errors.BOT_WALLET_ALREADY_ACTIVE,
    TERMS_V3_MISSING: errors.TERMS_V3_MISSING,
    SIGNING_DEADLINE_EXPIRED: errors.SIGNING_DEADLINE_EXPIRED,
    SIGNED_MESSAGE_MISMATCH: errors.SIGNED_MESSAGE_MISMATCH,
    SIGNING_POLICY_REFUSED: errors.SIGNING_POLICY_REFUSED,
    IDEMPOTENCY_CONFLICT: errors.IDEMPOTENCY_CONFLICT,
    UPSTREAM_UNAVAILABLE: errors.UPSTREAM_UNAVAILABLE,
    EXITS_DISABLED: errors.EXITS_DISABLED,
    EXITS_MODE_REQUIRED: errors.EXITS_MODE_REQUIRED,
    CONDITIONAL_ALREADY_OPEN: errors.CONDITIONAL_ALREADY_OPEN,
    PRICE_UNKNOWN: errors.PRICE_UNKNOWN,
    TP_NOT_ABOVE_SL: errors.TP_NOT_ABOVE_SL,
    TRIGGER_ON_WRONG_SIDE: errors.TRIGGER_ON_WRONG_SIDE,
    TRIGGER_OUT_OF_BOUNDS: errors.TRIGGER_OUT_OF_BOUNDS,
    TRIGGER_REJECTED: errors.TRIGGER_REJECTED,
    TOKEN_BLOCKED: errors.TOKEN_BLOCKED,
    EXIT_CANCELLING: errors.EXIT_CANCELLING,
    NOTHING_TO_SELL: errors.NOTHING_TO_SELL,
    RATE_LIMITED: errors.RATE_LIMITED,
    NETWORK: errors.NETWORK,
    TIMEOUT: errors.TIMEOUT,
  };
  const message = known[failure.code] ?? failure.detail ?? text.genericError;
  return new RealError(failure.status, failure.code, message, failure.correlationId ?? null, max);
}

/** Calls the API and turns any failure into a {@link RealError}. */
export async function realCall<T>(
  call: () => Promise<T>,
  context?: { maxCap?: bigint },
): Promise<T> {
  try {
    return await call();
  } catch (failure) {
    throw toRealError(failure, context);
  }
}

/** The REAL reads of a GuestApi instance, converted. */
export function realReads(api: GuestApi) {
  return {
    async release() {
      const view = await realCall(() => api.getRealRelease());
      return {
        network: view.network,
        release: release(view.release) as Release,
        // 261113-trading-real-bot U7 (BR8.1): an older API without the field has the exits off.
        exitsEnabled: view.exitsEnabled === true,
      };
    },
    async wallet() {
      return toBotWallet(await realCall(() => api.getBotWallet()));
    },
    async pnl() {
      return toPnl(await realCall(() => api.getRealPnl()));
    },
    async orders(request: { state?: Order['state']; cursor?: string | null; limit?: number }) {
      const page = await realCall(() => api.listRealOrders(request));
      return {
        items: page.items.map(toOrder),
        nextCursor: page.nextCursor ?? null,
        total: page.total,
      };
    },
    async signingSession(realOrderId: string) {
      return toSigningSession(await realCall(() => api.getRealSigningSession(realOrderId)));
    },
    async withdrawal(withdrawalId: string) {
      return toWithdrawal(await realCall(() => api.getRealWithdrawal(withdrawalId)));
    },
    async withdrawals() {
      const page = await realCall(() => api.listRealWithdrawals());
      return (page.items ?? []).map(toWithdrawal);
    },
  };
}

export type RealReads = ReturnType<typeof realReads>;

export { isUnknown };
