import { formatDecimal } from '@/lib/format/format';

/**
 * Sign of a DecimalString read as TEXT (project.md: money, quantities and P&L are never JavaScript numbers). Color is
 * never the only signal: the screens always show the sign and a word next to it.
 */
export type Sign = 'gain' | 'loss' | 'zero';

const DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/;

export function signOf(value: string): Sign {
  const match = DECIMAL.exec(value);
  if (!match) {
    throw new Error('Not a decimal string');
  }
  const digits = `${match[2]}${match[3] ?? ''}`;
  if (/^0+$/.test(digits)) {
    return 'zero';
  }
  return match[1] === '-' ? 'loss' : 'gain';
}

/** "+48,425000", "−21,604408" or "0,000000" (the minus is the typographic one, read aloud as "menos"). */
export function signedText(value: string): string {
  const sign = signOf(value);
  const absolute = value.startsWith('-') ? value.slice(1) : value;
  const formatted = formatDecimal(absolute);
  if (sign === 'zero') {
    return formatted;
  }
  return sign === 'loss' ? `−${formatted}` : `+${formatted}`;
}
