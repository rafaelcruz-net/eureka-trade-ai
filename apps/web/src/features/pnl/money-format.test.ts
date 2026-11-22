import { formatSol, formatUsd, solSign, usdSign } from '@/features/pnl/money-format';
import { formatSolCompact } from '@/features/real/money';

/**
 * 261121-paper-real-unico U1, Step 19 (BR7.1, BR7.2, BR7.5): the money texts of the blocks by mode and of the list.
 * SOL is exact, trailing zeros cut, at least 2 places, never rounded; US$ has 2 places, cut toward zero; the sign is
 * written ("+", "−") and nothing is ever a JavaScript number.
 */
describe('SOL of the blocks (BR7.1)', () => {
  it.each([
    ['31200000', '+0,0312 SOL'],
    ['2500000000', '+2,50 SOL'],
    ['1', '+0,000000001 SOL'],
    ['0', '0,00 SOL'],
    ['-120000000', '−0,12 SOL'],
    ['1234500000000', '+1.234,50 SOL'],
  ])('%s lamports is %s', (lamports, expected) => {
    expect(formatSol(lamports)).toBe(expected);
  });

  it('writes an unsigned amount for a limit and refuses a text that is not an integer', () => {
    expect(formatSolCompact('2500000000')).toBe('2,50 SOL');
    expect(() => formatSolCompact('1.5')).toThrow(TypeError);
  });

  it('says gain, loss or zero of the lamports', () => {
    expect(solSign('5')).toBe('gain');
    expect(solSign('-5')).toBe('loss');
    expect(solSign('0')).toBe('zero');
  });
});

describe('US$ of the blocks (BR7.2)', () => {
  it.each([
    ['12.40', '+US$ 12,40'],
    ['-3.1', '−US$ 3,10'],
    ['0', 'US$ 0,00'],
    ['0.000000', 'US$ 0,00'],
    ['1234567.891', '+US$ 1.234.567,89'],
    ['-0.009', 'US$ 0,00'],
    ['8.850001', '+US$ 8,85'],
  ])('%s is %s', (value, expected) => {
    expect(formatUsd(value)).toBe(expected);
  });

  it('cuts toward zero and never rounds up', () => {
    expect(formatUsd('9.999999')).toBe('+US$ 9,99');
    expect(formatUsd('-9.999999')).toBe('−US$ 9,99');
  });

  it('says gain, loss or zero and refuses a text that is not a decimal', () => {
    expect(usdSign('0.01')).toBe('gain');
    expect(usdSign('-0.01')).toBe('loss');
    expect(usdSign('0.001')).toBe('zero');
    expect(() => formatUsd('1,5')).toThrow('Not a decimal string');
    expect(() => usdSign('abc')).toThrow('Not a decimal string');
  });
});
