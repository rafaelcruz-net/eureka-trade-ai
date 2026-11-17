'use client';

/**
 * 261113-trading-real-bot U6 (BR5.1, BR5.2, U6-NFR6.1): a field of an amount in SOL with its visible label, the text the
 * person typed (never turned into a number here), a hint and the reason it was refused, linked by `aria-describedby`.
 * The conversion to lamports happens only in money.ts.
 */
export function SolAmountInput({
  id,
  label,
  value,
  onChange,
  hint,
  error,
  disabled = false,
  testId,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  hint?: string;
  error?: string | null;
  disabled?: boolean;
  testId: string;
}) {
  const described = [hint ? `${id}-hint` : null, error ? `${id}-error` : null]
    .filter(Boolean)
    .join(' ');
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        data-testid={testId}
        inputMode="decimal"
        autoComplete="off"
        value={value}
        disabled={disabled}
        aria-invalid={error ? true : undefined}
        aria-describedby={described || undefined}
        onChange={(event) => onChange(event.target.value)}
      />
      {hint && (
        <p id={`${id}-hint`} className="muted">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} role="alert" className="field-error" data-testid={`${testId}-error`}>
          {error}
        </p>
      )}
    </div>
  );
}
