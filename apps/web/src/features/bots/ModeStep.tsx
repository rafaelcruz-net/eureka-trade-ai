'use client';

import Link from 'next/link';
import type { KeyboardEvent, MouseEvent } from 'react';
import type { BotMarket, RealEligibility } from '@/lib/api/guest-api';
import { SimulatedBadge } from '@/components/SimulatedBadge';
import { RealBadge } from '@/features/real/components/RealBadge';
import { modeStepMessages as text } from '@/features/bots/mode-messages';

/** The REAL eligibility as the screen loads it (C2). */
export type EligibilityLoad =
  | { kind: 'loading' }
  | { kind: 'error'; code: string }
  | { kind: 'ready'; eligibility: RealEligibility };

function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

/** "dd/mm HH:MM" of a UTC instant in the time zone of the person (BR7.4). */
export function quarantineWhen(instant: string, timeZone: string): string {
  const parts = new Intl.DateTimeFormat('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    timeZone,
  }).formatToParts(new Date(instant));
  const part = (type: string) => parts.find((entry) => entry.type === type)?.value ?? '';
  return `${part('day')}/${part('month')} ${part('hour')}:${part('minute')}`;
}

/** The text of the reason of an unavailable REAL; the quarantine adds the instant it starts to count. */
export function reasonText(eligibility: RealEligibility, timeZone: string): string {
  const reason = eligibility.reason ?? 'REAL_DISABLED';
  const base = text.reasons[reason] ?? reason;
  if (reason === 'QUARANTINE' && eligibility.effectiveAt) {
    return `${base} ${text.quarantineFrom(quarantineWhen(eligibility.effectiveAt, timeZone))}`;
  }
  return base;
}

/**
 * 261121-paper-real-unico U1 (US1.1, US1.2; BR1.7, BR2.7, BR9.4; interaction-spec "ModeStep"): two native radios in a
 * fieldset "Modo do bot". "Simulado" is always available and chosen by default. An unavailable "Real" keeps its place
 * in the order of the arrows with {@code aria-disabled} (not the native lock): the arrow, the click and the Space on
 * it never change the value, and its reason is read through the description of the radio and a polite live region.
 * Only the inactive wallet offers a link ("Ativar a carteira do bot", to /real). The value and the eligibility come
 * from the parent.
 */
export function ModeStep({
  value,
  onChange,
  eligibility,
  onRetry,
  timeZone,
}: {
  value: BotMarket;
  onChange: (market: BotMarket) => void;
  eligibility: EligibilityLoad;
  onRetry: () => void;
  timeZone?: string;
}) {
  const zone = timeZone ?? browserTimeZone();
  const available = eligibility.kind === 'ready' && eligibility.eligibility.available;
  const unavailableReason =
    eligibility.kind === 'ready' && !eligibility.eligibility.available
      ? reasonText(eligibility.eligibility, zone)
      : null;

  function guardReal(event: MouseEvent<HTMLInputElement> | KeyboardEvent<HTMLInputElement>) {
    if (!available) {
      event.preventDefault();
    }
  }

  return (
    <fieldset className="mode-step" data-testid="mode-step">
      <legend>{text.legend}</legend>
      <div className="mode-card" data-testid="mode-card-PAPER">
        <input
          type="radio"
          id="mode-PAPER"
          name="mode"
          value="PAPER"
          checked={value === 'PAPER'}
          aria-describedby="mode-PAPER-help"
          data-testid="mode-PAPER"
          onChange={() => onChange('PAPER')}
        />
        <label htmlFor="mode-PAPER">
          <SimulatedBadge testId="mode-PAPER-badge" /> {text.paper}
        </label>
        <p id="mode-PAPER-help" className="muted">
          {text.paperHelp}
        </p>
      </div>
      <div
        className="mode-card"
        data-testid="mode-card-REAL"
        aria-disabled={!available || undefined}
      >
        <input
          type="radio"
          id="mode-REAL"
          name="mode"
          value="REAL"
          checked={value === 'REAL'}
          aria-disabled={!available}
          aria-describedby="mode-REAL-help"
          data-testid="mode-REAL"
          onClick={guardReal}
          onKeyDown={(event) => {
            if (event.key === ' ') {
              guardReal(event);
            }
          }}
          onChange={() => {
            if (available) {
              onChange('REAL');
            }
          }}
        />
        <label htmlFor="mode-REAL">
          <RealBadge testId="mode-REAL-badge" /> {text.real}
        </label>
        <div id="mode-REAL-help" className="muted">
          {eligibility.kind === 'loading' && (
            <p role="status" aria-busy="true" data-testid="mode-REAL-loading">
              {text.loading}
            </p>
          )}
          {eligibility.kind === 'error' && (
            <div data-testid="mode-REAL-error">
              <p>{text.error}</p>
              <p>{text.supportCode(eligibility.code)}</p>
              <button
                type="button"
                className="secondary"
                data-testid="mode-REAL-retry"
                onClick={onRetry}
              >
                {text.retry}
              </button>
            </div>
          )}
          {available && <p>{text.realHelp}</p>}
          {unavailableReason && (
            <p data-testid="mode-REAL-reason">
              {unavailableReason}{' '}
              {eligibility.kind === 'ready' &&
                eligibility.eligibility.reason === 'WALLET_INACTIVE' && (
                  <Link href="/real" data-testid="mode-REAL-activate">
                    {text.activateWallet}
                  </Link>
                )}
            </p>
          )}
        </div>
      </div>
      <p aria-live="polite" className="visually-hidden" data-testid="mode-live">
        {unavailableReason ? text.unavailable(unavailableReason) : ''}
      </p>
    </fieldset>
  );
}
