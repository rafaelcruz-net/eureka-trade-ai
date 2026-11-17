import {
  clockSkewMs,
  formatCountdown,
  milestoneCrossed,
  remainingMs,
  remainingSeconds,
} from '@/lib/time/countdown';

/**
 * 261113-trading-real-bot U6, Step 7 (BR3.2; U6-NFR3.2, U6-NFR6.3): the arithmetic of the signing countdown, outside the
 * REAL folders. With the browser clock 30 s behind, on time or 30 s ahead of the server, the seconds left are the same.
 */
const SERVER_NOW = Date.parse('2026-11-16T12:00:00Z');
const DEADLINE = '2026-11-16T12:00:10Z';

describe('countdown', () => {
  it.each([-30_000, 0, 30_000])(
    'with the browser clock off by %i ms the seconds left follow the server',
    (offset) => {
      const browserNow = SERVER_NOW + offset;
      const skew = clockSkewMs('2026-11-16T12:00:00Z', browserNow);

      expect(remainingSeconds(DEADLINE, browserNow, skew)).toBe(10);
      expect(remainingSeconds(DEADLINE, browserNow + 4_200, skew)).toBe(6);
      expect(remainingMs(DEADLINE, browserNow + 10_000, skew)).toBe(0);
    },
  );

  it('without serverTime, or with an unreadable one, the browser clock counts', () => {
    expect(clockSkewMs(null, SERVER_NOW)).toBe(0);
    expect(clockSkewMs('not a date', SERVER_NOW)).toBe(0);
    expect(remainingSeconds(DEADLINE, SERVER_NOW + 30_000, 0)).toBe(0);
  });

  it('never goes below zero', () => {
    expect(remainingMs(DEADLINE, SERVER_NOW + 60_000, 0)).toBe(0);
  });

  it('formats minutes and seconds', () => {
    expect(formatCountdown(8)).toBe('0:08');
    expect(formatCountdown(65)).toBe('1:05');
    expect(formatCountdown(-3)).toBe('0:00');
  });

  it('crosses the marks of 10 s and 5 s once each', () => {
    expect(milestoneCrossed(null, 10)).toBe(10);
    expect(milestoneCrossed(11, 10)).toBe(10);
    expect(milestoneCrossed(10, 9)).toBeNull();
    expect(milestoneCrossed(6, 5)).toBe(5);
    expect(milestoneCrossed(5, 4)).toBeNull();
    expect(milestoneCrossed(null, 30)).toBeNull();
  });
});
