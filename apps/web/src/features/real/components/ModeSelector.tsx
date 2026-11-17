'use client';

import { MODES, type Mode } from '@/features/real/api';
import { realMessages as text } from '@/features/real/messages';

/**
 * 261113-trading-real-bot U6 (BR4.1; interaction-spec.md ModeSelector; U6-NFR6.1): three radio options in a fieldset
 * with a legend; the arrows move between them natively. "Automática nas saídas" is disabled with "Em breve" while the
 * server does not take the mode (U7).
 */
export const AVAILABLE_MODES: readonly Mode[] = ['SIGNED', 'AUTO_FULL'];

export function ModeSelector({
  current,
  value,
  onChange,
  disabled = false,
}: {
  current: Mode;
  value: Mode;
  onChange: (mode: Mode) => void;
  disabled?: boolean;
}) {
  return (
    <fieldset data-testid="real-mode-selector" disabled={disabled}>
      <legend>{text.mode.title}</legend>
      {MODES.map((mode) => {
        const option = text.mode.options[mode] as { name: string; description: string };
        const soon = !AVAILABLE_MODES.includes(mode);
        const id = `real-mode-${mode.toLowerCase()}`;
        return (
          <p key={mode}>
            <input
              id={id}
              type="radio"
              name="real-mode"
              value={mode}
              checked={value === mode}
              disabled={soon}
              aria-describedby={`${id}-description`}
              onChange={() => onChange(mode)}
              data-testid={`real-mode-option-${mode}`}
            />{' '}
            <label htmlFor={id}>
              {option.name}
              {mode === current ? ` ${text.mode.current}` : ''}
            </label>{' '}
            <span id={`${id}-description`} className="muted">
              {soon ? text.mode.soon : option.description}
            </span>
          </p>
        );
      })}
    </fieldset>
  );
}
