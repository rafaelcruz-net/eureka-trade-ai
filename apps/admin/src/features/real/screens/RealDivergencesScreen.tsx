'use client';

import { useCallback, useRef, useState } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type { RealAdminDivergence } from '@/lib/api/admin-api';
import { browserTimeZone, dateTime } from '@/lib/format';
import { useAdminAction } from '@/features/real/hooks/useAdminAction';
import { useAdminLoad } from '@/features/real/hooks/useAdminLoad';
import { formatSol, toBaseUnits } from '@/features/real/money';
import { adminRealMessages as text } from '@/features/real/messages';
import { AdminActionDialog } from '@/features/real/components/AdminActionDialog';
import { abbreviate, RealLoadError, RealSection } from '@/features/real/components/RealSection';

const copy = text.divergences;

/** BR9.6: the items of the compensation are the measure of the last reconciliation, per asset, without zeros. */
export function compensationItems(
  divergence: RealAdminDivergence,
): Array<{ asset: string; amount: string }> {
  const items: Array<{ asset: string; amount: string }> = [];
  if (divergence.lamportsDiff && toBaseUnits(divergence.lamportsDiff) !== 0n) {
    items.push({ asset: 'SOL', amount: toBaseUnits(divergence.lamportsDiff).toString() });
  }
  for (const diff of divergence.tokenDiffs ?? []) {
    if (diff.mint && diff.diffBaseUnits && toBaseUnits(diff.diffBaseUnits) !== 0n) {
      items.push({ asset: diff.mint, amount: toBaseUnits(diff.diffBaseUnits).toString() });
    }
  }
  return items;
}

function itemText(item: { asset: string; amount: string }): string {
  return item.asset === 'SOL'
    ? `${formatSol(toBaseUnits(item.amount))} ${copy.sol}`
    : `${toBaseUnits(item.amount).toString()} ${abbreviate(item.asset)}`;
}

/**
 * 261113-trading-real-bot U6, A1 `/real/divergencias` (US8.2; BR9.2, BR9.3, BR9.6; S34): the divergences open or
 * compensated. The compensation form shows the measure of the last reconciliation per asset and fills the values with
 * it; the person confirms with the reason and never types the values.
 */
export function RealDivergencesScreen({ timeZone }: { timeZone?: string }) {
  const api = useApi();
  const zone = timeZone ?? browserTimeZone();
  const { load, reload } = useAdminLoad(useCallback(() => api.listRealDivergences(null), [api]));
  const [target, setTarget] = useState<RealAdminDivergence | null>(null);
  const opener = useRef<HTMLElement | null>(null);
  const compensate = useAdminAction(
    (
      body: {
        divergenceId: string;
        items: Array<{ asset: string; amount: string }>;
        reason: string;
      },
      key: string,
    ) =>
      api.compensateRealDivergence(
        body.divergenceId,
        { items: body.items, reason: body.reason },
        key,
      ),
    api.newKey,
  );
  const items = target ? compensationItems(target) : [];

  return (
    <RealSection title={copy.title} testId="admin-real-divergences">
      {load.kind === 'loading' && <p role="status">{text.loading}</p>}
      {load.kind === 'error' && (
        <RealLoadError
          error={load.error}
          message={copy.error}
          onRetry={reload}
          testId="admin-real-divergences-error"
        />
      )}
      {load.kind === 'ready' && (load.data.items ?? []).length === 0 && (
        <p data-testid="admin-real-divergences-empty">{copy.empty}</p>
      )}
      {load.kind === 'ready' && (load.data.items ?? []).length > 0 && (
        <ul data-testid="admin-real-divergences-list">
          {(load.data.items ?? []).map((divergence) => (
            <li
              key={divergence.divergenceId}
              data-testid={`admin-real-divergence-${divergence.divergenceId}`}
            >
              <strong>{copy.states[divergence.state ?? ''] ?? divergence.state}</strong> ·{' '}
              {copy.wallet} {abbreviate(divergence.botWalletId)}
              {divergence.openedAt
                ? ` · ${copy.openedAt} ${dateTime(divergence.openedAt, zone)}`
                : ''}{' '}
              · {copy.measure}:{' '}
              {compensationItems(divergence).map(itemText).join('; ') || text.noData}{' '}
              {divergence.state === 'OPEN' && (
                <button
                  type="button"
                  onClick={(event) => {
                    opener.current = event.currentTarget;
                    setTarget(divergence);
                  }}
                  data-testid={`admin-real-divergence-compensate-${divergence.divergenceId}`}
                >
                  {copy.compensate}
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {target && (
        <AdminActionDialog
          title={copy.compensateTitle}
          effect={copy.compensateEffect}
          testId="admin-real-compensation-dialog"
          state={compensate.state}
          ready={items.length > 0}
          returnFocusTo={opener.current}
          onClose={() => {
            compensate.reset();
            setTarget(null);
          }}
          onSubmit={(reason) =>
            void compensate
              .submit({ divergenceId: target.divergenceId ?? '', items, reason })
              .then((result) => {
                if (result) {
                  setTarget(null);
                  reload();
                }
              })
          }
        >
          <ul data-testid="admin-real-compensation-items">
            {items.map((item) => (
              <li key={item.asset}>{itemText(item)}</li>
            ))}
          </ul>
        </AdminActionDialog>
      )}
    </RealSection>
  );
}
