'use client';

import { useId, useState } from 'react';
import type { MarketValue } from '@/lib/api/guest-api';
import { formatDecimal, formatInstant } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';

const text = messages.scanner;

function describe(value: MarketValue, timeZone?: string): string {
  if (value.status === 'UNKNOWN') {
    const reason = text.unknownReason[value.reason] ?? value.reason;
    return value.source ? `${reason} ${text.source(value.source.name)}` : reason;
  }
  const source = value.source.simulated
    ? `${value.source.name} (${text.simulated})`
    : value.source.name;
  return text.detail(
    source,
    formatInstant(value.observedAt, timeZone),
    value.stale ? text.stale : text.fresh,
  );
}

/**
 * One market value. KNOWN shows the exact decimal (and "desatualizado" in text when stale); UNKNOWN shows the word
 * "unknown", never 0 or an empty cell. Hover or keyboard focus reveals source, time and freshness (F4 step 5).
 */
export function MarketValueCell({
  value,
  testId,
  unit,
  timeZone,
}: {
  value: MarketValue;
  testId: string;
  unit?: (formatted: string) => string;
  timeZone?: string;
}) {
  const [open, setOpen] = useState(false);
  const tooltipId = useId();
  const show = () => setOpen(true);
  const hide = () => setOpen(false);

  return (
    <span
      className="value-cell"
      tabIndex={0}
      data-testid={testId}
      data-status={value.status}
      aria-describedby={tooltipId}
      onMouseEnter={show}
      onMouseLeave={hide}
      onFocus={show}
      onBlur={hide}
    >
      {value.status === 'KNOWN' ? (
        <>
          <span>{unit ? unit(formatDecimal(value.value)) : formatDecimal(value.value)}</span>
          {value.stale && (
            <span className="badge-stale" data-testid={`${testId}-stale`}>
              ⚠ {text.stale}
            </span>
          )}
        </>
      ) : (
        <span className="value-unknown">? {text.unknown}</span>
      )}
      <span
        id={tooltipId}
        role="tooltip"
        className="value-tooltip"
        hidden={!open}
        data-testid={`${testId}-detail`}
      >
        {describe(value, timeZone)}
      </span>
    </span>
  );
}
