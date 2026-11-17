'use client';

import { MODES, type Mode } from '@/features/real/api';
import { realMessages as text } from '@/features/real/messages';

/**
 * 261113-trading-real-bot U6 (BR4.1; interaction-spec.md ModeSelector; U6-NFR6.1): three radio options in a fieldset
 * with a legend; the arrows move between them natively. U7 (BR8.1, BR9.3): "Automática nas saídas" is enabled when the
 * server says the exits are on (`exitsEnabled` of `/real/release`); off, it stays disabled with "Em breve", except for
 * the person already in that mode, who stays in it.
 */
export const AVAILABLE_MODES: readonly Mode[] = ['SIGNED', 'AUTO_FULL'];

export function availableModes(exitsEnabled: boolean): readonly Mode[] {
  return exitsEnabled ? MODES : AVAILABLE_MODES;
}

export function ModeSelector({
  current,
  value,
  onChange,
  disabled = false,
  exitsEnabled = false,
}: {
  current: Mode;
  value: Mode;
  onChange: (mode: Mode) => void;
  disabled?: boolean;
  exitsEnabled?: boolean;
}) {
  const available = availableModes(exitsEnabled);
  return (
    <fieldset data-testid="real-mode-selector" disabled={disabled}>
      <legend>{text.mode.title}</legend>
      {MODES.map((mode) => {
        const option = text.mode.options[mode] as { name: string; description: string };
        const soon = !available.includes(mode) && mode !== current;
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
