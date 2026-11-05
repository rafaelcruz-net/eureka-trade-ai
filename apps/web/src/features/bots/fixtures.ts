import type { BotDetail, BotSummary, Decision, StrategyTemplate } from '@/lib/api/guest-api';

/** Fixtures of the bot screens (U5). Mints are fake test addresses, never real ones. */
export const BOT_ID = '0b0e0000-0000-4000-8000-000000000001';
export const MINT = 'TestMint111111111111111111111111111111111111';
export const OTHER_MINT = 'TestMint222222222222222222222222222222222222';

export const TEMPLATE: StrategyTemplate = {
  templateId: 'MOMENTUM_VOLUME',
  version: 'v1',
  name: 'Momentum com volume',
  description: 'Compra quando preço e volume sobem juntos.',
  parameters: [
    {
      key: 'takeProfitPct',
      label: 'Take profit',
      unit: '%',
      min: '1',
      max: '50',
      step: '0.5',
      default: '10',
    },
    {
      key: 'stopLossPct',
      label: 'Stop loss',
      unit: '%',
      min: '1',
      max: '30',
      step: '0.5',
      default: '5',
    },
  ],
};

export const RANGE_TEMPLATE: StrategyTemplate = {
  templateId: 'RANGE_BREAKOUT',
  version: 'v1',
  name: 'Rompimento de faixa',
  description: 'Compra no rompimento da máxima recente.',
  parameters: [],
};

export const LIMITS = {
  maxOrderSize: '100.000000',
  maxTokenExposure: '200.000000',
  maxTotalExposure: '500.000000',
  maxDailyLoss: '50.000000',
  maxDrawdown: '10.0000',
  maxSlippage: '2.0000',
  minLiquidity: '10000.000000',
  minIntervalSeconds: 240,
};

export function detail(overrides: Partial<BotDetail> = {}): BotDetail {
  return {
    botId: BOT_ID,
    name: 'Meu bot',
    mode: 'PAPER',
    state: 'DRAFT',
    pauseReasons: [],
    draftStep: 1,
    templateId: 'MOMENTUM_VOLUME',
    templateVersion: 'v1',
    parameters: { takeProfitPct: '10', stopLossPct: '5' },
    tokenSelection: { tokens: [], rules: [] },
    updatedAt: '2026-10-28T12:00:00Z',
    ...overrides,
  };
}

export function summary(overrides: Partial<BotSummary> = {}): BotSummary {
  return {
    botId: BOT_ID,
    name: 'Meu bot',
    mode: 'PAPER',
    state: 'ACTIVE',
    pauseReasons: [],
    ...overrides,
  };
}

export function decision(overrides: Partial<Decision> = {}): Decision {
  return {
    decisionId: 'd-1',
    cycleId: 'c-1',
    action: 'BUY',
    createdAt: '2026-10-28T12:01:00Z',
    validUntil: '2026-10-28T12:06:00Z',
    limitsVersion: 1,
    strategyVersion: 'v1',
    summary: 'Preço e volume subiram juntos.',
    tokenMint: MINT,
    factors: [
      {
        name: 'priceChange',
        label: 'Variação de preço',
        satisfied: true,
        threshold: '2',
        token: { chain: 'solana', mint: MINT },
        value: { status: 'KNOWN', value: '3.5' },
      },
      {
        name: 'volume',
        satisfied: false,
        token: { chain: 'solana', mint: MINT },
        value: { status: 'UNKNOWN' },
      },
    ],
    inputs: [],
    ...overrides,
  };
}
