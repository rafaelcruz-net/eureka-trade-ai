'use client';

import Link from 'next/link';
import type { PnlReport } from '@/lib/api/guest-api';
import { formatClock, formatDecimal } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';
import { Term } from '@/features/bots/Dialog';
import { signOf } from '@/features/pnl/decimal';
import { PnlAmountView, SignedValue } from '@/features/pnl/SignedValue';
import { useDashboard } from '@/features/dashboard/DashboardProvider';
import { CardError } from '@/features/dashboard/CardError';
import type { ReconciliationSlice } from '@/features/dashboard/dashboard-reducer';
import { dashboardMessages } from '@/features/dashboard/messages';

const text = dashboardMessages.pnl;

/**
 * The "P&L PAPER (simulado)" card (US5.4; BR3.1 to BR3.5, BR6.6, BR8.2): the period "Hoje (dia UTC, desde HH:MM no
 * seu horário)"; realized, unrealized and fees with the sign written and the unit, from the DECIMAL TEXT of the API
 * through the decimal functions of U7 (never a floating point number); unknown with its reason, never zero; the
 * partial and empty states; and the reconciliation line with its own failure. The dashboard never computes P&L.
 */
export function PnlCard() {
  const { state, loader, timeZone } = useDashboard();
  const { pnl } = state;
  return (
    <section
      aria-labelledby="dashboard-pnl-title"
      aria-busy={pnl.status === 'LOADING'}
      className="dashboard-card"
      data-testid="dashboard-pnl-card"
    >
      <h2 id="dashboard-pnl-title">
        {text.title} <span className="tag">{dashboardMessages.paperLabel}</span>
      </h2>
      <p className="muted">
        <Term label="P&L" explanation={text.termHelp} testId="dashboard-pnl-term" />
      </p>
      {pnl.period && (
        <p className="muted" data-testid="dashboard-pnl-period">
          {text.period(formatClock(new Date(pnl.period.from), timeZone))}
        </p>
      )}
      {pnl.status === 'LOADING' && (
        <p role="status" data-testid="dashboard-pnl-loading">
          {text.loading}
        </p>
      )}
      {pnl.status === 'ERROR' && (
        <CardError card="pnl" message={text.error} onRetry={() => void loader.refreshPnl(true)} />
      )}
      {pnl.status === 'EMPTY' && <p data-testid="dashboard-pnl-empty">{text.empty}</p>}
      {pnl.status === 'READY' && pnl.report && <PnlTotals report={pnl.report} />}
      <ReconciliationLine
        slice={state.reconciliation}
        timeZone={timeZone}
        onRetry={() => void loader.refreshReconciliation(true)}
      />
      <p className="muted">{text.simulatedNote}</p>
      <Link href="/pnl" data-testid="dashboard-open-pnl-link">
        {text.open}
      </Link>
    </section>
  );
}

/** Fees are paid: written with the minus sign unless they are zero (BR3.5). */
function feesText(fees: string): string {
  if (signOf(fees) === 'zero') {
    return formatDecimal(fees);
  }
  const absolute = fees.startsWith('-') ? fees.slice(1) : fees;
  return `−${formatDecimal(absolute)}`;
}

function PnlTotals({ report }: { report: PnlReport }) {
  return (
    <>
      {report.partial && (
        <p role="note" className="notice notice-warning" data-testid="dashboard-pnl-partial">
          ⚠ {text.partial}
        </p>
      )}
      <dl className="totals">
        <dt>{text.realized}</dt>
        <dd>
          <SignedValue value={report.realized} testId="dashboard-pnl-realized" /> {text.unit}
        </dd>
        <dt>{text.unrealized}</dt>
        <dd>
          <PnlAmountView amount={report.unrealized} testId="dashboard-pnl-unrealized" />
          {report.unrealized.status === 'KNOWN' ? ` ${text.unit}` : ''}
        </dd>
        <dt>{text.fees}</dt>
        <dd data-testid="dashboard-pnl-fees">
          {feesText(report.fees)} {text.unit} <span className="muted">({text.feesPaid})</span>
        </dd>
      </dl>
    </>
  );
}

const reconciliationText = dashboardMessages.reconciliation;

/** BR3.4: "Reconciliado às HH:MM", "N divergências" (link to S6) or "Ainda não conferido"; a failure only here. */
function ReconciliationLine({
  slice,
  timeZone,
  onRetry,
}: {
  slice: ReconciliationSlice;
  timeZone?: string;
  onRetry: () => void;
}) {
  const result = slice.result;
  return (
    <div data-testid="dashboard-reconciliation">
      {slice.status === 'LOADING' && <p className="muted">{reconciliationText.loading}</p>}
      {slice.status === 'ERROR' && (
        <p role="alert">
          ⚠ {reconciliationText.error}{' '}
          <button
            type="button"
            className="secondary"
            data-testid="dashboard-reconciliation-retry"
            onClick={onRetry}
          >
            {dashboardMessages.retry}
          </button>
        </p>
      )}
      {slice.status === 'READY' && result?.status === 'RECONCILED' && (
        <p>
          ✓{' '}
          {result.checkedAt
            ? reconciliationText.reconciled(formatClock(new Date(result.checkedAt), timeZone))
            : reconciliationText.reconciledNoTime}
        </p>
      )}
      {slice.status === 'READY' && result?.status === 'DIVERGENT' && (
        <p>
          ⚠{' '}
          <Link href="/pnl" data-testid="dashboard-reconciliation-divergences-link">
            {reconciliationText.divergent(result.divergenceCount)}
          </Link>{' '}
          <span className="muted">({messages.pnl.reconciliationTitle})</span>
        </p>
      )}
      {slice.status === 'READY' && result?.status === 'PENDING' && (
        <p>{reconciliationText.pending}</p>
      )}
    </div>
  );
}
