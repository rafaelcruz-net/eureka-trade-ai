import type { RealConditional, RealConditionalQuote } from '@/lib/api/guest-api';
import { toBaseUnits, UNKNOWN, type Unknown } from '@/features/real/money';

/**
 * 261113-trading-real-bot U7 (BR3.2, BR3.4, BR2.6, BR8.2, U7-NFR7.1): the conditional exits on the browser. Prices are
 * decimal TEXT end to end (the DecimalString of the contract): typed with a comma or a point, compared as integers
 * scaled to 18 places, never as floating point. The checks here only help the person; the server checks everything
 * again and is the authority.
 */
export type ConditionalKind = 'STOP' | 'TAKE_PROFIT' | 'OCO';
export const CONDITIONAL_KINDS: readonly ConditionalKind[] = ['OCO', 'STOP', 'TAKE_PROFIT'];
export type ConditionalState = RealConditional['state'];

export interface Conditional {
  id: string;
  mint: string;
  kind: ConditionalKind;
  stopPriceUsd: string | null;
  takeProfitPriceUsd: string | null;
  amount: bigint;
  state: ConditionalState;
  filledSide: 'STOP' | 'TAKE_PROFIT' | null;
  needsUserCancellation: boolean;
  createdAt: string;
  txSignature: string | null;
}

export interface Quote {
  mint: string;
  position: bigint;
  price: string | Unknown;
  source: string | null;
  observedAt: string | null;
  minStop: string | null;
  maxTakeProfit: string | null;
}

/** States that still hold the position (one per position, BR3.1). */
export const NOT_FINAL: readonly ConditionalState[] = ['PENDING_SIGNATURE', 'OPEN', 'CANCELLING'];

export function toConditional(dto: RealConditional): Conditional {
  return {
    id: dto.conditionalOrderId,
    mint: dto.positionMint,
    kind: dto.kind,
    stopPriceUsd: dto.stopPriceUsd ?? null,
    takeProfitPriceUsd: dto.takeProfitPriceUsd ?? null,
    amount: toBaseUnits(dto.amountBaseUnits),
    state: dto.state,
    filledSide: dto.filledSide ?? null,
    needsUserCancellation: dto.needsUserCancellation,
    createdAt: dto.createdAt,
    txSignature: dto.txSignature ?? null,
  };
}

export function toQuote(dto: RealConditionalQuote): Quote {
  return {
    mint: dto.positionMint,
    position: toBaseUnits(dto.positionBaseUnits),
    price: dto.priceStatus === 'KNOWN' && dto.priceUsd ? dto.priceUsd : UNKNOWN,
    source: dto.source ?? null,
    observedAt: dto.observedAt ?? null,
    minStop: dto.minStopPriceUsd ?? null,
    maxTakeProfit: dto.maxTakeProfitPriceUsd ?? null,
  };
}

/** The scale of every comparison: the 18 places of `numeric(38,18)`. */
const SCALE = 18;
const PRICE = /^(\d{1,20})(?:[.,](\d{1,30}))?$/;

export type PriceInput =
  | { ok: true; value: string }
  | { ok: false; reason: 'empty' | 'format' | 'decimals' };

/** A price typed by the person: digits, an optional comma or point, at most 18 places; never a sign nor an exponent. */
export function parseUsd(text: string): PriceInput {
  const trimmed = text.trim();
  if (trimmed === '') {
    return { ok: false, reason: 'empty' };
  }
  const match = PRICE.exec(trimmed);
  if (!match) {
    return { ok: false, reason: 'format' };
  }
  const whole = match[1] as string;
  const fraction = (match[2] ?? '').replace(/0+$/, '');
  if (fraction.length > SCALE) {
    return { ok: false, reason: 'decimals' };
  }
  if (/^0+$/.test(whole) && fraction === '') {
    return { ok: false, reason: 'format' };
  }
  const normalizedWhole = whole.replace(/^0+(?=\d)/, '');
  return { ok: true, value: fraction === '' ? normalizedWhole : `${normalizedWhole}.${fraction}` };
}

/** A DecimalString as an integer scaled to 18 places. */
export function scaled(decimal: string): bigint {
  const [whole = '0', fraction = ''] = decimal.split('.');
  return BigInt(whole + fraction.padEnd(SCALE, '0').slice(0, SCALE));
}

export function compareDecimal(left: string, right: string): -1 | 0 | 1 {
  const a = scaled(left);
  const b = scaled(right);
  return a === b ? 0 : a < b ? -1 : 1;
}

export type TriggerProblem =
  | 'PRICE_UNKNOWN'
  | 'TP_NOT_ABOVE_SL'
  | 'TRIGGER_ON_WRONG_SIDE'
  | 'TRIGGER_OUT_OF_BOUNDS';

/** BR3.4 and BR2.6, in the order of the server; null when the prices may be sent. */
export function checkTriggers(
  kind: ConditionalKind,
  stop: string | null,
  takeProfit: string | null,
  quote: Quote,
): TriggerProblem | null {
  if (quote.price === UNKNOWN || typeof quote.price !== 'string') {
    return 'PRICE_UNKNOWN';
  }
  const price = quote.price;
  if (kind === 'OCO' && stop && takeProfit && compareDecimal(takeProfit, stop) <= 0) {
    return 'TP_NOT_ABOVE_SL';
  }
  if (
    (kind !== 'TAKE_PROFIT' && stop && compareDecimal(stop, price) >= 0) ||
    (kind !== 'STOP' && takeProfit && compareDecimal(takeProfit, price) <= 0)
  ) {
    return 'TRIGGER_ON_WRONG_SIDE';
  }
  if (
    (kind !== 'TAKE_PROFIT' && stop && quote.minStop && compareDecimal(stop, quote.minStop) < 0) ||
    (kind !== 'STOP' &&
      takeProfit &&
      quote.maxTakeProfit &&
      compareDecimal(takeProfit, quote.maxTakeProfit) > 0)
  ) {
    return 'TRIGGER_OUT_OF_BOUNDS';
  }
  return null;
}

/** A price shown to the person with a decimal comma (the value itself never changes). */
export function showUsd(decimal: string): string {
  return decimal.replace('.', ',');
}
