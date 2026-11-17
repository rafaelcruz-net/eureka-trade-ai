/**
 * 261113-trading-real-bot U6 (BR3.2, BR3.3; U6-NFR3.2, U6-NFR6.3, U6-NFR7.3): the time arithmetic of the signing
 * countdown, OUTSIDE the REAL folders on purpose (it never touches money). The deadline is the server's; the difference
 * of clocks is `serverTime` minus the browser clock when the answer arrived, and it is discounted. Without `serverTime`
 * the browser clock counts. The server still decides the expiry.
 */
export const TICK_MS = 250;
/** U6-NFR6.3: the countdown is announced only at these marks. */
export const MILESTONES: readonly number[] = [10, 5];

/** The difference between the clock of the server and the one of the browser when the answer arrived. */
export function clockSkewMs(serverTime: string | null, receivedAtMs: number): number {
  if (!serverTime) {
    return 0;
  }
  const server = Date.parse(serverTime);
  return Number.isNaN(server) ? 0 : server - receivedAtMs;
}

/** Milliseconds left until the deadline, on the clock of the server (`now + skew`); never negative. */
export function remainingMs(deadline: string, nowMs: number, skewMs: number): number {
  return Math.max(0, Date.parse(deadline) - (nowMs + skewMs));
}

/** Whole seconds left, rounded up, so 0 means the deadline passed. */
export function remainingSeconds(deadline: string, nowMs: number, skewMs: number): number {
  return Math.ceil(remainingMs(deadline, nowMs, skewMs) / 1_000);
}

/** "0:08", "1:05". */
export function formatCountdown(seconds: number): string {
  const safe = Math.max(0, seconds);
  return `${Math.floor(safe / 60)}:${String(safe % 60).padStart(2, '0')}`;
}

/** The mark crossed between two readings of the countdown (10 or 5), or null. */
export function milestoneCrossed(previousSeconds: number | null, seconds: number): number | null {
  for (const mark of MILESTONES) {
    if (seconds <= mark && (previousSeconds === null || previousSeconds > mark)) {
      return mark;
    }
  }
  return null;
}
