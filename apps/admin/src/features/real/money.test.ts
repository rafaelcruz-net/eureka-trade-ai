import {
  formatSignedSol,
  formatSol,
  formatSolAmount,
  formatToken,
  isUnknown,
  LAMPORTS_PER_SOL,
  MAX_CAP_LAMPORTS,
  parseSol,
  toBaseUnits,
  toBaseUnitsOrUnknown,
  UNKNOWN,
} from './money';

/**
 * 261113-trading-real-bot U6, Step 4, written before the code (BR5.1, BR5.2, BR8.2, BR8.7; U6-NFR7.1, U6-NFR7.2,
 * U6-NFR7.5): the single parser and formatter of SOL and token amounts, on bigint only. The edges of the rules: 0, 9
 * and 10 decimals, comma and point, spaces, sign, the maximum of 10 SOL, values above 2^53, and the explicit Unknown.
 */
describe('parseSol', () => {
  it('reads "3,5" and "3.5" as 3500000000 lamports, exactly (S9)', () => {
    expect(parseSol('3,5')).toEqual({ ok: true, lamports: 3_500_000_000n });
    expect(parseSol('3.5')).toEqual({ ok: true, lamports: 3_500_000_000n });
  });

  it('reads 0 and 9 decimals and refuses 10 decimals (S10)', () => {
    expect(parseSol('0')).toEqual({ ok: true, lamports: 0n });
    expect(parseSol('0,000000001')).toEqual({ ok: true, lamports: 1n });
    expect(parseSol('0,0000000001')).toEqual({ ok: false, reason: 'TOO_MANY_DECIMALS' });
  });

  it('ignores the spaces around the value but not inside it', () => {
    expect(parseSol('  1,25  ')).toEqual({ ok: true, lamports: 1_250_000_000n });
    expect(parseSol('1 25')).toEqual({ ok: false, reason: 'INVALID' });
  });

  it('refuses empty, negative, signs and anything that is not a decimal', () => {
    expect(parseSol('')).toEqual({ ok: false, reason: 'EMPTY' });
    expect(parseSol('   ')).toEqual({ ok: false, reason: 'EMPTY' });
    expect(parseSol('-1')).toEqual({ ok: false, reason: 'NEGATIVE' });
    expect(parseSol('+1')).toEqual({ ok: false, reason: 'INVALID' });
    expect(parseSol('1e3')).toEqual({ ok: false, reason: 'INVALID' });
    expect(parseSol('1.000,5')).toEqual({ ok: false, reason: 'INVALID' });
    expect(parseSol(',5')).toEqual({ ok: false, reason: 'INVALID' });
    expect(parseSol('5,')).toEqual({ ok: false, reason: 'INVALID' });
  });

  it('accepts exactly the maximum of 10 SOL and refuses one lamport above it (S8)', () => {
    expect(parseSol('10', { max: MAX_CAP_LAMPORTS })).toEqual({
      ok: true,
      lamports: 10n * LAMPORTS_PER_SOL,
    });
    expect(parseSol('10,000000001', { max: MAX_CAP_LAMPORTS })).toEqual({
      ok: false,
      reason: 'ABOVE_MAX',
    });
    expect(parseSol('12', { max: MAX_CAP_LAMPORTS })).toEqual({ ok: false, reason: 'ABOVE_MAX' });
  });

  it('refuses zero when the amount must be positive (a withdrawal)', () => {
    expect(parseSol('0', { positive: true })).toEqual({ ok: false, reason: 'ZERO' });
    expect(parseSol('0,000000001', { positive: true })).toEqual({ ok: true, lamports: 1n });
  });

  it('keeps every lamport of values above 2^53', () => {
    const result = parseSol('9007199,254740993');

    expect(result).toEqual({ ok: true, lamports: 9_007_199_254_740_993n });
  });
});

describe('formatSol and formatSolAmount', () => {
  it('shows 9 decimals in pt-BR, grouping the thousands with a point (BR8.7)', () => {
    expect(formatSol(1_250_000_000n)).toBe('1,250000000');
    expect(formatSol(0n)).toBe('0,000000000');
    expect(formatSol(1n)).toBe('0,000000001');
    expect(formatSol(1_234_567_000_000_001n)).toBe('1.234.567,000000001');
  });

  it('keeps the sign of a negative amount and every digit above 2^53', () => {
    expect(formatSol(-84_000_000n)).toBe('-0,084000000');
    expect(formatSol(9_007_199_254_740_993n)).toBe('9.007.199,254740993');
  });

  it('adds the unit, and an Unknown is "sem dado" or the given text, never zero (BR8.2)', () => {
    expect(formatSolAmount(1_250_000_000n)).toBe('1,250000000 SOL');
    expect(formatSolAmount(UNKNOWN)).toBe('sem dado');
    expect(formatSolAmount(UNKNOWN, 'sem preço')).toBe('sem preço');
    expect(formatSolAmount(UNKNOWN)).not.toContain('0');
  });

  it('writes the sign of a signed amount: + for gains, - for losses, none for zero', () => {
    expect(formatSignedSol(84_000_000n)).toBe('+0,084000000 SOL');
    expect(formatSignedSol(-120_000_000n)).toBe('-0,120000000 SOL');
    expect(formatSignedSol(0n)).toBe('0,000000000 SOL');
  });
});

describe('formatToken', () => {
  it('uses the decimals of the mint', () => {
    expect(formatToken(16_260_000_000n, 6)).toBe('16.260,000000');
    expect(formatToken(5n, 0)).toBe('5');
    expect(formatToken(1n, 9)).toBe('0,000000001');
  });

  it('without the decimals of the mint shows the base units, named as such', () => {
    expect(formatToken(1_000_000n, undefined)).toBe('1.000.000 unidades base');
  });

  it('refuses decimals that are not a small whole number', () => {
    expect(() => formatToken(1n, -1)).toThrow();
    expect(() => formatToken(1n, 1.5)).toThrow();
    expect(() => formatToken(1n, 40)).toThrow();
  });
});

describe('toBaseUnits', () => {
  it('turns the integer text of the API into bigint, above 2^53 too (U6-NFR7.1)', () => {
    expect(toBaseUnits('0')).toBe(0n);
    expect(toBaseUnits('18446744073709551615')).toBe(18_446_744_073_709_551_615n);
    expect(toBaseUnits('-500')).toBe(-500n);
  });

  it('accepts a safe whole JSON number and refuses fractions and unsafe numbers', () => {
    expect(toBaseUnits(42)).toBe(42n);
    expect(() => toBaseUnits(1.5)).toThrow();
    expect(() => toBaseUnits(2 ** 53)).toThrow();
  });

  it('refuses text that is not an integer and a missing value', () => {
    expect(() => toBaseUnits('1.5')).toThrow();
    expect(() => toBaseUnits('abc')).toThrow();
    expect(() => toBaseUnits(undefined)).toThrow();
  });

  it('turns a missing value into the explicit Unknown, never zero (U6-NFR7.5)', () => {
    const missing = toBaseUnitsOrUnknown(undefined);

    expect(isUnknown(missing)).toBe(true);
    expect(missing).not.toBe(0n);
    expect(toBaseUnitsOrUnknown('7')).toBe(7n);
    expect(isUnknown(7n)).toBe(false);
  });
});
