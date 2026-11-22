import type {
  LedgerEntry,
  PaperModeBlock,
  PaperTrade,
  PnlByMode,
  PnlReport,
  RealModeBlock,
  ReconciliationResult,
} from '@/lib/api/guest-api';

/** 261121-paper-real-unico U1: a PAPER bot and a REAL bot of the P&L by mode (synthetic ids). */
export const PAPER_BOT_ID = 'b0700000-0000-4000-8000-0000000000a1';
export const REAL_BOT_ID = 'b0700000-0000-4000-8000-0000000000b2';

/** The PAPER block of C4 with one bot: realized 12.40, fees 0.40, nothing open; total 12.00 (BR5.4). */
export const PAPER_BLOCK: PaperModeBlock = {
  status: 'AVAILABLE',
  market: 'PAPER',
  currency: 'USD',
  disclaimer: 'O resultado simulado não garante o resultado real.',
  empty: false,
  total: { status: 'KNOWN', value: '12.000000' },
  realized: '12.400000',
  unrealized: { status: 'KNOWN', value: '0.000000' },
  unrealizedAsOf: '2026-10-28T15:00:00Z',
  fees: '0.400000',
  bots: [
    {
      botId: PAPER_BOT_ID,
      name: 'Simulado A',
      botStatus: 'ACTIVE',
      total: { status: 'KNOWN', value: '12.000000' },
      realized: '12.400000',
      unrealized: { status: 'KNOWN', value: '0.000000' },
      fees: '0.400000',
    },
  ],
};

/** The REAL block of C4 with one bot: total 31,200,000 lamports (+0,0312 SOL, BR7.1). */
export const REAL_BLOCK: RealModeBlock = {
  status: 'AVAILABLE',
  market: 'REAL',
  currency: 'SOL',
  empty: false,
  totalLamports: { status: 'KNOWN', value: '31200000' },
  realizedLamports: '-5000',
  unrealizedLamports: { status: 'KNOWN', value: '31205000' },
  unrealizedAsOf: '2026-10-28T15:00:00Z',
  feesLamports: '10000',
  bots: [
    {
      botId: REAL_BOT_ID,
      name: 'Real B',
      botStatus: 'PAUSED',
      totalLamports: { status: 'KNOWN', value: '31200000' },
      realizedLamports: '-5000',
      unrealizedLamports: { status: 'KNOWN', value: '31205000' },
      feesLamports: '10000',
      prorated: { summary: [], count: 0, lines: [] },
    },
  ],
  unowned: null,
};

export const BY_MODE: PnlByMode = { period: { kind: 'ALL' }, paper: PAPER_BLOCK, real: REAL_BLOCK };

/** Synthetic mints of the U7 screen tests (never real tokens). */
export const MINT_A = 'MintAaaa1111111111111111111111111111111111';
export const MINT_B = 'MintBbbb2222222222222222222222222222222222';

const SOURCE = { sourceId: 'sim-a', name: 'Simulado A', simulated: true };

/** C1 sold and C9 open: known values, not partial (functional-spec.md catalogue). */
export const REPORT: PnlReport = {
  from: '2026-10-22T03:00:00Z',
  to: '2026-10-29T03:00:00Z',
  realized: '48.425000',
  unrealized: { status: 'KNOWN', value: '18.298232' },
  fees: '1.050675',
  partial: false,
  empty: false,
  unrealizedAsOf: '2026-10-28T12:00:00Z',
  byToken: [
    {
      chain: 'solana',
      mint: MINT_A,
      realized: '48.425000',
      unrealized: { status: 'KNOWN', value: '0.000000' },
      fees: '0.748875',
      mark: {
        status: 'KNOWN',
        value: '1.500000',
        source: SOURCE,
        observedAt: '2026-10-28T12:00:00Z',
        stale: false,
      },
      noLiquidity: false,
    },
    {
      chain: 'solana',
      mint: MINT_B,
      realized: '0.000000',
      unrealized: { status: 'KNOWN', value: '18.298232' },
      fees: '0.301800',
      mark: {
        status: 'KNOWN',
        value: '1.200000',
        source: SOURCE,
        observedAt: '2026-10-28T12:00:00Z',
        stale: false,
      },
      noLiquidity: false,
    },
  ],
};

