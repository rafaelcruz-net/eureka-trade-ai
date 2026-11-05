/**
 * Exact decimal arithmetic on DecimalString values (project.md: never floating point). A value is held as a BigInt of
 * units at a scale; comparisons, the step grid and the proportional defaults are computed with BigInt only.
 */
const DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/;

export interface Exact {
  units: bigint;
  scale: number;
}

/** Parses a DecimalString; null when the text is not a decimal (never zero by default). */
export function parseDecimal(text: string | null | undefined): Exact | null {
  if (text === null || text === undefined) {
    return null;
  }
  const match = DECIMAL.exec(text.trim());
  if (!match) {
    return null;
  }
  const [, sign, integer, fraction = ''] = match;
  const units = BigInt(`${integer}${fraction}`) * (sign === '-' ? -1n : 1n);
  return { units, scale: fraction.length };
}

export function isDecimal(text: string | null | undefined): boolean {
  return parseDecimal(text) !== null;
}

function rescale(value: Exact, scale: number): bigint {
  return value.units * 10n ** BigInt(scale - value.scale);
}

/** -1, 0 or 1, like a.compareTo(b); both must be decimals. */
export function compareDecimal(a: string, b: string): number {
  const left = parseDecimal(a);
  const right = parseDecimal(b);
  if (!left || !right) {
    throw new Error('Not a decimal string');
  }
  const scale = Math.max(left.scale, right.scale);
  const difference = rescale(left, scale) - rescale(right, scale);
  return difference === 0n ? 0 : difference > 0n ? 1 : -1;
}

/** The number of decimal places of a DecimalString. */
export function scaleOf(text: string): number {
  return parseDecimal(text)?.scale ?? 0;
}

/** BR1.2: inside [min, max] and (value - min) a multiple of step. */
export function onStepGrid(value: string, min: string, max: string, step: string): boolean {
  if (!isDecimal(value) || compareDecimal(value, min) < 0 || compareDecimal(value, max) > 0) {
    return false;
  }
  const parts = [parseDecimal(value), parseDecimal(min), parseDecimal(step)] as Exact[];
  const scale = Math.max(...parts.map((part) => part.scale));
  const [v, m, s] = parts.map((part) => rescale(part, scale)) as [bigint, bigint, bigint];
  return s !== 0n && (v - m) % s === 0n;
}

/** {@code value x percent / 100}, rounded HALF_EVEN to {@code places} (BR1.6), as a DecimalString. */
export function percentOf(value: string, percent: number, places: number): string {
  const base = parseDecimal(value);
  if (!base) {
    throw new Error('Not a decimal string');
  }
  const numerator = base.units * BigInt(percent);
  const denominator = 100n * 10n ** BigInt(base.scale);
  const scaled = numerator * 10n ** BigInt(places);
  let quotient = scaled / denominator;
  const remainder = scaled % denominator;
  const twice = remainder * 2n;
  if (twice > denominator || (twice === denominator && quotient % 2n !== 0n)) {
    quotient += 1n;
  }
  return toText(quotient, places);
}

function toText(units: bigint, places: number): string {
  const negative = units < 0n;
  const digits = (negative ? -units : units).toString().padStart(places + 1, '0');
  const integer = digits.slice(0, digits.length - places);
  const fraction = digits.slice(digits.length - places);
  return `${negative ? '-' : ''}${integer}${places > 0 ? `.${fraction}` : ''}`;
}
