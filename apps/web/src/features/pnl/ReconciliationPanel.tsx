'use client';

import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { ReconciliationResult } from '@/lib/api/guest-api';
import { formatClock, formatDecimal } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';

const text = messages.pnl;

type Load =
  | { kind: 'loading' }
  | { kind: 'error' }
  | { kind: 'ready'; result: ReconciliationResult };

/**
 * The reconciliation (F9, BR8.4): a divergence is never hidden - a fixed warning with the count and the list - and
 * without divergence "Reconciliado" with the time of the check. "Verificar agora" runs the check at once.
 */
export function ReconciliationPanel({ botId, timeZone }: { botId?: string; timeZone?: string }) {
  const api = useApi();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [checking, setChecking] = useState(false);
  const [checkMessage, setCheckMessage] = useState<string | null>(null);

  const fetchLatest = useCallback(async () => {
    setLoad({ kind: 'loading' });
    try {
      setLoad({ kind: 'ready', result: await api.getReconciliation(botId) });
    } catch {
      // An expired session is handled by the P&L screen, which owns the redirect to the entry.
      setLoad({ kind: 'error' });
    }
  }, [api, botId]);

  useEffect(() => {
    void fetchLatest();
  }, [fetchLatest]);

  async function checkNow() {
    setChecking(true);
    setCheckMessage(null);
    try {
      setLoad({ kind: 'ready', result: await api.runReconciliation() });
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 429) {
        setCheckMessage(text.checkLimited(failure.retryAfterSeconds ?? 60));
      } else {
        setCheckMessage(text.checkError);
      }
    } finally {
      setChecking(false);
    }
  }

  return (
    <section aria-labelledby="reconciliation-title" data-testid="reconciliation">
      <h2 id="reconciliation-title">{text.reconciliationTitle}</h2>
      {load.kind === 'loading' && (
        <p role="status" data-testid="reconciliation-loading">
          {text.reconciliationLoading}
        </p>
      )}
      {load.kind === 'error' && (
        <p role="alert" className="notice notice-error" data-testid="reconciliation-error">
          {text.reconciliationError}
        </p>
      )}
      {load.kind === 'ready' && <Result result={load.result} timeZone={timeZone} />}
      <button
        type="button"
        className="secondary"
        data-testid="reconciliation-check-now"
        disabled={checking}
        onClick={() => void checkNow()}
      >
        {checking ? text.checking : text.checkNow}
      </button>
      {checkMessage && (
        <p role="status" data-testid="reconciliation-check-message">
          {checkMessage}
        </p>
      )}
    </section>
  );
}

function Result({ result, timeZone }: { result: ReconciliationResult; timeZone?: string }) {
  if (result.status === 'DIVERGENT') {
    return (
      <div role="alert" className="notice notice-error" data-testid="reconciliation-divergent">
        <h3>{text.divergent(result.divergenceCount)}</h3>
        <p>{text.divergentHelp}</p>
        <ul data-testid="reconciliation-divergences">
          {result.divergences.map((divergence, index) => (
            <li
              key={`${divergence.kind}-${divergence.orderId ?? divergence.entryId ?? divergence.positionId ?? index}`}
            >
              {text.divergenceLine(
                text.divergenceKinds[divergence.kind] ?? divergence.kind,
                formatDecimal(divergence.expected),
                formatDecimal(divergence.found),
              )}
            </li>
          ))}
        </ul>
      </div>
    );
  }
  if (result.status === 'PENDING') {
    return <p data-testid="reconciliation-pending">{text.reconciliationPending}</p>;
  }
  return (
    <p className="notice" data-testid="reconciliation-ok">
      <span aria-hidden="true">✓ </span>
      {result.checkedAt
        ? text.reconciled(formatClock(new Date(result.checkedAt), timeZone))
        : text.reconciledNoAccount}
    </p>
  );
}
