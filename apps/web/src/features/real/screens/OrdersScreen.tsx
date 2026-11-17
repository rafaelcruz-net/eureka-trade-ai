'use client';

import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { abbreviateMint, formatInstant } from '@/lib/format/format';
import type { Order, RealError, RealReads } from '@/features/real/api';
import { toRealError } from '@/features/real/api';
import { formatSolAmount, formatToken, isUnknown } from '@/features/real/money';
import { realMessages as text } from '@/features/real/messages';
import { OrderStateBadge } from '@/features/real/components/OrderStateBadge';
import { RealArea } from '@/features/real/components/RealArea';
import { RealErrorNotice } from '@/features/real/components/RealErrorNotice';
import { ConditionalsTab } from '@/features/real/components/ConditionalsTab';
import { FiscalExportPanel } from '@/features/real/components/FiscalExportPanel';

const copy = text.orders;
/** U6-NFR3.6: pages of 20 by cursor (the contract would give 50 by default). */
export const ORDERS_PAGE = 20;
const SOL_MINT = 'So11111111111111111111111111111111111111112';

/**
 * BR8.5: the explorer of the configured network. Mainnet: `https://solscan.io/tx/<signature>`; devnet: the same with
 * `?cluster=devnet`; the local network has no explorer, so no link (null).
 */
export function explorerLink(network: string, signature: string): string | null {
  const base = `https://solscan.io/tx/${encodeURIComponent(signature)}`;
  if (network === 'MAINNET') {
    return base;
  }
  if (network === 'DEVNET') {
    return `${base}?cluster=devnet`;
  }
  return null;
}

function abbreviate(signature: string): string {
  return signature.length <= 12 ? signature : `${signature.slice(0, 6)}…${signature.slice(-6)}`;
}

/** The token of an order is the side that is not SOL: the output of a buy, the input of a sale. */
function tokenOf(order: Order) {
  return order.side === 'BUY' ? (order.output ?? order.input) : order.input;
}

function amountText(amount: {
  mint: string;
  baseUnits: bigint;
  decimals: number | undefined;
}): string {
  return amount.mint === SOL_MINT
    ? formatSolAmount(amount.baseUnits)
    : formatToken(amount.baseUnits, amount.decimals);
}

/**
 * 261113-trading-real-bot U6, R5 `/real/ordens` (US4.1, US8.3; BR8.4 to BR8.7; S26 to S29, S37): the REAL orders,
 * newest first, 20 per page by cursor; each with its state in text and icon, the signature with the link of the
 * explorer of the network (none on the local network), the dates in the time zone of the browser and the amounts with
 * the decimals of their mint. Below 768 px the table becomes cards.
 */
export function OrdersScreen({ timeZone, newKey }: { timeZone?: string; newKey?: () => string }) {
  return (
    <RealArea origin="/real/ordens" title={copy.title} titleId="real-orders-title">
      {({ wallet, reads, api }) => (
        <>
          <OrdersTabs
            orders={<OrdersList reads={reads} network={wallet.network} timeZone={timeZone} />}
            conditionals={<ConditionalsTab api={api} timeZone={timeZone} newKey={newKey} />}
          />
          <FiscalExportPanel api={api} timeZone={timeZone} />
        </>
      )}
    </RealArea>
  );
}

type Tab = 'orders' | 'conditionals';

/**
 * 261113-trading-real-bot U7 (BR8.3): the tabs "Ordens" and "Condicionais"; the arrows move between them, the selected
 * one is said in text (aria-selected) and the other panel is not rendered.
 */
function OrdersTabs({ orders, conditionals }: { orders: ReactNode; conditionals: ReactNode }) {
  const [tab, setTab] = useState<Tab>('orders');
  const tabs: Array<[Tab, string]> = [
    ['orders', text.exits.ordersTab],
    ['conditionals', text.exits.tab],
  ];
  return (
    <>
      <div role="tablist" aria-label={copy.title} data-testid="real-orders-tabs">
        {tabs.map(([id, label]) => (
          <button
            key={id}
            id={`real-orders-tab-${id}`}
            type="button"
            role="tab"
            aria-selected={tab === id}
            aria-controls={`real-orders-panel-${id}`}
            tabIndex={tab === id ? 0 : -1}
            onClick={() => setTab(id)}
            onKeyDown={(event) => {
              if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
                setTab(tab === 'orders' ? 'conditionals' : 'orders');
              }
            }}
            data-testid={`real-orders-tab-${id}`}
          >
            {label}
          </button>
        ))}
      </div>
      <div
        role="tabpanel"
        id={`real-orders-panel-${tab}`}
        aria-labelledby={`real-orders-tab-${tab}`}
        data-testid={`real-orders-panel-${tab}`}
      >
        {tab === 'orders' ? orders : conditionals}
      </div>
    </>
  );
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'error'; error: RealError }
  | {
      kind: 'ready';
      items: Order[];
      nextCursor: string | null;
      loadingMore: boolean;
      moreError: RealError | null;
    };

