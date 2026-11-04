import type { ScannerFilters, SortField, SortOrder } from '@/lib/api/guest-api';
import { messages } from '@/lib/i18n/messages';

/** Age shortcuts of S3 in minutes: 1, 5, 15, 30 and 60 minutes, 6 and 24 hours (functional-spec.md). */
export const AGE_PRESETS = [1, 5, 15, 30, 60, 360, 1440] as const;

export const SORT_FIELDS: SortField[] = ['AGE', 'LIQUIDITY', 'VOLUME_24H', 'HOLDERS', 'RISK_SCORE'];

/** The state of the filter form, as typed (text), plus order. */
export interface FilterState {
  filters: ScannerFilters;
  sort: SortField;
  order?: SortOrder;
}

export const FILTER_NAMES: Array<keyof ScannerFilters> = [
  'maxAgeMinutes',
  'minLiquidity',
  'minVolume24h',
  'minHolders',
  'maxRiskScore',
  'sourceId',
];

const DECIMAL = /^\d+(?:[.,]\d+)?$/;
const INTEGER = /^\d{1,9}$/;

/** Reads the filters, sort and order from the address (filters live in the URL). */
export function fromSearchParams(params: URLSearchParams): FilterState {
  const filters: ScannerFilters = {};
  for (const name of FILTER_NAMES) {
    const value = params.get(name);
    if (value) {
      filters[name] = value;
    }
  }
  const sort = params.get('sort');
  const order = params.get('order');
  return {
    filters,
    sort: SORT_FIELDS.includes(sort as SortField) ? (sort as SortField) : 'AGE',
    order: order === 'ASC' || order === 'DESC' ? order : undefined,
  };
}

/** The address of a filter state: only the parameters that have a value. */
export function toSearchParams(state: FilterState): string {
  const params = new URLSearchParams();
  for (const name of FILTER_NAMES) {
    const value = state.filters[name];
    if (value) {
      params.set(name, value);
    }
  }
  if (state.sort !== 'AGE') {
    params.set('sort', state.sort);
  }
  if (state.order) {
    params.set('order', state.order);
  }
  return params.toString();
}

export function hasActiveFilters(filters: ScannerFilters): boolean {
  return FILTER_NAMES.some((name) => Boolean(filters[name]));
}

/**
 * Validates the typed filters before any request (CT-UX-6); a decimal typed with a comma travels with a dot. Returns
 * the normalized filters and the error text of each invalid field.
 */
export function validateFilters(filters: ScannerFilters): {
  normalized: ScannerFilters;
  errors: Partial<Record<keyof ScannerFilters, string>>;
} {
  const text = messages.scanner.filters;
  const normalized: ScannerFilters = {};
  const errors: Partial<Record<keyof ScannerFilters, string>> = {};
  for (const name of FILTER_NAMES) {
    const value = filters[name]?.trim();
    if (!value) {
      continue;
    }
    if (name === 'minLiquidity' || name === 'minVolume24h') {
      if (DECIMAL.test(value)) {
        normalized[name] = value.replace(',', '.');
      } else {
        errors[name] = text.invalidDecimal;
      }
    } else if (name === 'maxRiskScore') {
      if (INTEGER.test(value) && Number(value) <= 100) {
        normalized[name] = value;
      } else {
        errors[name] = text.invalidRisk;
      }
    } else if (name === 'maxAgeMinutes' || name === 'minHolders') {
      if (INTEGER.test(value)) {
        normalized[name] = value;
      } else {
        errors[name] = text.invalidInteger;
      }
    } else {
      normalized[name] = value;
    }
  }
  return { normalized, errors };
}
