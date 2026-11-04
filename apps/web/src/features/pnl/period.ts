/**
 * The period of S6 (F7): the person chooses calendar days in THEIR time zone; the API receives UTC instants
 * [from, to) (project.md: instants travel in UTC and are converted only in the interface). The last chosen day is
 * included, so `to` is the start of the next local day.
 */
const DAY = /^(\d{4})-(\d{2})-(\d{2})$/;
const MAX_DAYS = 366;
const DAY_MS = 86_400_000;

export interface UtcPeriod {
  from: string;
  to: string;
}

function parts(day: string): [number, number, number] {
  const match = DAY.exec(day);
  if (!match) {
    throw new Error('Not a calendar day');
  }
  return [
    parseInt(match[1] as string, 10),
    parseInt(match[2] as string, 10),
    parseInt(match[3] as string, 10),
  ];
}

/** Milliseconds the wall clock of `timeZone` is ahead of UTC at the instant `utcMs`. */
function offsetMs(utcMs: number, timeZone: string): number {
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
  const values: Record<string, number> = {};
  for (const part of formatter.formatToParts(new Date(utcMs))) {
    if (part.type !== 'literal') {
      values[part.type] = parseInt(part.value, 10);
    }
  }
  const wall = Date.UTC(
    values.year as number,
    (values.month as number) - 1,
    values.day as number,
    values.hour as number,
    values.minute as number,
    values.second as number,
  );
  return wall - Math.floor(utcMs / 1000) * 1000;
}

/** The instant at which `day` starts in `timeZone`, as ISO 8601 UTC. */
export function startOfLocalDay(day: string, timeZone: string): string {
  const [year, month, date] = parts(day);
  const wallMidnight = Date.UTC(year, month - 1, date);
  let utc = wallMidnight - offsetMs(wallMidnight, timeZone);
  utc = wallMidnight - offsetMs(utc, timeZone);
  return new Date(utc).toISOString().replace('.000Z', 'Z');
}

/** `day` plus `days` calendar days. */
export function addDays(day: string, days: number): string {
  const [year, month, date] = parts(day);
  return new Date(Date.UTC(year, month - 1, date) + days * DAY_MS).toISOString().slice(0, 10);
}

/** The calendar day of `now` in `timeZone`. */
export function todayIn(timeZone: string, now: Date): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now);
}

/**
 * The UTC period of the chosen days, or null when the first day is after the last one or the period has more than
 * 366 days (BR6.1, the API refuses it with 400 anyway).
 */
export function toUtcPeriod(
  fromDay: string,
  toDayInclusive: string,
  timeZone: string,
): UtcPeriod | null {
  if (!DAY.test(fromDay) || !DAY.test(toDayInclusive) || fromDay > toDayInclusive) {
    return null;
  }
  const from = startOfLocalDay(fromDay, timeZone);
  const to = startOfLocalDay(addDays(toDayInclusive, 1), timeZone);
  if (Date.parse(to) - Date.parse(from) > MAX_DAYS * DAY_MS) {
    return null;
  }
  return { from, to };
}
