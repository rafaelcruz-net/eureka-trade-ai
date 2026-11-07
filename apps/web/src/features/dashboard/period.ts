/**
 * The P&L period of the dashboard (BR3.1): `from` is 00:00 UTC of the current UTC day and `to` is now, both read
 * from the app clock and sent as UTC instants (project.md); the label shows the start of the day in the browser's
 * time zone. At the UTC midnight the period is computed again.
 */
const DAY_MS = 86_400_000;

export interface DayPeriod {
  from: string;
  to: string;
}

function startOfUtcDay(now: Date): Date {
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
}

export function utcDayPeriod(now: Date): DayPeriod {
  return { from: startOfUtcDay(now).toISOString(), to: now.toISOString() };
}

/** Milliseconds from {@code now} to the next 00:00 UTC (never 0: exactly at midnight it is a whole day). */
export function msUntilNextUtcMidnight(now: Date): number {
  return startOfUtcDay(now).getTime() + DAY_MS - now.getTime();
}
