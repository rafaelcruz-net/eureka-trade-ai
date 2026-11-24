'use client';

import Link from 'next/link';
import { formatClock } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';
import type { ReconciliationSlice } from '@/features/dashboard/dashboard-reducer';
import { dashboardMessages } from '@/features/dashboard/messages';

const reconciliationText = dashboardMessages.reconciliation;

/**
 * BR3.4 of the U9: "Reconciliado às HH:MM", "N divergências" (link to S6) or "Ainda não conferido"; a failure only here.
 * 261121-paper-real-unico U3 (BR7.1): it left the PnlCard with the card and stays on the dashboard, under the cards by
 * mode, because the reconciliation of the PAPER ledger still matters there.
 */
export function ReconciliationLine({
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
