'use client';

import { useEffect, useRef, useState } from 'react';
import type { GuestApi } from '@/lib/api/guest-api';
import { abbreviateMint, formatInstant } from '@/lib/format/format';
import { useLiveChannel } from '@/features/live/LiveChannelProvider';
import { realCall } from '@/features/real/api';
import { showUsd, toConditional, type Conditional } from '@/features/real/conditionals';
import { useLoad } from '@/features/real/hooks/useLoad';
import { useMoneyAction } from '@/features/real/hooks/useMoneyAction';
import { realMessages as text } from '@/features/real/messages';
import { MoneyConfirmDialog } from '@/features/real/components/MoneyConfirmDialog';
import { RealErrorNotice } from '@/features/real/components/RealErrorNotice';

const copy = text.exits;

/**
 * 261113-trading-real-bot U7, the tab "Condicionais" of `/real/ordens` (BR8.3, BR6.7, S48): the conditional exits of the
 * person with their state in TEXT ("Aguardando assinatura", "Aberta", "Preenchida", "Cancelando", "Cancelada",
 * "Expirada", "Falhou"), the leg that filled, and the cancellation by the person with its confirmation. When the
 * automatic cancellation is stalled for 15 minutes the notice of BR6.7 says the support was told and the free SOL can
 * still be withdrawn. A change of state on the live channel ({@code real.conditional.state}) reads the list again.
 */
export function ConditionalsTab({
  api,
  timeZone,
  newKey,
}: {
  api: GuestApi;
  timeZone?: string;
  newKey?: () => string;
}) {
  const list = useLoad(() =>
    realCall(() => api.listRealConditionals({ limit: 20 })).then((page) =>
      page.items.map(toConditional),
    ),
  );
  const { subscribe } = useLiveChannel();
  const reload = list.reload;
  useEffect(() => subscribe('real.conditional.state', () => reload()), [subscribe, reload]);

  if (list.load.kind === 'loading') {
    return <p role="status">{copy.loading}</p>;
  }
  if (list.load.kind === 'error') {
    return (
      <RealErrorNotice
        error={list.load.error}
        onRetry={list.reload}
        testId="real-exits-list-error"
      />
    );
  }
  const items = list.load.data;
  return (
    <section aria-label={copy.tab} data-testid="real-exits-list">
      {items.some((item) => item.needsUserCancellation) && (
        <p role="alert" data-testid="real-exits-stalled">
          {copy.stalled}
        </p>
      )}
      {items.length === 0 ? (
        <p data-testid="real-exits-empty">{copy.empty}</p>
      ) : (
        <table className="real-table">
          <thead>
            <tr>
              <th scope="col">{copy.columns.date}</th>
              <th scope="col">{copy.columns.token}</th>
              <th scope="col">{copy.columns.kind}</th>
              <th scope="col">{copy.columns.prices}</th>
              <th scope="col">{copy.columns.state}</th>
              <th scope="col">
                <span className="visually-hidden">{copy.cancel}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <ConditionalRow
                key={item.id}
                api={api}
                item={item}
                timeZone={timeZone}
                newKey={newKey}
                onChanged={(changed) =>
                  list.set(items.map((other) => (other.id === changed.id ? changed : other)))
                }
              />
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function ConditionalRow({
  api,
  item,
  timeZone,
  newKey,
  onChanged,
}: {
  api: GuestApi;
  item: Conditional;
  timeZone?: string;
  newKey?: () => string;
  onChanged: (conditional: Conditional) => void;
}) {
  const [confirming, setConfirming] = useState(false);
  const opener = useRef<HTMLButtonElement>(null);
  const cancel = useMoneyAction(
    (_: { id: string }, key: string) =>
      realCall(() => api.cancelRealConditional(item.id, key)).then(toConditional),
    { newKey },
  );
  const cancellable = item.state === 'PENDING_SIGNATURE' || item.state === 'OPEN';
  const prices = [item.stopPriceUsd, item.takeProfitPriceUsd]
    .map((price) => (price ? showUsd(price) : '—'))
    .join(' / ');
  const state =
    (copy.states[item.state] ?? item.state) +
    (item.state === 'FILLED' && item.filledSide
      ? ` ${copy.filledSides[item.filledSide] ?? ''}`
      : '');

  async function confirm() {
    const changed = await cancel.submit({ id: item.id });
    if (changed) {
      setConfirming(false);
      onChanged(changed);
    }
  }

  return (
    <tr data-testid={`real-exits-row-${item.id}`}>
      <td>{formatInstant(item.createdAt, timeZone)}</td>
      <td>{abbreviateMint(item.mint)}</td>
      <td>{copy.kinds[item.kind] ?? item.kind}</td>
      <td>{prices}</td>
      <td data-testid={`real-exits-state-${item.id}`}>{state}</td>
      <td>
        {cancellable && (
          <button
            ref={opener}
            type="button"
            onClick={() => setConfirming(true)}
            data-testid={`real-exits-cancel-${item.id}`}
          >
            {copy.cancel}
          </button>
        )}
        {!confirming && cancel.state.kind === 'error' && (
          <RealErrorNotice
            error={cancel.state.error}
            testId={`real-exits-cancel-error-${item.id}`}
          />
        )}
        {confirming && (
          <MoneyConfirmDialog
            title={copy.cancelTitle}
            effect={copy.cancelEffect}
            action={cancel}
            onConfirm={() => void confirm()}
            onClose={() => {
              cancel.reset();
              setConfirming(false);
            }}
            returnFocusTo={opener.current}
            onRetry={() => void cancel.retry().then((changed) => changed && onChanged(changed))}
            testId="real-exits-cancel-confirm"
          />
        )}
      </td>
    </tr>
  );
}
