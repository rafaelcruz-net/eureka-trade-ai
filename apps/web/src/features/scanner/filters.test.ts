import { messages } from '@/lib/i18n/messages';
import {
  fromSearchParams,
  hasActiveFilters,
  toSearchParams,
  validateFilters,
} from '@/features/scanner/filters';
import { listQuery } from '@/lib/api/guest-api';

describe('scanner filters', () => {
  it('reads and writes the address with only the filled filters', () => {
    const state = fromSearchParams(
      new URLSearchParams('minLiquidity=10&sort=HOLDERS&order=ASC&color=red'),
    );

    expect(state).toEqual({ filters: { minLiquidity: '10' }, sort: 'HOLDERS', order: 'ASC' });
    expect(toSearchParams(state)).toBe('minLiquidity=10&sort=HOLDERS&order=ASC');
    expect(toSearchParams({ filters: {}, sort: 'AGE' })).toBe('');
  });

  it('an unknown sort or order in the address falls back to the defaults', () => {
    expect(fromSearchParams(new URLSearchParams('sort=PRICE&order=up'))).toEqual({
      filters: {},
      sort: 'AGE',
      order: undefined,
    });
  });

  it('accepts a decimal with comma or dot and refuses negatives, letters and a risk above 100', () => {
    const { normalized, errors } = validateFilters({
      minLiquidity: '1.000,5',
      minVolume24h: '2,5',
      minHolders: '-3',
      maxRiskScore: '101',
      maxAgeMinutes: '15',
      sourceId: 'sim-a',
    });

    expect(normalized).toEqual({ minVolume24h: '2.5', maxAgeMinutes: '15', sourceId: 'sim-a' });
    expect(errors).toEqual({
      minLiquidity: messages.scanner.filters.invalidDecimal,
      minHolders: messages.scanner.filters.invalidInteger,
      maxRiskScore: messages.scanner.filters.invalidRisk,
    });
  });

  it('knows when a filter is active', () => {
    expect(hasActiveFilters({})).toBe(false);
    expect(hasActiveFilters({ sourceId: 'sim-b' })).toBe(true);
  });

  it('the list query carries only the parameters that have a value', () => {
    expect(
      listQuery({
        cursor: 'c',
        limit: 50,
        sort: 'RISK_SCORE',
        order: 'DESC',
        filters: { minHolders: '1', sourceId: '' },
      }),
    ).toBe('limit=50&cursor=c&sort=RISK_SCORE&order=DESC&minHolders=1');
  });
});
