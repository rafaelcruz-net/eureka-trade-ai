'use client';

import { useCallback, useEffect, useState } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type { LedgerEntry, PaperTrade } from '@/lib/api/guest-api';
import { abbreviateMint, formatInstant } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';
import { SignedValue } from '@/features/pnl/SignedValue';
import { TradeHypotheses } from '@/features/pnl/TradeHypotheses';

const text = messages.pnl;

type Load =
  | { kind: 'loading' }
  | { kind: 'error' }
  | { kind: 'ready'; entries: LedgerEntry[]; nextCursor: string | null };

type Hypotheses =
  | { kind: 'loading' }
  | { kind: 'unavailable' }
  | { kind: 'ready'; trade: PaperTrade };

interface Group {
  key: string;
  orderId?: string;
  botId: string;
  entries: LedgerEntry[];
}

/** Groups consecutive entries by operation, keeping the order of the page (newest first). */
function groups(entries: LedgerEntry[]): Group[] {
  const result: Group[] = [];
  for (const entry of entries) {
    const key = entry.orderId ?? `entry-${entry.entryId}`;
    const last = result[result.length - 1];
    if (last && last.key === key) {
      last.entries.push(entry);
    } else {
      result.push({ key, orderId: entry.orderId, botId: entry.botId, entries: [entry] });
    }
  }
  return result;
}

/**
 * The PAPER ledger (F8, BR4.3, BR10.2): entries grouped by operation with type, amount, time and policy version,
 * and "ver hipóteses" with every assumption of the fill. There is no edit or delete action anywhere.
 */
export function LedgerList({ botId, timeZone }: { botId?: string; timeZone?: string }) {
  const api = useApi();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [open, setOpen] = useState<Record<string, Hypotheses>>({});

  const fetchPage = useCallback(
    async (cursor: string | null, previous: LedgerEntry[]) => {
      try {
        const page = await api.listLedger({ botId, cursor, limit: 50 });
        setLoad({
          kind: 'ready',
          entries: [...previous, ...page.items],
          nextCursor: page.nextCursor ?? null,
        });
      } catch {
        setLoad({ kind: 'error' });
      }
    },
    [api, botId],
  );

  useEffect(() => {
    setLoad({ kind: 'loading' });
    void fetchPage(null, []);
  }, [fetchPage]);

  async function toggle(group: Group) {
    const orderId = group.orderId as string;
    if (open[orderId]) {
      setOpen((current) => {
        const next = { ...current };
        delete next[orderId];
        return next;
      });
      return;
    }
    setOpen((current) => ({ ...current, [orderId]: { kind: 'loading' } }));
    try {
      const page = await api.listBotTrades(group.botId, { limit: 100 });
      const trade = page.items.find((item) => item.orderId === orderId);
      setOpen((current) => ({
        ...current,
        [orderId]: trade ? { kind: 'ready', trade } : { kind: 'unavailable' },
      }));
    } catch {
      setOpen((current) => ({ ...current, [orderId]: { kind: 'unavailable' } }));
    }
  }

  return (
    <section aria-labelledby="ledger-title" data-testid="ledger">
      <h2 id="ledger-title">
        {text.ledgerTitle} <span className="tag">{text.paperLabel}</span>
      </h2>
      <p className="muted">{text.ledgerIntro}</p>
      {load.kind === 'loading' && (
        <p role="status" data-testid="ledger-loading">
          {text.ledgerLoading}
        </p>
      )}
      {load.kind === 'error' && (
        <p role="alert" className="notice notice-error" data-testid="ledger-error">
          {text.ledgerError}
        </p>
      )}
      {load.kind === 'ready' && load.entries.length === 0 && (
        <p data-testid="ledger-empty">{text.ledgerEmpty}</p>
      )}
      {load.kind === 'ready' && load.entries.length > 0 && (
        <ol className="ledger-groups" data-testid="ledger-groups">
          {groups(load.entries).map((group) => {
            const hypotheses = group.orderId ? open[group.orderId] : undefined;
            const policy = group.entries.find(
              (entry) => entry.simulationPolicyVersion,
            )?.simulationPolicyVersion;
            return (
              <li key={group.key} data-testid={`ledger-group-${group.key}`}>
                <h3>
                  {group.orderId ? text.operation(abbreviateMint(group.orderId)) : text.noOperation}
                </h3>
                {policy && <p className="muted">{text.policy(policy)}</p>}
                <ul>
                  {group.entries.map((entry) => (
                    <li key={entry.entryId} data-testid={`ledger-entry-${entry.entryId}`}>
                      <span>{text.entryTypes[entry.type] ?? entry.type}</span>{' '}
                      <SignedValue value={entry.amount} testId={`ledger-amount-${entry.entryId}`} />{' '}
                      <span className="muted">{entry.asset}</span>{' '}
                      <time dateTime={entry.createdAt}>
                        {formatInstant(entry.createdAt, timeZone)}
                      </time>
                    </li>
                  ))}
                </ul>
                {group.orderId && (
                  <button
                    type="button"
                    className="secondary"
                    aria-expanded={hypotheses !== undefined}
                    data-testid={`ledger-hypotheses-${group.key}`}
                    onClick={() => void toggle(group)}
                  >
                    {hypotheses ? text.hideHypotheses : text.showHypotheses}
                  </button>
                )}
                {hypotheses?.kind === 'loading' && <p role="status">{text.hypothesesLoading}</p>}
                {hypotheses?.kind === 'unavailable' && (
                  <p role="alert">{text.hypothesesUnavailable}</p>
                )}
                {hypotheses?.kind === 'ready' && (
                  <TradeHypotheses trade={hypotheses.trade} timeZone={timeZone} />
                )}
              </li>
            );
          })}
        </ol>
      )}
      {load.kind === 'ready' && load.nextCursor && (
        <button
          type="button"
          className="secondary"
          data-testid="ledger-more"
          onClick={() => void fetchPage(load.nextCursor, load.entries)}
        >
          {text.ledgerMore}
        </button>
      )}
    </section>
  );
}
