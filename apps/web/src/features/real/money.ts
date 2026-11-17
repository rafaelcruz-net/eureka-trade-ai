/**
 * 261113-trading-real-bot U6 (BR5.1, BR5.2, BR8.2, BR8.7; U6-NFR7.1 to U6-NFR7.3, U6-NFR7.5): the SINGLE parser and
 * formatter of SOL and token amounts of the REAL screens. Everything is bigint: a lamport or a base unit is never a
 * JavaScript number (project.md: no floating point for money), and the text of the screen becomes lamports here and
 * only here. A missing market value is the explicit {@link Unknown}, never zero. This file is the named exception of
 * the lint block of the REAL folders (eslint.config.mjs) and keeps every other ban of it.
 */
export const SOL_DECIMALS = 9;
export const LAMPORTS_PER_SOL = 1_000_000_000n;
/** The maximum cap of the REAL (US2.1, AC2.1.2): 10 SOL. */
export const MAX_CAP_LAMPORTS = 10n * LAMPORTS_PER_SOL;

/** A value the server did not send (a missing price, an unknown unrealized P&L). Never zero. */
export type Unknown = { readonly kind: 'UNKNOWN' };
export const UNKNOWN: Unknown = Object.freeze({ kind: 'UNKNOWN' as const });

export function isUnknown(value: unknown): value is Unknown {
  return typeof value === 'object' && value !== null && (value as Unknown).kind === 'UNKNOWN';
}

export type SolParseFailure =
  | 'EMPTY'
  | 'INVALID'
  | 'NEGATIVE'
  | 'TOO_MANY_DECIMALS'
  | 'ABOVE_MAX'
  | 'ZERO';

export type SolParse = { ok: true; lamports: bigint } | { ok: false; reason: SolParseFailure };

export interface SolParseOptions {
  /** The largest accepted amount, in lamports (the cap: {@link MAX_CAP_LAMPORTS}). */
  max?: bigint;
  /** Zero is refused (a withdrawal). */
  positive?: boolean;
}

/** Digits, then at most one decimal separator (comma or point) followed by at least one digit. */
const DECIMAL_TEXT = /^(\d+)(?:[.,](\d+))?$/;

/**
 * "3,5" or "3.5" → 3500000000n. Spaces around the value are ignored; anything else that is not digits with one comma or
 * point is refused, and so are more than 9 decimals, a sign, an amount above {@code max} and, with {@code positive},
 * zero.
 */
export function parseSol(text: string, options: SolParseOptions = {}): SolParse {
  const trimmed = text.trim();
  if (trimmed === '') {
    return { ok: false, reason: 'EMPTY' };
  }
  if (trimmed.startsWith('-')) {
    return { ok: false, reason: 'NEGATIVE' };
  }
  const match = DECIMAL_TEXT.exec(trimmed);
  if (!match) {
    return { ok: false, reason: 'INVALID' };
  }
  const fraction = match[2] ?? '';
  if (fraction.length > SOL_DECIMALS) {
    return { ok: false, reason: 'TOO_MANY_DECIMALS' };
  }
  const lamports = BigInt(`${match[1] ?? '0'}${fraction.padEnd(SOL_DECIMALS, '0')}`);
  if (options.max !== undefined && lamports > options.max) {
    return { ok: false, reason: 'ABOVE_MAX' };
  }
  if (options.positive && lamports === 0n) {
    return { ok: false, reason: 'ZERO' };
  }
  return { ok: true, lamports };
}

function groupThousands(digits: string): string {
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, '.');
}

/** {@code value} with {@code decimals} places, pt-BR: "1.234,500000000"; the sign of a negative value is kept. */
function formatFixed(value: bigint, decimals: number): string {
  const negative = value < 0n;
  const digits = (negative ? -value : value).toString().padStart(decimals + 1, '0');
  const whole = groupThousands(digits.slice(0, digits.length - decimals));
  const fraction = decimals > 0 ? `,${digits.slice(digits.length - decimals)}` : '';
  return `${negative ? '-' : ''}${whole}${fraction}`;
}

/** 1250000000n → "1,250000000": always the 9 decimals of SOL (BR8.7). */
export function formatSol(lamports: bigint): string {
  return formatFixed(lamports, SOL_DECIMALS);
}

/** "1,250000000 SOL"; an Unknown is "sem dado" or the given text, never zero (BR8.2). */
export function formatSolAmount(value: bigint | Unknown, unknownText = 'sem dado'): string {
  return isUnknown(value) ? unknownText : `${formatSol(value)} SOL`;
}

/** P&L with the sign written: "+0,084000000 SOL", "-0,120000000 SOL", "0,000000000 SOL". */
export function formatSignedSol(lamports: bigint): string {
  return `${lamports > 0n ? '+' : ''}${formatSol(lamports)} SOL`;
}

const MAX_TOKEN_DECIMALS = 30;

/**
 * A token amount with the decimals of its mint (BR8.7). Without the decimals of the mint, the base units are shown and
 * named as such, never guessed.
 */
export function formatToken(baseUnits: bigint, decimals: number | undefined): string {
  if (decimals === undefined) {
    return `${groupThousands(baseUnits.toString())} unidades base`;
  }
  if (!Number.isInteger(decimals) || decimals < 0 || decimals > MAX_TOKEN_DECIMALS) {
    throw new RangeError('The decimals of a mint are a whole number from 0 to 30');
  }
  return formatFixed(baseUnits, decimals);
}

const INTEGER_TEXT = /^-?\d+$/;

/**
 * U6-NFR7.1: the ONE conversion of a lamport or base unit field of the API to bigint: the integer text of the contract,
 * or a safe whole JSON number. Anything else is a broken answer and throws.
 */
export function toBaseUnits(value: string | number | undefined | null): bigint {
  if (typeof value === 'string' && INTEGER_TEXT.test(value)) {
    return BigInt(value);
  }
  if (typeof value === 'number' && Number.isSafeInteger(value)) {
    return BigInt(value);
  }
  throw new TypeError('An amount of the API is not an integer');
}

/** A field the server may leave out (a price it does not know): the Unknown, never zero (U6-NFR7.5). */
export function toBaseUnitsOrUnknown(value: string | number | undefined | null): bigint | Unknown {
  return value === undefined || value === null ? UNKNOWN : toBaseUnits(value);
}
