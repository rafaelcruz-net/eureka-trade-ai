'use client';

import type { BotLimits } from '@/lib/api/guest-api';
import { botMessages } from '@/features/bots/messages';
import { Term } from '@/features/bots/Dialog';

const FIELDS = [
  'maxOrderSize',
  'maxTokenExposure',
  'maxTotalExposure',
  'maxDailyLoss',
  'maxDrawdown',
  'maxSlippage',
  'minLiquidity',
  'minIntervalSeconds',
] as const;

export type LimitField = (typeof FIELDS)[number];

/** The editable form of the limits: every value is text; the interval becomes an integer only when sent. */
export type LimitsForm = Record<LimitField, string>;

export function toForm(limits: BotLimits): LimitsForm {
  return { ...limits, minIntervalSeconds: String(limits.minIntervalSeconds) } as LimitsForm;
}

/** The limits of the form; a non-integer interval becomes 0, which the validation refuses (never a decimal). */
export function fromForm(form: LimitsForm): BotLimits {
  const interval = /^\d+$/.test(form.minIntervalSeconds)
    ? Number.parseInt(form.minIntervalSeconds, 10)
    : 0;
  return { ...form, minIntervalSeconds: interval };
}

/**
 * Every limit with its visible label, unit, default and the sentence of what it prevents (AC4.2.1); the error in text
 * next to the field, tied by aria-describedby (NFR11.1). Market terms carry their explanation.
 */
export function LimitFields({
  form,
  defaults,
  errors,
  prefix = '',
  testId,
  onChange,
}: {
  form: LimitsForm;
  defaults?: LimitsForm;
  errors: Record<string, string>;
  prefix?: string;
  testId: string;
  onChange: (field: LimitField, value: string) => void;
}) {
  return (
    <fieldset data-testid={testId}>
      <legend>{botMessages.wizard.limits}</legend>
      {FIELDS.map((field) => {
        const spec = botMessages.limitFields[field] as {
          label: string;
          unit: string;
          blocks: string;
        };
        const id = `${testId}-${field}`;
        const error = errors[`${prefix}${field}`];
        return (
          <div key={field} className="field">
            <label htmlFor={id}>
              {field === 'maxDrawdown' ? (
                <Term
                  label={spec.label}
                  explanation={botMessages.glossary.drawdown}
                  testId={`${id}-term`}
                />
              ) : field === 'maxSlippage' ? (
                <Term
                  label={spec.label}
                  explanation={botMessages.glossary.slippage}
                  testId={`${id}-term`}
                />
              ) : (
                spec.label
              )}{' '}
              ({spec.unit})
            </label>
            <input
              id={id}
              inputMode="decimal"
              value={form[field]}
              aria-invalid={error ? true : undefined}
              aria-describedby={`${id}-help${error ? ` ${id}-error` : ''}`}
              data-testid={id}
              onChange={(event) => onChange(field, event.target.value)}
            />
            <p id={`${id}-help`} className="muted">
              {spec.blocks}
              {defaults ? ` ${botMessages.wizard.defaultValue(defaults[field])}` : ''}
            </p>
            {error && (
              <p id={`${id}-error`} className="field-error" data-testid={`${id}-error`}>
                {error}
              </p>
            )}
          </div>
        );
      })}
    </fieldset>
  );
}
