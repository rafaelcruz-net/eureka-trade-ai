'use client';

import { useState, type FormEvent } from 'react';
import type { ScannerFilters, SortField, SortOrder, SourceStatus } from '@/lib/api/guest-api';
import { messages } from '@/lib/i18n/messages';
import {
  AGE_PRESETS,
  SORT_FIELDS,
  validateFilters,
  type FilterState,
} from '@/features/scanner/filters';

const text = messages.scanner.filters;

type Errors = Partial<Record<keyof ScannerFilters, string>>;

/**
 * Filters of S3 (US2.2): age with shortcuts or custom minutes, minimum liquidity, 24 h volume and holders, maximum
 * indicator and origin, plus order. Every field has a visible label with its unit; an invalid value (typed here or
 * refused by the API) shows its error in text next to the field (CT-UX-6). Native controls only, keyboard first.
 */
export function ScannerFiltersForm({
  state,
  sources,
  serverErrors,
  onApply,
}: {
  state: FilterState;
  sources: SourceStatus[];
  serverErrors: Errors;
  onApply: (state: FilterState) => void;
}) {
  const [filters, setFilters] = useState<ScannerFilters>(state.filters);
  const [sort, setSort] = useState<SortField>(state.sort);
  const [order, setOrder] = useState<SortOrder | ''>(state.order ?? '');
  const presetAge = AGE_PRESETS.some((m) => String(m) === state.filters.maxAgeMinutes);
  const [customAge, setCustomAge] = useState(Boolean(state.filters.maxAgeMinutes) && !presetAge);
  const [errors, setErrors] = useState<Errors>({});
  const shown: Errors = { ...serverErrors, ...errors };

  const set = (name: keyof ScannerFilters, value: string) =>
    setFilters((current) => ({ ...current, [name]: value }));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const { normalized, errors: found } = validateFilters(filters);
    setErrors(found);
    if (Object.keys(found).length === 0) {
      onApply({ filters: normalized, sort, order: order || undefined });
    }
  };

  const field = (name: keyof ScannerFilters, label: string, inputMode: 'decimal' | 'numeric') => (
    <div className="filter-field">
      <label htmlFor={`scanner-filter-${name}`}>{label}</label>
      <input
        id={`scanner-filter-${name}`}
        data-testid={`scanner-filter-${name}`}
        inputMode={inputMode}
        value={filters[name] ?? ''}
        aria-invalid={Boolean(shown[name])}
        aria-describedby={shown[name] ? `scanner-filter-${name}-error` : undefined}
        onChange={(event) => set(name, event.target.value)}
      />
      {shown[name] && (
        <span
          id={`scanner-filter-${name}-error`}
          className="field-error"
          data-testid={`scanner-filter-${name}-error`}
        >
          {shown[name]}
        </span>
      )}
    </div>
  );

  return (
    <form className="scanner-filters" onSubmit={submit} noValidate data-testid="scanner-filters">
      <fieldset>
        <legend>{text.legend}</legend>
        <div className="filter-field">
          <label htmlFor="scanner-filter-age">{text.maxAge}</label>
          <select
            id="scanner-filter-age"
            data-testid="scanner-filter-age"
            value={customAge ? 'custom' : (filters.maxAgeMinutes ?? '')}
            onChange={(event) => {
              const value = event.target.value;
              setCustomAge(value === 'custom');
              set('maxAgeMinutes', value === 'custom' ? '' : value);
            }}
          >
            <option value="">{text.maxAgeAny}</option>
            {AGE_PRESETS.map((minutes) => (
              <option key={minutes} value={String(minutes)}>
                {text.ageOption(minutes)}
              </option>
            ))}
            <option value="custom">{text.maxAgeCustom}</option>
          </select>
        </div>
        {customAge && field('maxAgeMinutes', text.maxAgeCustomLabel, 'numeric')}
        {field('minLiquidity', text.minLiquidity, 'decimal')}
        {field('minVolume24h', text.minVolume24h, 'decimal')}
        {field('minHolders', text.minHolders, 'numeric')}
        {field('maxRiskScore', text.maxRiskScore, 'numeric')}
        <div className="filter-field">
          <label htmlFor="scanner-filter-source">{text.source}</label>
          <select
            id="scanner-filter-source"
            data-testid="scanner-filter-source"
            value={filters.sourceId ?? ''}
            onChange={(event) => set('sourceId', event.target.value)}
          >
            <option value="">{text.anySource}</option>
            {sources.map((source) => (
              <option key={source.source.sourceId} value={source.source.sourceId}>
                {source.source.name}
              </option>
            ))}
          </select>
        </div>
        <div className="filter-field">
          <label htmlFor="scanner-sort">{text.sort}</label>
          <select
            id="scanner-sort"
            data-testid="scanner-sort"
            value={sort}
            onChange={(event) => setSort(event.target.value as SortField)}
          >
            {SORT_FIELDS.map((value) => (
              <option key={value} value={value}>
                {text.sortOptions[value]}
              </option>
            ))}
          </select>
        </div>
        <div className="filter-field">
          <label htmlFor="scanner-order">{text.order}</label>
          <select
            id="scanner-order"
            data-testid="scanner-order"
            value={order}
            onChange={(event) => setOrder(event.target.value as SortOrder | '')}
          >
            <option value="">—</option>
            <option value="ASC">{text.ascending}</option>
            <option value="DESC">{text.descending}</option>
          </select>
        </div>
        <button type="submit" data-testid="scanner-filters-apply">
          {text.apply}
        </button>
      </fieldset>
    </form>
  );
}
