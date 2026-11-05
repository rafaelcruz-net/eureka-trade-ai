import type { BotLimits, StrategyParameter } from '@/lib/api/guest-api';
import { compareDecimal, isDecimal, onStepGrid, percentOf, scaleOf } from '@/features/bots/decimal';
import { botMessages } from '@/features/bots/messages';

/**
 * The client side of the validation the server repeats (BR1.2 to BR1.6, NFR6.2): the screen refuses early, the
 * server is the authority. Every rule works on text and BigInt, never on JavaScript numbers.
 */
export type FieldErrors = Record<string, string>;

const text = botMessages.validation;
const BASE58 = /^[123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz]{32,44}$/;

/** BR1.2: each parameter inside its range and on its step. */
export function validateParameters(
  specs: StrategyParameter[],
  values: Record<string, string>,
): FieldErrors {
  const errors: FieldErrors = {};
  for (const spec of specs) {
    const value = values[spec.key] ?? '';
    if (!isDecimal(value)) {
      errors[`parameters.${spec.key}`] = text.notDecimal;
    } else if (!onStepGrid(value, spec.min, spec.max, spec.step)) {
      errors[`parameters.${spec.key}`] = text.range(spec.min, spec.max, spec.step);
    }
  }
  return errors;
}

export interface SelectionDraft {
  tokens: { chain: string; mint: string }[];
  rules: { field: string; operator: string; value: string }[];
}

/** BR1.3: at least one token or rule; up to 10 tokens without repetition and 5 rules. */
export function validateSelection(selection: SelectionDraft): FieldErrors {
  const errors: FieldErrors = {};
  if (selection.tokens.length === 0 && selection.rules.length === 0) {
    errors.tokenSelection = text.selectionEmpty;
    return errors;
  }
  if (selection.tokens.length > 10) {
    errors['tokenSelection.tokens'] = text.tooManyTokens;
  }
  if (selection.rules.length > 5) {
    errors['tokenSelection.rules'] = text.tooManyRules;
  }
  const seen = new Set<string>();
  selection.tokens.forEach((token, index) => {
    if (!BASE58.test(token.mint)) {
      errors[`tokenSelection.tokens[${index}].mint`] = text.badMint;
    } else if (seen.has(token.mint)) {
      errors[`tokenSelection.tokens[${index}]`] = text.repeatedToken;
    }
    seen.add(token.mint);
  });
  selection.rules.forEach((rule, index) => {
    if (!isDecimal(rule.value) || compareDecimal(rule.value, '0') < 0) {
      errors[`tokenSelection.rules[${index}].value`] = text.ruleValue;
    }
  });
  return errors;
}

/** BR1.4: from the minimum to the cap, with up to 6 places; above the cap shows the cap. */
export function validateBalance(value: string, min = '100', cap = '10000'): FieldErrors {
  if (!isDecimal(value) || scaleOf(value) > 6) {
    return { virtualBalance: text.balanceFormat };
  }
  if (compareDecimal(value, cap) > 0) {
    return { virtualBalance: text.balanceCap };
  }
  if (compareDecimal(value, min) < 0) {
    return { virtualBalance: text.balanceMin };
  }
  return {};
}

/** BR1.5: positive, percentages in (0, 100], order <= token exposure <= total exposure. */
export function validateLimits(limits: BotLimits): FieldErrors {
  const errors: FieldErrors = {};
  const positive = [
    'maxOrderSize',
    'maxTokenExposure',
    'maxTotalExposure',
    'maxDailyLoss',
  ] as const;
  for (const field of positive) {
    if (!isDecimal(limits[field]) || compareDecimal(limits[field], '0') <= 0) {
      errors[field] = text.positive;
    }
  }
  for (const field of ['maxDrawdown', 'maxSlippage'] as const) {
    const value = limits[field];
    if (!isDecimal(value) || compareDecimal(value, '0') <= 0 || compareDecimal(value, '100') > 0) {
      errors[field] = text.percent;
    }
  }
  if (!isDecimal(limits.minLiquidity) || compareDecimal(limits.minLiquidity, '0') < 0) {
    errors.minLiquidity = text.nonNegative;
  }
  if (!Number.isInteger(limits.minIntervalSeconds) || limits.minIntervalSeconds < 1) {
    errors.minIntervalSeconds = text.interval;
  }
  if (!errors.maxOrderSize && !errors.maxTokenExposure) {
    if (compareDecimal(limits.maxOrderSize, limits.maxTokenExposure) > 0) {
      errors.maxOrderSize = text.orderAboveToken;
    }
  }
  if (!errors.maxTokenExposure && !errors.maxTotalExposure) {
    if (compareDecimal(limits.maxTokenExposure, limits.maxTotalExposure) > 0) {
      errors.maxTokenExposure = text.tokenAboveTotal;
    }
  }
  return errors;
}

/** BR1.6: the default limits, proportional to the balance, rounded HALF_EVEN to 6 places. */
export function defaultLimits(balance: string): BotLimits {
  return {
    maxOrderSize: percentOf(balance, 10, 6),
    maxTokenExposure: percentOf(balance, 20, 6),
    maxTotalExposure: percentOf(balance, 50, 6),
    maxDailyLoss: percentOf(balance, 5, 6),
    maxDrawdown: '10.0000',
    maxSlippage: '2.0000',
    minLiquidity: '10000.000000',
    minIntervalSeconds: 240,
  };
}
