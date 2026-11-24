/**
 * 261121-paper-real-unico U3, Step 25 (case 9; AC4.5.1, AC3.10.1): the answers Playwright mounts on C4 and C5 to check
 * 360 px with many prorated shares, a line "Sem bot" and two pages of lines. Every value is SYNTHETIC: no real wallet,
 * mint nor signature; the ids of the bots are the ones the spec created, so the screens find their lines.
 */

/** A synthetic mint in base58 (never a real token). */
export const MOUNTED_MINT = 'MintMounted11111111111111111111111111111111';

const ORIGINS = ['LIQUIDATION', 'CONDITIONAL', 'SOLD_BY_OTHER_BOT'] as const;

/** C4 without `market`: the PAPER block with one bot and the REAL block with 12 shares and a line "Sem bot". */
export function mountedByMode(
  paper: { botId: string; name: string },
  real: { botId: string; name: string },
) {
  return {
    period: { kind: 'ALL' },
    paper: {
      status: 'AVAILABLE',
      market: 'PAPER',
      currency: 'USD',
      disclaimer: 'O resultado simulado não garante o resultado real.',
      empty: false,
      total: { status: 'KNOWN', value: '12.000000' },
      realized: '12.400000',
      unrealized: { status: 'KNOWN', value: '0.000000' },
      unrealizedAsOf: '2026-11-23T12:00:00Z',
      fees: '0.400000',
      bots: [
        {
          botId: paper.botId,
          name: paper.name,
          botStatus: 'ACTIVE',
          total: { status: 'KNOWN', value: '12.000000' },
          realized: '12.400000',
          unrealized: { status: 'KNOWN', value: '0.000000' },
          fees: '0.400000',
        },
      ],
    },
    real: {
      status: 'AVAILABLE',
      market: 'REAL',
      currency: 'SOL',
      empty: false,
      totalLamports: { status: 'KNOWN', value: '56199000' },
      realizedLamports: '24995000',
      unrealizedLamports: { status: 'KNOWN', value: '31215000' },
      unrealizedAsOf: '2026-11-23T12:00:00Z',
      feesLamports: '11000',
      bots: [
        {
          botId: real.botId,
          name: real.name,
          botStatus: 'ACTIVE',
          totalLamports: { status: 'KNOWN', value: '31199000' },
          realizedLamports: '-5000',
          unrealizedLamports: { status: 'KNOWN', value: '31215000' },
          feesLamports: '11000',
          prorated: {
            count: 12,
            summary: [
              { origin: 'LIQUIDATION', realizedLamports: '400000' },
              { origin: 'CONDITIONAL', realizedLamports: '-120000' },
              { origin: 'SOLD_BY_OTHER_BOT', realizedLamports: '50000' },
            ],
            lines: Array.from({ length: 10 }, (_, index) => ({
              at: `2026-11-23T${String(10 + index).padStart(2, '0')}:05:00Z`,
              mint: MOUNTED_MINT,
              origin: ORIGINS[index % 3],
              tokenBaseUnits: String(100 + index),
              receivedLamports: String(300000 + index * 1000),
              feeLamports: '2500',
              realizedLamports: String(10000 + index),
            })),
          },
        },
      ],
      unowned: {
        realizedLamports: '25000000',
        unrealizedLamports: { status: 'KNOWN', value: '0' },
        feesLamports: '1000',
        reasons: ['UNOWNED_EXCESS', 'UNOWNED_NO_COST'],
      },
    },
  };
}

/** The opaque cursor of the second page (synthetic; the mounted route only compares it). */
export const MOUNTED_CURSOR = 'AQAAAAAAAAAAAAAAAAAAAACq7rdq8e7S7a5qY2Fz1A';

function line(index: number) {
  return {
    at: `2026-11-23T${String(23 - (index % 20)).padStart(2, '0')}:${String(index % 60).padStart(2, '0')}:00Z`,
    side: index % 2 === 0 ? 'BUY' : 'SELL',
    mint: MOUNTED_MINT,
    origin: index % 7 === 0 && index > 0 ? 'LIQUIDATION' : 'BOT',
    tokenBaseUnits: String(1000 + index),
    amountLamports: String(500000 + index),
    feeLamports: '5000',
    realizedLamports: index % 2 === 0 ? '0' : String(20000 + index),
  };
}

/** C5: the first page has 50 lines and a cursor; the next one 3 lines and no cursor. */
export function mountedLines(cursor: string | null) {
  if (cursor === MOUNTED_CURSOR) {
    return { currency: 'SOL', items: [50, 51, 52].map(line), nextCursor: null };
  }
  return {
    currency: 'SOL',
    items: Array.from({ length: 50 }, (_, index) => line(index)),
    nextCursor: MOUNTED_CURSOR,
  };
}
