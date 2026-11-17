import {
  checkTriggers,
  compareDecimal,
  parseUsd,
  scaled,
  showUsd,
  toConditional,
  toQuote,
  type Quote,
} from '@/features/real/conditionals';
import { isUnknown } from '@/features/real/money';

/**
 * 261113-trading-real-bot U7, Step 14, written after the code (BR3.2, BR3.4, BR2.6, U7-NFR7.1): the prices of the
 * conditional exits are decimal text from the input to the API and compared as integers scaled to 18 places, never as
 * floating point; the checks mirror the server, which stays the authority.
 */
const QUOTE: Quote = {
  mint: 'TestMint111111111111111111111111111111111111',
  position: 5_000n,
  price: '0.0001',
  source: 'sim-a',
  observedAt: '2026-11-17T12:00:00Z',
  minStop: '0.00005',
  maxTakeProfit: '0.001',
};

describe('parseUsd', () => {
  it('accepts a comma or a point and normalizes to a DecimalString', () => {
    expect(parseUsd('0,00008')).toEqual({ ok: true, value: '0.00008' });
    expect(parseUsd(' 0.000150 ')).toEqual({ ok: true, value: '0.00015' });
    expect(parseUsd('012')).toEqual({ ok: true, value: '12' });
  });

  it('refuses empty text, a sign, an exponent, zero and more than 18 places', () => {
    expect(parseUsd('')).toEqual({ ok: false, reason: 'empty' });
    expect(parseUsd('-1')).toEqual({ ok: false, reason: 'format' });
    expect(parseUsd('8e-5')).toEqual({ ok: false, reason: 'format' });
    expect(parseUsd('0,000')).toEqual({ ok: false, reason: 'format' });
    expect(parseUsd('0.0000000000000000001')).toEqual({ ok: false, reason: 'decimals' });
    expect(parseUsd('0.1000000000000000000')).toEqual({ ok: true, value: '0.1' });
  });
});

describe('compareDecimal', () => {
  it('compares exactly at 18 places, where floating point would not', () => {
    expect(scaled('0.1')).toBe(100000000000000000n);
    expect(compareDecimal('0.100000000000000001', '0.1')).toBe(1);
    expect(compareDecimal('0.3', '0.30')).toBe(0);
    expect(compareDecimal('2', '10')).toBe(-1);
  });
});

describe('checkTriggers', () => {
  it('accepts an OCO with the stop below and the take-profit above the current price', () => {
    expect(checkTriggers('OCO', '0.00008', '0.00015', QUOTE)).toBeNull();
  });

  it('refuses in the order of the server: unknown price, order of the OCO, side, bounds', () => {
    expect(
      checkTriggers('STOP', '0.00008', null, {
        ...QUOTE,
        price: toQuote({
          positionMint: QUOTE.mint,
          positionBaseUnits: '5000',
          priceStatus: 'UNKNOWN',
        }).price,
      }),
    ).toBe('PRICE_UNKNOWN');
    expect(checkTriggers('OCO', '0.00009', '0.00008', QUOTE)).toBe('TP_NOT_ABOVE_SL');
    expect(checkTriggers('STOP', '0.0001', null, QUOTE)).toBe('TRIGGER_ON_WRONG_SIDE');
    expect(checkTriggers('TAKE_PROFIT', null, '0.00009', QUOTE)).toBe('TRIGGER_ON_WRONG_SIDE');
    expect(checkTriggers('STOP', '0.00004', null, QUOTE)).toBe('TRIGGER_OUT_OF_BOUNDS');
    expect(checkTriggers('TAKE_PROFIT', null, '0.0012', QUOTE)).toBe('TRIGGER_OUT_OF_BOUNDS');
    expect(checkTriggers('STOP', '0.00005', null, QUOTE)).toBeNull();
    expect(checkTriggers('TAKE_PROFIT', null, '0.001', QUOTE)).toBeNull();
  });
});

describe('conversions', () => {
  it('turns the conditional of the contract into bigint and explicit nulls', () => {
    const conditional = toConditional({
      conditionalOrderId: '0c0c0000-0000-4000-8000-0000000000c1',
      positionMint: QUOTE.mint,
      kind: 'OCO',
      stopPriceUsd: '0.00008',
      takeProfitPriceUsd: '0.00015',
      amountBaseUnits: '5000',
      state: 'FILLED',
      filledSide: 'STOP',
      needsUserCancellation: false,
      createdAt: '2026-11-17T12:00:00Z',
    });

    expect(conditional.amount).toBe(5_000n);
    expect(conditional.filledSide).toBe('STOP');
    expect(conditional.txSignature).toBeNull();
  });

  it('an unknown price of the quote is the explicit Unknown, never zero', () => {
    const quote = toQuote({
      positionMint: QUOTE.mint,
      positionBaseUnits: '0',
      priceStatus: 'UNKNOWN',
    });

    expect(isUnknown(quote.price)).toBe(true);
    expect(quote.minStop).toBeNull();
    expect(showUsd('0.00008')).toBe('0,00008');
  });
});
