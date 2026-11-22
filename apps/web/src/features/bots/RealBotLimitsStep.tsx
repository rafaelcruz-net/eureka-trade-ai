'use client';

import Link from 'next/link';
import type { LimitLamports, LimitShare, RealAccountLimits } from '@/lib/api/guest-api';
import { formatSolCompact } from '@/features/real/money';
import { realLimitsMessages as text } from '@/features/bots/mode-messages';

/** The REAL limits of the account as the step loads them (C3). */
export type LimitsLoad =
  | { kind: 'loading' }
  | { kind: 'error'; code: string }
  | { kind: 'ready'; limits: RealAccountLimits };

function amountText(limit: LimitLamports, withoutCap: boolean): string {
  if (limit.status === 'UNKNOWN') {
    return withoutCap ? text.dependsOnCap : text.notDefined;
  }
  return formatSolCompact(limit.value);
}

function shareText(limit: LimitShare): string {
  if (limit.status === 'UNKNOWN') {
    return text.dependsOnCap;
  }
  return `${formatSolCompact(limit.value)} ${text.ofCap(limit.percentOfCap)}`;
}

/**
 * 261121-paper-real-unico U1 (US1.1, US1.3; BR1.8, BR3.1, BR3.2, BR3.4; mockups M3b): the limits step of a REAL bot.
 * The four REAL limits of the account, read only, in SOL by the money module of the REAL (BR7.1); a limit without a
 * value is "não definido", never zero, and without a cap the order and the loss are "não definido (depende do teto)"
 * with the link "Definir o teto". No editable field: "Alterar limites" leads to /real. "Continuar" saves the step
 * without a virtual balance nor limits of its own; it is disabled while the limits load or after an error.
 */
export function RealBotLimitsStep({
  load,
  onRetry,
  onBack,
  onContinue,
  busy = false,
}: {
  load: LimitsLoad;
  onRetry: () => void;
  onBack: () => void;
  onContinue: () => void;
  busy?: boolean;
}) {
  const ready = load.kind === 'ready';
  const withoutCap = ready && load.limits.cap.status === 'UNKNOWN';
  return (
    <section data-testid="real-limits">
      <h2>{text.title}</h2>
      <p className="muted">{text.intro}</p>
      {load.kind === 'loading' && (
        <p role="status" aria-busy="true" data-testid="real-limits-loading">
          {text.loading}
        </p>
      )}
      {load.kind === 'error' && (
        <div role="alert" className="notice notice-error" data-testid="real-limits-error">
          <p>{text.error}</p>
          <p className="muted">{text.supportCode(load.code)}</p>
          <button
            type="button"
            className="secondary"
            data-testid="real-limits-retry"
            onClick={onRetry}
          >
            {text.retry}
          </button>
        </div>
      )}
      {ready && (
        <>
          <dl className="hypotheses" data-testid="real-limits-values">
            <dt>{text.cap}</dt>
            <dd data-testid="real-limits-cap">{amountText(load.limits.cap, false)}</dd>
            <dt>{text.orderMax}</dt>
            <dd data-testid="real-limits-order">{shareText(load.limits.orderMax)}</dd>
            <dt>{text.dailyBuys}</dt>
            <dd data-testid="real-limits-buys">{text.upTo(load.limits.dailyBuys.count)}</dd>
            <dt>{text.dailyLoss}</dt>
            <dd data-testid="real-limits-loss">{shareText(load.limits.dailyLoss)}</dd>
          </dl>
          <p>
            <Link href="/real" data-testid="real-limits-change">
              {withoutCap ? text.defineCap : text.change}
            </Link>
          </p>
        </>
      )}
      <button type="button" className="secondary" data-testid="real-limits-back" onClick={onBack}>
        {text.back}
      </button>
      <button
        type="button"
        disabled={!ready || busy}
        aria-describedby={load.kind === 'loading' ? 'real-limits-wait' : undefined}
        data-testid="real-limits-continue"
        onClick={onContinue}
      >
        {text.continue}
      </button>
      {load.kind === 'loading' && (
        <p id="real-limits-wait" className="visually-hidden">
          {text.loading}
        </p>
      )}
    </section>
  );
}
