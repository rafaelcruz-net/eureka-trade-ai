import type {
  Assessment,
  KnownValue,
  ScannerPage,
  ScannerRow,
  SourceStatus,
  UnknownValue,
} from '@/lib/api/guest-api';

/** Test fixtures typed with the generated contract types (synthetic mints only). */
export const SOURCE = { sourceId: 'sim-a', name: 'Fonte simulada A', simulated: true };
export const SOURCE_B = { sourceId: 'sim-b', name: 'Fonte simulada B', simulated: true };

export const MINT_A = 'AAAAmint11111111111111111111111111111111AAAA';
export const MINT_B = 'BBBBmint22222222222222222222222222222222BBBB';

export const known = (value: string, stale = false): KnownValue => ({
  status: 'KNOWN',
  value,
  source: SOURCE,
  observedAt: '2026-10-28T12:00:00Z',
  stale,
});

export const unknown: UnknownValue = { status: 'UNKNOWN', reason: 'NOT_PROVIDED', source: SOURCE };

export function row(mint: string, overrides: Partial<ScannerRow> = {}): ScannerRow {
  return {
    chain: 'solana',
    mint,
    symbol: 'PEPE',
    coverage: { informed: 5, total: 5 },
    price: known('0.000012345'),
    liquidity: known('25000.50'),
    volume24h: known('1234567.89'),
    holders: known('321'),
    ageMinutes: known('12'),
    risk: { status: 'AVAILABLE', score: 45, band: 'MEDIUM' },
    ...overrides,
  };
}

export function sourceStatus(
  status: SourceStatus['status'],
  source = SOURCE,
  lastUpdatedAt: string | null = '2026-10-28T12:00:00Z',
): SourceStatus {
  return lastUpdatedAt === null ? { source, status } : { source, status, lastUpdatedAt };
}

export function page(items: ScannerRow[], overrides: Partial<ScannerPage> = {}): ScannerPage {
  return {
    items,
    nextCursor: null,
    total: items.length,
    sources: [sourceStatus('OK')],
    excludedByUnknown: 0,
    pendingNewTokens: 0,
    ...overrides,
  };
}

export function assessment(overrides: Partial<Assessment> = {}): Assessment {
  return {
    risk: { status: 'AVAILABLE', score: 61, band: 'MEDIUM' },
    coverage: '100.0000',
    minCoverage: '80.0000',
    weightSetVersion: '1',
    inValidation: true,
    decisionHint: 'ELIGIBLE',
    bandRaisedByUnknown: false,
    computedAt: '2026-10-28T12:00:00Z',
    factors: [
      {
        factorId: 'LIQUIDITY',
        label: 'Liquidity',
        weight: '35.0000',
        input: known('5500'),
        factorRisk: 80,
        contribution: '28.0000',
      },
      {
        factorId: 'HOLDERS',
        label: 'Holders',
        weight: '25.0000',
        input: known('200', true),
        factorRisk: 60,
        contribution: '15.0000',
      },
      {
        factorId: 'VOLUME_24H',
        label: 'Volume 24h',
        weight: '20.0000',
        input: known('10000'),
        factorRisk: 60,
        contribution: '12.0000',
      },
      {
        factorId: 'PRICE_CHANGE_1H',
        label: 'Variação de preço em 1 hora',
        weight: '20.0000',
        input: { status: 'UNKNOWN', reason: 'NOT_YET_OBSERVED' },
        factorRisk: 100,
        contribution: { status: 'UNKNOWN', reason: 'NOT_YET_OBSERVED' },
      },
    ],
    ...overrides,
  };
}
