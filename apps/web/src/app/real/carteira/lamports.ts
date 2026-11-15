/**
 * SOL and lamports as TEXT with integer arithmetic only (contract "Lamports"; project.md: never floating point for
 * money). 1 SOL = 1,000,000,000 lamports.
 */
const DECIMALS = 9;

/** Shown when the server sent no value: a missing amount is never shown as zero. */
export const UNAVAILABLE = 'indisponível';

/** "1500000000" -> "1,5 SOL"; "0" -> "0 SOL"; a missing value -> "indisponível". */
export function formatSolAmount(lamports: string | undefined): string {
  return lamports === undefined ? UNAVAILABLE : `${formatSol(lamports)} SOL`;
}

/** "1500000000" -> "1,5"; "0" -> "0". */
export function formatSol(lamports: string): string {
  const digits = lamports.replace(/^0+(?=\d)/, '');
  const padded = digits.padStart(DECIMALS + 1, '0');
  const whole = padded.slice(0, padded.length - DECIMALS);
  const fraction = padded.slice(padded.length - DECIMALS).replace(/0+$/, '');
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  return fraction.length > 0 ? `${grouped},${fraction}` : grouped;
}

/** "1,5" or "1.5" -> "1500000000"; null when it is not a positive amount with at most 9 decimals. */
export function parseSol(text: string): string | null {
  const match = /^\s*(\d{1,11})(?:[.,](\d{1,9}))?\s*$/.exec(text);
  if (!match) {
    return null;
  }
  const whole = match[1] ?? '0';
  const fraction = (match[2] ?? '').padEnd(DECIMALS, '0');
  const lamports = `${whole}${fraction}`.replace(/^0+(?=\d)/, '');
  return lamports === '0' ? null : lamports;
}