function OrdersList({
  reads,
  network,
  timeZone,
}: {
  reads: RealReads;
  network: string;
  timeZone?: string;
}) {
  const [state, setState] = useState<ListState>({ kind: 'loading' });

  const first = useCallback(() => {
    setState({ kind: 'loading' });
    reads.orders({ limit: ORDERS_PAGE }).then(
      (page) =>
        setState({
          kind: 'ready',
          items: page.items,
          nextCursor: page.nextCursor,
          loadingMore: false,
          moreError: null,
        }),
      (failure: unknown) => setState({ kind: 'error', error: toRealError(failure) }),
    );
  }, [reads]);
  useEffect(first, [first]);

  function more() {
    if (state.kind !== 'ready' || !state.nextCursor || state.loadingMore) {
      return;
    }
    const current = state;
    setState({ ...current, loadingMore: true, moreError: null });
    reads.orders({ limit: ORDERS_PAGE, cursor: current.nextCursor }).then(
      (page) =>
        setState({
          kind: 'ready',
          items: [...current.items, ...page.items],
          nextCursor: page.nextCursor,
          loadingMore: false,
          moreError: null,
        }),
      (failure: unknown) =>
        setState({ ...current, loadingMore: false, moreError: toRealError(failure) }),
    );
  }

  if (state.kind === 'loading') {
    return (
      <p role="status" data-testid="real-orders-loading">
        {copy.loading}
      </p>
    );
  }
  if (state.kind === 'error') {
    return (
      <RealErrorNotice
        error={state.error}
        message={copy.error}
        onRetry={first}
        testId="real-orders-error"
      />
    );
  }
  if (state.items.length === 0) {
    return <p data-testid="real-orders-empty">{copy.empty}</p>;
  }
  return (
    <>
      <table className="real-orders-table" data-testid="real-orders-table">
        <thead>
          <tr>
            {Object.values(copy.columns).map((column) => (
              <th key={column} scope="col">
                {column}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {state.items.map((order) => (
            <tr key={order.id} data-testid={`real-order-row-${order.id}`}>
              <OrderCells order={order} network={network} timeZone={timeZone} variant="row" />
            </tr>
          ))}
        </tbody>
      </table>
      <ul className="real-orders-cards" data-testid="real-orders-cards">
        {state.items.map((order) => (
          <li key={order.id} className="real-card" data-testid={`real-order-card-${order.id}`}>
            <dl>
              <OrderCells order={order} network={network} timeZone={timeZone} variant="card" />
            </dl>
          </li>
        ))}
      </ul>
      {state.moreError && (
        <RealErrorNotice
          error={state.moreError}
          message={copy.error}
          onRetry={more}
          testId="real-orders-more-error"
        />
      )}
      {state.nextCursor && (
        <button
          type="button"
          className="secondary"
          disabled={state.loadingMore}
          onClick={more}
          data-testid="real-orders-more"
        >
          {state.loadingMore ? text.loading : copy.more}
        </button>
      )}
    </>
  );
}

/** The cells of one order, as table cells or as the definitions of a card. */
function OrderCells({
  order,
  network,
  timeZone,
  variant,
}: {
  order: Order;
  network: string;
  timeZone?: string;
  variant: 'row' | 'card';
}) {
  const token = tokenOf(order);
  const link = order.txSignature ? explorerLink(network, order.txSignature) : null;
  const reserved =
    order.side === 'BUY' && order.input.mint === SOL_MINT ? order.input.baseUnits : undefined;
  const cells: Array<[string, React.ReactNode]> = [
    [copy.columns.date, formatInstant(order.createdAt, timeZone)],
    [copy.columns.token, abbreviateMint(token.mint)],
    [copy.columns.side, copy.sides[order.side] ?? order.side],
    [copy.columns.quantity, amountText(order.input)],
    [copy.columns.output, order.output ? amountText(order.output) : text.noData],
    [copy.columns.fee, isUnknown(order.fee) ? text.noData : formatSolAmount(order.fee)],
    [
      copy.columns.state,
      <OrderStateBadge
        key="state"
        state={order.state}
        reservedLamports={reserved}
        testId={`real-order-${variant}-state-${order.id}`}
      />,
    ],
    [
      copy.columns.transaction,
      order.txSignature ? (
        link ? (
          <a
            key="tx"
            href={link}
            target="_blank"
            rel="noopener noreferrer"
            aria-label={copy.seeLabel(abbreviate(order.txSignature))}
            data-testid={`real-order-${variant}-link-${order.id}`}
          >
            ↗ {copy.see}
          </a>
        ) : (
          <span
            key="tx"
            className="mono"
            data-testid={`real-order-${variant}-signature-${order.id}`}
          >
            {abbreviate(order.txSignature)}
          </span>
        )
      ) : (
        copy.noTransaction
      ),
    ],
  ];
  if (variant === 'row') {
    return (
      <>
        {cells.map(([label, value]) => (
          <td key={label}>{value}</td>
        ))}
      </>
    );
  }
  return (
    <>
      {cells.map(([label, value]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </>
  );
}
