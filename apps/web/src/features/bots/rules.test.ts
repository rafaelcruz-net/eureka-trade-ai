import type { BotLimits, StrategyParameter } from '@/lib/api/guest-api';
import {
  compareDecimal,
  isDecimal,
  onStepGrid,
  parseDecimal,
  percentOf,
  scaleOf,
} from '@/features/bots/decimal';
import { botMessages } from '@/features/bots/messages';
import {
  isWizardState,
  reasonsText,
  resumesAtText,
  stateText,
  validCommands,
} from '@/features/bots/states';
import {
  defaultLimits,
  validateBalance,
  validateLimits,
  validateParameters,
  validateSelection,
} from '@/features/bots/validation';

const MINT = 'So11111111111111111111111111111111111111112';
const v = botMessages.validation;

const LIMITS: BotLimits = {
  maxOrderSize: '100.000000',
  maxTokenExposure: '200.000000',
  maxTotalExposure: '500.000000',
  maxDailyLoss: '50.000000',
  maxDrawdown: '10.0000',
  maxSlippage: '2.0000',
  minLiquidity: '10000.000000',
  minIntervalSeconds: 240,
};

describe('exact decimals of the bot screens (no floating point)', () => {
  it('parses, compares and scales decimal text', () => {
    expect(parseDecimal('12.50')).not.toBeNull();
    expect(parseDecimal('1e3')).toBeNull();
    expect(parseDecimal(undefined)).toBeNull();
    expect(isDecimal('-0.000001')).toBe(true);
    expect(isDecimal('abc')).toBe(false);
    expect(compareDecimal('0.1', '0.10')).toBe(0);
    expect(compareDecimal('10000.000001', '10000')).toBe(1);
    expect(compareDecimal('-1', '0')).toBe(-1);
    expect(scaleOf('1.234567')).toBe(6);
    expect(scaleOf('7')).toBe(0);
  });

  it('accepts only values on the step grid inside the range', () => {
    expect(onStepGrid('2.5', '1', '10', '0.5')).toBe(true);
    expect(onStepGrid('2.3', '1', '10', '0.5')).toBe(false);
    expect(onStepGrid('10.5', '1', '10', '0.5')).toBe(false);
    expect(onStepGrid('0.5', '1', '10', '0.5')).toBe(false);
  });

  it('computes proportional defaults HALF_EVEN with 6 places (BR1.6)', () => {
    expect(percentOf('1000', 10, 6)).toBe('100.000000');
    expect(percentOf('1234.567891', 5, 6)).toBe('61.728395');
    expect(defaultLimits('1000.000000')).toEqual({
      ...LIMITS,
      maxDrawdown: '10.0000',
      maxSlippage: '2.0000',
    });
  });
});

