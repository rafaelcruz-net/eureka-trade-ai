'use client';

import { useEffect, useRef, useState, type FormEvent } from 'react';
import { modeMessages } from '@/features/pnl/mode-messages';
import { addDays, todayIn, toUtcPeriod, type UtcPeriod } from '@/features/pnl/period';

const text = modeMessages.period;
const DAY = /^\d{4}-\d{2}-\d{2}$/;

export type PeriodKind = 'ALL' | 'TODAY' | 'LAST_7' | 'LAST_30' | 'RANGE';
export const PERIOD_KINDS: readonly PeriodKind[] = ['ALL', 'TODAY', 'LAST_7', 'LAST_30', 'RANGE'];

/**
 * 261121-paper-real-unico U3 (BR2.1, BR2.2): a period as it was APPLIED: the option, the local calendar days it covers
 * and the UTC pair [from, to) computed at the moment of the application, fixed until the next one (even after the
 * local midnight). "Desde o início" has no days and no pair: the API receives `period=ALL`.
 */
export interface AppliedPeriod {
  kind: PeriodKind;
  fromDay: string | null;
  toDay: string | null;
  utc: UtcPeriod | null;
}

export const SINCE_START: AppliedPeriod = { kind: 'ALL', fromDay: null, toDay: null, utc: null };

export type PeriodField = 'from' | 'to';
export type PeriodErrorKind = keyof typeof text.errors;
export type PeriodErrors = Partial<Record<PeriodField, PeriodErrorKind>>;

export type PeriodResult =
  | { ok: true; period: AppliedPeriod }
  | { ok: false; errors: PeriodErrors };

/** A real calendar day "AAAA-MM-DD" (2026-02-30 is not). */
function isDay(value: string): boolean {
  return DAY.test(value) && addDays(value, 0) === value;
}

/**
 * The application of an option at `now` in `timeZone` (BR2.2, BR2.3). Hoje, 7 and 30 days count whole local days
 * through period.ts (unchanged); the interval is inclusive and refused with the message of its field: an invalid day,
 * the end before the start, a start after today and more than 366 days in UTC (366 calendar days that gain the hour
 * of the end of the daylight saving time are refused).
 */
export function applyPeriod(
  kind: PeriodKind,
  timeZone: string,
  now: Date,
  draft: { fromDay: string; toDay: string } = { fromDay: '', toDay: '' },
): PeriodResult {
  if (kind === 'ALL') {
    return { ok: true, period: SINCE_START };
  }
  const today = todayIn(timeZone, now);
  if (kind !== 'RANGE') {
    const back = kind === 'TODAY' ? 0 : kind === 'LAST_7' ? 6 : 29;
    const fromDay = addDays(today, -back);
    const utc = toUtcPeriod(fromDay, today, timeZone);
    if (utc === null) {
      throw new Error('A preset period is always valid');
    }
    return { ok: true, period: { kind, fromDay, toDay: today, utc } };
  }
  const errors: PeriodErrors = {};
  if (!isDay(draft.fromDay)) {
    errors.from = 'format';
  }
  if (!isDay(draft.toDay)) {
    errors.to = 'format';
  }
  if (errors.from || errors.to) {
    return { ok: false, errors };
  }
  if (draft.fromDay > draft.toDay) {
    return { ok: false, errors: { to: 'reversed' } };
  }
  if (draft.fromDay > today) {
    return { ok: false, errors: { from: 'future' } };
  }
  const utc = toUtcPeriod(draft.fromDay, draft.toDay, timeZone);
  if (utc === null) {
    return { ok: false, errors: { to: 'tooLong' } };
  }
  return { ok: true, period: { kind: 'RANGE', fromDay: draft.fromDay, toDay: draft.toDay, utc } };
}

const ADDRESS: Record<Exclude<PeriodKind, 'ALL'>, string> = {
  TODAY: 'hoje',
  LAST_7: '7d',
  LAST_30: '30d',
  RANGE: 'intervalo',
};

/** BR2.6: the period in the address: `?periodo=hoje|7d|30d|intervalo&de=&ate=`; "Desde o início" has none. */
export function periodAddress(period: AppliedPeriod): Record<string, string> {
  if (period.kind === 'ALL') {
    return {};
  }
  const values: Record<string, string> = { periodo: ADDRESS[period.kind] };
  if (period.kind === 'RANGE' && period.fromDay && period.toDay) {
    values.de = period.fromDay;
    values.ate = period.toDay;
  }
  return values;
}

/**
 * The period of an address, applied now. Anything invalid (an unknown option, a bad or future interval, more than 366
 * days) opens on "Desde o início", without a request with RANGE.
 */
export function periodFromAddress(
  params: { get(name: string): string | null } | null | undefined,
  timeZone: string,
  now: Date,
): AppliedPeriod {
  const value = params?.get('periodo') ?? null;
  const kind = (Object.keys(ADDRESS) as Exclude<PeriodKind, 'ALL'>[]).find(
    (candidate) => ADDRESS[candidate] === value,
  );
  if (kind === undefined) {
    return SINCE_START;
  }
  const result = applyPeriod(kind, timeZone, now, {
    fromDay: params?.get('de') ?? '',
    toDay: params?.get('ate') ?? '',
  });
  return result.ok ? result.period : SINCE_START;
}

