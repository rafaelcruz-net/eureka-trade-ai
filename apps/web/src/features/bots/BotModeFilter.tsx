'use client';

import { useEffect, useRef } from 'react';
import { botListModeMessages as text } from '@/features/bots/mode-messages';

export type BotModeFilterValue = 'ALL' | 'PAPER' | 'REAL';

export const BOT_MODE_FILTER_VALUES: readonly BotModeFilterValue[] = ['ALL', 'PAPER', 'REAL'];

/** BR1.7: the value of `?mode=` in the address; anything that is not PAPER or REAL is "Todos". */
export function parseModeFilter(value: string | null | undefined): BotModeFilterValue {
  return value === 'PAPER' || value === 'REAL' ? value : 'ALL';
}

/**
 * 261121-paper-real-unico U3 (BR1.1 to BR1.5; AC2.3.1, AC2.3.5): the filter by mode of /bots. Three native radios in a
 * fieldset "Modo"; the arrows change and apply the option without moving the focus; the initial focus is on the
 * checked radio. With PAPER or REAL, "Mostrando N de M bots." is announced politely; both numbers come from the API
 * (BotPage), the screen never counts. No internal state: the value lives in the address.
 */
export function BotModeFilter({
  value,
  onChange,
  matchingCount,
  totalCount,
  autoFocus = true,
}: {
  value: BotModeFilterValue;
  onChange: (value: BotModeFilterValue) => void;
  matchingCount?: number;
  totalCount?: number;
  autoFocus?: boolean;
}) {
  const checked = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (autoFocus) {
      checked.current?.focus();
    }
    // Only on mount: a later change of the option never moves the focus (BR1.4).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const showCount = value !== 'ALL' && matchingCount !== undefined && totalCount !== undefined;
  return (
    <div className="mode-filter" data-testid="bots-mode-filter">
      <fieldset>
        <legend>{text.filter.legend}</legend>
        <div className="mode-filter-options">
          {BOT_MODE_FILTER_VALUES.map((option) => (
            <label key={option} className="mode-filter-option">
              <input
                ref={option === value ? checked : undefined}
                type="radio"
                name="bot-mode-filter"
                value={option}
                checked={option === value}
                data-testid={`bots-mode-filter-${option}`}
                onChange={() => onChange(option)}
              />{' '}
              {text.filter[option]}
            </label>
          ))}
        </div>
      </fieldset>
      <p aria-live="polite" aria-atomic="true" data-testid="bots-shown">
        {showCount ? text.shown(matchingCount, totalCount) : ''}
      </p>
    </div>
  );
}