describe('client validation repeated by the server (BR1.2 to BR1.5)', () => {
  const spec: StrategyParameter = {
    key: 'takeProfitPct',
    label: 'Take profit',
    unit: '%',
    min: '1',
    max: '50',
    step: '0.5',
    default: '10',
  };

  it('refuses a parameter that is not decimal or off the grid, with the range in the sentence', () => {
    expect(validateParameters([spec], { takeProfitPct: '10' })).toEqual({});
    expect(validateParameters([spec], { takeProfitPct: 'x' })).toEqual({
      'parameters.takeProfitPct': v.notDecimal,
    });
    expect(validateParameters([spec], {})).toEqual({ 'parameters.takeProfitPct': v.notDecimal });
    expect(validateParameters([spec], { takeProfitPct: '60' })).toEqual({
      'parameters.takeProfitPct': v.range('1', '50', '0.5'),
    });
  });

  it('needs a token or a rule; refuses bad and repeated mints, too many items and negative rule values', () => {
    expect(validateSelection({ tokens: [], rules: [] })).toEqual({
      tokenSelection: v.selectionEmpty,
    });
    expect(validateSelection({ tokens: [{ chain: 'solana', mint: MINT }], rules: [] })).toEqual({});
    const errors = validateSelection({
      tokens: [
        { chain: 'solana', mint: 'bad' },
        { chain: 'solana', mint: MINT },
        { chain: 'solana', mint: MINT },
      ],
      rules: [{ field: 'LIQUIDITY', operator: 'GTE', value: '-1' }],
    });
    expect(errors['tokenSelection.tokens[0].mint']).toBe(v.badMint);
    expect(errors['tokenSelection.tokens[2]']).toBe(v.repeatedToken);
    expect(errors['tokenSelection.rules[0].value']).toBe(v.ruleValue);
    const many = validateSelection({
      tokens: Array.from({ length: 11 }, () => ({ chain: 'solana', mint: MINT })),
      rules: Array.from({ length: 6 }, () => ({ field: 'HOLDERS', operator: 'GTE', value: '1' })),
    });
    expect(many['tokenSelection.tokens']).toBe(v.tooManyTokens);
    expect(many['tokenSelection.rules']).toBe(v.tooManyRules);
  });

  it('keeps the virtual balance between the minimum and the cap with up to 6 places (BR1.4)', () => {
    expect(validateBalance('1000.000000')).toEqual({});
    expect(validateBalance('1000.0000001')).toEqual({ virtualBalance: v.balanceFormat });
    expect(validateBalance('10000.000001')).toEqual({ virtualBalance: v.balanceCap });
    expect(validateBalance('10000.01')).toEqual({ virtualBalance: v.balanceCap });
    expect(validateBalance('99.99')).toEqual({ virtualBalance: v.balanceMin });
    expect(validateBalance('mil')).toEqual({ virtualBalance: v.balanceFormat });
  });

  it('refuses incoherent limits: order above token, token above total, percent outside (0, 100]', () => {
    expect(validateLimits(LIMITS)).toEqual({});
    expect(validateLimits({ ...LIMITS, maxOrderSize: '300' }).maxOrderSize).toBe(v.orderAboveToken);
    expect(validateLimits({ ...LIMITS, maxTokenExposure: '600' }).maxTokenExposure).toBe(
      v.tokenAboveTotal,
    );
    expect(validateLimits({ ...LIMITS, maxDrawdown: '0' }).maxDrawdown).toBe(v.percent);
    expect(validateLimits({ ...LIMITS, maxSlippage: '100.01' }).maxSlippage).toBe(v.percent);
    expect(validateLimits({ ...LIMITS, maxDailyLoss: '0' }).maxDailyLoss).toBe(v.positive);
    expect(validateLimits({ ...LIMITS, minLiquidity: '-1' }).minLiquidity).toBe(v.nonNegative);
    expect(validateLimits({ ...LIMITS, minIntervalSeconds: 0 }).minIntervalSeconds).toBe(
      v.interval,
    );
  });
});

describe('states, reasons and commands of the annex', () => {
  it('writes every state and reason in text', () => {
    expect(stateText('CLOSING_PENDING')).toBe('Encerramento pendente');
    expect(reasonsText(['DAILY_LOSS', 'SYSTEM'])).toEqual([
      'perda diária atingida',
      'pausado pelo sistema',
    ]);
  });

  it('offers only the commands the server accepts in each state', () => {
    expect(validCommands('VALIDATED')).toEqual(['start']);
    expect(validCommands('ACTIVE')).toEqual(['pause', 'stop']);
    expect(validCommands('PAUSED')).toEqual(['resume', 'stop']);
    expect(validCommands('DRAFT')).toEqual([]);
    expect(validCommands('CLOSING_PENDING')).toEqual([]);
    expect(validCommands('STOPPED')).toEqual([]);
    expect(isWizardState('DRAFT')).toBe(true);
    expect(isWizardState('ACTIVE')).toBe(false);
  });

  it('shows the restart at the next UTC midnight in the person time zone (AC4.7.6)', () => {
    expect(resumesAtText('2026-10-29T00:00:00Z', 'America/Sao_Paulo')).toBe(
      'volta a operar às 21:00, horário de Brasília',
    );
    expect(resumesAtText('2026-10-29T00:00:00Z', 'Europe/Lisbon')).toBe(
      'volta a operar às 00:00, Europe/Lisbon',
    );
    expect(resumesAtText(undefined, 'UTC')).toBeNull();
  });
});
