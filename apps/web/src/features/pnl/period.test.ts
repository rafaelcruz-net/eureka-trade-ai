import { addDays, startOfLocalDay, todayIn, toUtcPeriod } from '@/features/pnl/period';
import { signedText, signOf } from '@/features/pnl/decimal';

describe('period of S6 in the person time zone, sent in UTC', () => {
  it('the start of a São Paulo day is 03:00 UTC', () => {
    expect(startOfLocalDay('2026-10-28', 'America/Sao_Paulo')).toBe('2026-10-28T03:00:00Z');
  });

  it('the last chosen day is included: to is the start of the next local day', () => {
    expect(toUtcPeriod('2026-10-22', '2026-10-28', 'America/Sao_Paulo')).toEqual({
      from: '2026-10-22T03:00:00Z',
      to: '2026-10-29T03:00:00Z',
    });
  });

  it('a time zone ahead of UTC moves the instant back', () => {
    expect(startOfLocalDay('2026-10-28', 'Asia/Tokyo')).toBe('2026-10-27T15:00:00Z');
    expect(startOfLocalDay('2026-10-28', 'UTC')).toBe('2026-10-28T00:00:00Z');
  });

  it('follows a daylight saving change (New York, 1 November 2026)', () => {
    expect(startOfLocalDay('2026-10-31', 'America/New_York')).toBe('2026-10-31T04:00:00Z');
    expect(startOfLocalDay('2026-11-02', 'America/New_York')).toBe('2026-11-02T05:00:00Z');
  });

  it('refuses a reversed period and more than 366 days', () => {
    expect(toUtcPeriod('2026-10-29', '2026-10-28', 'UTC')).toBeNull();
    expect(toUtcPeriod('2025-10-27', '2026-10-28', 'UTC')).toBeNull();
    expect(toUtcPeriod('2025-10-28', '2026-10-28', 'UTC')).not.toBeNull();
    expect(toUtcPeriod('ontem', '2026-10-28', 'UTC')).toBeNull();
  });

  it('days are calendar arithmetic and today is the local day', () => {
    expect(addDays('2026-10-28', -6)).toBe('2026-10-22');
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
    expect(todayIn('America/Sao_Paulo', new Date('2026-10-29T02:00:00Z'))).toBe('2026-10-28');
    expect(() => addDays('28/10/2026', 1)).toThrow();
  });

  // 261121-paper-real-unico U3, Step 18 (BR2.2, BR2.3; AC7.1.1, AC7.1.3): period.ts does not change; new cases only.
  it('"Hoje" in São Paulo at 2026-11-23T02:30Z is the local day 22, from 03:00Z to 03:00Z', () => {
    const today = todayIn('America/Sao_Paulo', new Date('2026-11-23T02:30:00Z'));

    expect(today).toBe('2026-11-22');
    expect(toUtcPeriod(today, today, 'America/Sao_Paulo')).toEqual({
      from: '2026-11-22T03:00:00Z',
      to: '2026-11-23T03:00:00Z',
    });
  });

  it('an interval is inclusive of its last day', () => {
    expect(toUtcPeriod('2026-11-01', '2026-11-01', 'UTC')).toEqual({
      from: '2026-11-01T00:00:00Z',
      to: '2026-11-02T00:00:00Z',
    });
  });

  it('366 calendar days in New York across the end of daylight saving time are refused; 365 are accepted', () => {
    // 2025-11-01 starts in EDT (04:00Z) and the day after 2026-11-01 starts in EST (05:00Z): 366 days and one hour.
    expect(toUtcPeriod('2025-11-01', '2026-11-01', 'America/New_York')).toBeNull();
    expect(toUtcPeriod('2025-11-02', '2026-11-01', 'America/New_York')).toEqual({
      from: '2025-11-02T04:00:00Z',
      to: '2026-11-02T05:00:00Z',
    });
  });
});

describe('decimals stay text', () => {
  it('the sign is read from the text, never from a number', () => {
    expect(signOf('48.425000')).toBe('gain');
    expect(signOf('-21.604408')).toBe('loss');
    expect(signOf('0.000000')).toBe('zero');
    expect(signOf('-0.000000')).toBe('zero');
    expect(() => signOf('1e3')).toThrow();
  });

  it('shows the sign and every digit in pt-BR grouping', () => {
    expect(signedText('1047.676125')).toBe('+1.047,676125');
    expect(signedText('-21.604408')).toBe('−21,604408');
    expect(signedText('0.000000')).toBe('0,000000');
    expect(signedText('0.000002010200')).toBe('+0,000002010200');
  });
});
