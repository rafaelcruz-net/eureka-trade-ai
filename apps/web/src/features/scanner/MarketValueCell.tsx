'use client';

import { useId, useState } from 'react';
import type { MarketValue } from '@/lib/api/guest-api';
import { formatClock, formatDecimal, formatInstant } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';

const text = messages.scanner;

function sourceName(source: { name: string; simulated: boolean }): string {
  return source.simulated ? `${source.name} (${text.simulated})` : source.name;
}

function describe(value: MarketValue, timeZone?: string): string {
  if (value.status === 'UNKNOWN') {
    const reason = text.unknownReason[value.reason] ?? value.reason;
    return value.source ? `${reason} ${text.source(value.source.name)}` : reason;
  }
  return text.detail(
    sourceName(value.source),
    formatInstant(value.observedAt, timeZone),
    value.stale ? text.stale : text.fresh,
  );
}

/**
 * One market value. KNOWN shows the exact decimal; when stale, the text mark "Desatualizado" and the time of the last
 * update (BR5.5); when another source diverges, the text mark "Fontes divergem" (BR5.7). UNKNOWN shows the word
 * "unknown", never 0 or an empty cell. Hover or keyboard focus reveals source, time and freshness and, for a divergent
 * field, every origin with source, value and time. State is never shown by color alone.
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
  const divergence = value.status === 'KNOWN' ? (value.divergence ?? []) : [];

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
              ⚠ {text.stale} ·{' '}
              {text.staleUpdatedAt(formatClock(new Date(value.observedAt), timeZone))}
            </span>
          )}
          {divergence.length > 0 && (
            <span className="badge-divergent" data-testid={`${testId}-divergent`}>
              ≠ {text.divergent}
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
        {value.status === 'KNOWN' &&
          divergence.length > 0 &&
          [
            { source: value.source, value: value.value, observedAt: value.observedAt },
            ...divergence,
          ].map((origin) => (
            <span key={origin.source.sourceId} className="origin" data-testid={`${testId}-origin`}>
              {' '}
              ·{' '}
              {text.origin(
                sourceName(origin.source),
                formatDecimal(origin.value),
                formatInstant(origin.observedAt, timeZone),
              )}
            </span>
          ))}
      </span>
    </span>
  );
}