/** C8 with a C2 loss: one line without liquidity, total unknown and partial. */
export const PARTIAL_REPORT: PnlReport = {
  ...REPORT,
  realized: '-21.064000',
  unrealized: { status: 'UNKNOWN', reason: 'NO_LIQUIDITY' },
  partial: true,
  byToken: [
    {
      chain: 'solana',
      mint: MINT_A,
      realized: '-21.064000',
      unrealized: { status: 'UNKNOWN', reason: 'NO_LIQUIDITY' },
      fees: '0.540408',
      mark: {
        status: 'KNOWN',
        value: '0.800000',
        source: SOURCE,
        observedAt: '2026-10-28T11:58:00Z',
        stale: false,
      },
      noLiquidity: true,
    },
  ],
};

export const ORDER_ID = '7a1d5b60-0000-4000-8000-0000000000c1';
export const BOT_ID = 'b0700000-0000-4000-8000-0000000000b7';

export const ENTRIES: LedgerEntry[] = [
  {
    entryId: 'e0000000-0000-4000-8000-000000000003',
    mode: 'PAPER',
    botId: BOT_ID,
    type: 'FEE',
    amount: '-0.301800',
    asset: 'USDC',
    referenceId: ORDER_ID,
    orderId: ORDER_ID,
    simulationPolicyVersion: 'v1',
    createdAt: '2026-10-28T12:00:00Z',
  },
  {
    entryId: 'e0000000-0000-4000-8000-000000000002',
    mode: 'PAPER',
    botId: BOT_ID,
    type: 'SLIPPAGE',
    amount: '-0.600000',
    asset: 'USDC',
    referenceId: ORDER_ID,
    orderId: ORDER_ID,
    simulationPolicyVersion: 'v1',
    createdAt: '2026-10-28T12:00:00Z',
  },
  {
    entryId: 'e0000000-0000-4000-8000-000000000001',
    mode: 'PAPER',
    botId: BOT_ID,
    type: 'FUNDING',
    amount: '1000.000000',
    asset: 'USDC',
    referenceId: 'fund-key',
    createdAt: '2026-10-28T11:00:00Z',
  },
];

export const TRADE: PaperTrade = {
  orderId: ORDER_ID,
  mode: 'PAPER',
  kind: 'TRADE',
  side: 'BUY',
  chain: 'solana',
  tokenMint: MINT_A,
  status: 'FILLED',
  requestedQuantity: '100',
  filledQuantity: '100',
  referencePrice: '1.000000000000',
  quotedPrice: '1.005000000000',
  fillPrice: '1.006000000000',
  spreadPct: '0.5000',
  impactPct: '0.1000',
  slippage: '0.6000',
  liquidityConsumedPct: '0.1000',
  grossAmount: '100.600000',
  fees: '0.301800',
  marketSource: 'sim-a',
  marketObservedAt: '2026-10-28T12:00:00Z',
  simulationPolicyVersion: 'v1',
  limitsVersion: 3,
  idempotencyKey: 'cycle-key-1',
  createdAt: '2026-10-28T12:00:00Z',
  filledAt: '2026-10-28T12:00:00Z',
};

export const NOT_FILLED: PaperTrade = {
  ...TRADE,
  orderId: '7a1d5b60-0000-4000-8000-0000000000c2',
  status: 'NOT_FILLED',
  notFilledReason: 'INSUFFICIENT_BALANCE',
  filledQuantity: undefined,
  referencePrice: undefined,
  quotedPrice: undefined,
  fillPrice: undefined,
  spreadPct: undefined,
  impactPct: undefined,
  slippage: undefined,
  liquidityConsumedPct: undefined,
  grossAmount: undefined,
  fees: undefined,
  marketSource: undefined,
  marketObservedAt: undefined,
  simulationPolicyVersion: undefined,
  filledAt: undefined,
};

export const DIVERGENT: ReconciliationResult = {
  status: 'DIVERGENT',
  trigger: 'SCHEDULED',
  checkedAt: '2026-10-28T12:00:00Z',
  divergenceCount: 2,
  divergences: [
    {
      botId: BOT_ID,
      orderId: ORDER_ID,
      kind: 'AMOUNT_MISMATCH',
      expected: '-100.901800',
      found: '-100.901801',
    },
    {
      botId: BOT_ID,
      kind: 'ORPHAN_ENTRY',
      entryId: 'e0000000-0000-4000-8000-000000000009',
      expected: '0.000000',
      found: '-1.000000',
    },
  ],
};