/** "2026-11-23" → "23/11/2026". */
export function dayText(day: string): string {
  return `${day.slice(8, 10)}/${day.slice(5, 7)}/${day.slice(0, 4)}`;
}

/** BR2.7: what the screen shows, with the unrealized always the one of now. */
export function periodShowing(period: AppliedPeriod, timeZone: string): string {
  if (period.kind === 'ALL' || !period.fromDay || !period.toDay) {
    return text.showingAll(timeZone);
  }
  return text.showing(dayText(period.fromDay), dayText(period.toDay));
}

/**
 * 261121-paper-real-unico U3 (BR2.1 to BR2.3, BR2.5, BR2.7; AC7.1.1 to AC7.1.4, AC7.2.2): the period of /pnl. A
 * fieldset "Período" with five native radios; a preset applies at once, the interval with "Aplicar". The UTC pair is
 * computed by period.ts at the moment of the application and stays fixed. An error keeps the draft, marks the field
 * with `aria-invalid`, is announced by `role="alert"` and takes the focus to the first field in error; `onChange` is
 * never called with an error. `now` and `timeZone` are injectable.
 */
export function PeriodSelector({
  value,
  onChange,
  timeZone,
  now = () => new Date(),
}: {
  value: AppliedPeriod;
  onChange: (period: AppliedPeriod, utc: UtcPeriod | null) => void;
  timeZone: string;
  now?: () => Date;
}) {
  const [selected, setSelected] = useState<PeriodKind>(value.kind);
  const [draftFrom, setDraftFrom] = useState(value.kind === 'RANGE' ? (value.fromDay ?? '') : '');
  const [draftTo, setDraftTo] = useState(value.kind === 'RANGE' ? (value.toDay ?? '') : '');
  const [errors, setErrors] = useState<PeriodErrors>({});
  const fromInput = useRef<HTMLInputElement>(null);
  const toInput = useRef<HTMLInputElement>(null);
  const [focusError, setFocusError] = useState(0);

  useEffect(() => {
    setSelected(value.kind);
    if (value.kind === 'RANGE') {
      setDraftFrom(value.fromDay ?? '');
      setDraftTo(value.toDay ?? '');
    }
  }, [value]);

  useEffect(() => {
    if (focusError === 0) {
      return;
    }
    if (errors.from) {
      fromInput.current?.focus();
    } else if (errors.to) {
      toInput.current?.focus();
    }
    // Only after an "Aplicar" with an error.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusError]);

  function choose(kind: PeriodKind) {
    setSelected(kind);
    setErrors({});
    if (kind === 'RANGE') {
      return;
    }
    const result = applyPeriod(kind, timeZone, now());
    if (result.ok) {
      onChange(result.period, result.period.utc);
    }
  }

  function apply(event: FormEvent) {
    event.preventDefault();
    const result = applyPeriod('RANGE', timeZone, now(), { fromDay: draftFrom, toDay: draftTo });
    if (!result.ok) {
      setErrors(result.errors);
      setFocusError((count) => count + 1);
      return;
    }
    setErrors({});
    onChange(result.period, result.period.utc);
  }

  const helpId = 'pnl-period-help';
  return (
    <form className="period-selector" data-testid="pnl-period" onSubmit={apply} noValidate>
      <fieldset>
        <legend>{text.legend}</legend>
        <div className="period-options">
          {PERIOD_KINDS.map((kind) => (
            <label key={kind} className="period-option">
              <input
                type="radio"
                name="pnl-period"
                value={kind}
                checked={selected === kind}
                data-testid={`pnl-period-${kind}`}
                onChange={() => choose(kind)}
              />{' '}
              {text.options[kind]}
            </label>
          ))}
        </div>
        {selected === 'RANGE' && (
          <div className="period-range" data-testid="pnl-period-range">
            <p className="muted" id={helpId}>
              {text.help(timeZone)}
            </p>
            <label htmlFor="pnl-from">{text.from}</label>
            <input
              id="pnl-from"
              ref={fromInput}
              type="date"
              value={draftFrom}
              data-testid="pnl-from"
              aria-invalid={errors.from ? true : undefined}
              aria-describedby={errors.from ? `${helpId} pnl-from-error` : helpId}
              onChange={(event) => setDraftFrom(event.target.value)}
            />
            {errors.from && (
              <p
                role="alert"
                className="field-error"
                id="pnl-from-error"
                data-testid="pnl-from-error"
              >
                {text.errors[errors.from]}
              </p>
            )}
            <label htmlFor="pnl-to">{text.to}</label>
            <input
              id="pnl-to"
              ref={toInput}
              type="date"
              value={draftTo}
              data-testid="pnl-to"
              aria-invalid={errors.to ? true : undefined}
              aria-describedby={errors.to ? `${helpId} pnl-to-error` : helpId}
              onChange={(event) => setDraftTo(event.target.value)}
            />
            {errors.to && (
              <p role="alert" className="field-error" id="pnl-to-error" data-testid="pnl-to-error">
                {text.errors[errors.to]}
              </p>
            )}
            <button type="submit" data-testid="pnl-apply">
              {text.apply}
            </button>
          </div>
        )}
      </fieldset>
      <p aria-live="polite" aria-atomic="true" data-testid="pnl-period-showing">
        {periodShowing(value, timeZone)}
      </p>
    </form>
  );
}
