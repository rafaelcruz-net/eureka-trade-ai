/**
 * Display formatting. Decimals arrive as exact text (DecimalString) and are formatted by string manipulation only:
 * they are never converted to JavaScript numbers (project.md: no floating point for market values).
 */
const DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/;

/** "1234567.891" → "1.234.567,891" (pt-BR grouping), keeping every digit. */
export function formatDecimal(value: string): string {
  const match = DECIMAL.exec(value);
  if (!match) {
    throw new Error('Not a decimal string');
  }
  const [, sign, integer, fraction] = match;
  const grouped = (integer as string).replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  return `${sign}${grouped}${fraction ? `,${fraction}` : ''}`;
}

/** Instants travel in UTC; conversion to the person's time zone happens only here, in the interface. */
export function formatInstant(iso: string, timeZone?: string): string {
  return new Intl.DateTimeFormat('pt-BR', {
    dateStyle: 'short',
    timeStyle: 'medium',
    timeZone,
  }).format(new Date(iso));
}

export function formatClock(date: Date, timeZone?: string): string {
  return new Intl.DateTimeFormat('pt-BR', { hour: '2-digit', minute: '2-digit', timeZone }).format(
    date,
  );
}

/** First 4 and last 4 characters, so repeated symbols stay distinguishable (BR3.2). */
export function abbreviateMint(mint: string): string {
  return mint.length <= 10 ? mint : `${mint.slice(0, 4)}…${mint.slice(-4)}`;
}
