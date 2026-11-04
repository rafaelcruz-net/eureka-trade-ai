'use client';

import { useCallback, useEffect, useState } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type { PaperTrade } from '@/lib/api/guest-api';
import { formatDecimal, formatInstant } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';

const text = messages.pnl;

type Load = { kind: 'loading' } | { kind: 'error' } | { kind: 'ready'; trades: PaperTrade[] };

function decimal(value: string | undefined): string {
  return value === undefined ? '—' : formatDecimal(value);
}

/**
 * The operations of one bot (AC4.10.3, BR3.4, BR10.2), each once, with the requested and filled quantity, the
 * reference, quoted and fill prices and the versions of the policy and of the limits. A component for the bot screen
 * of U5; it only reads.
 */
export function BotTradesTable({ botId, timeZone }: { botId: string; timeZone?: string }) {
  const api = useApi();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });

  const fetchTrades = useCallback(async () => {
    setLoad({ kind: 'loading' });
    try {
      const page = await api.listBotTrades(botId, { limit: 50 });
      setLoad({ kind: 'ready', trades: page.items });
    } catch {
      setLoad({ kind: 'error' });
    }
  }, [api, botId]);

  useEffect(() => {
    void fetchTrades();
  }, [fetchTrades]);

  return (
    <section aria-labelledby={`trades-title-${botId}`} data-testid="bot-trades">
      <h2 id={`trades-title-${botId}`}>
        {text.tradesTitle} <span className="tag">{text.paperLabel}</span>
      </h2>
      {load.kind === 'loading' && (
        <p role="status" data-testid="bot-trades-loading">
          {text.tradesLoading}
        </p>
      )}
      {load.kind === 'error' && (
        <p role="alert" className="notice notice-error" data-testid="bot-trades-error">
          {text.tradesError}
        </p>
      )}
      {load.kind === 'ready' && load.trades.length === 0 && (
        <p data-testid="bot-trades-empty">{text.tradesEmpty}</p>
      )}
      {load.kind === 'ready' && load.trades.length > 0 && (
        <table data-testid="bot-trades-table">
          <caption className="visually-hidden">{text.tradesTitle}</caption>
          <thead>
            <tr>
              <th scope="col">{text.tradeColumns.time}</th>
              <th scope="col">{text.tradeColumns.side}</th>
              <th scope="col">{text.tradeColumns.status}</th>
              <th scope="col">{text.tradeColumns.requested}</th>
              <th scope="col">{text.tradeColumns.filled}</th>
              <th scope="col">{text.tradeColumns.reference}</th>
              <th scope="col">{text.tradeColumns.quoted}</th>
              <th scope="col">{text.tradeColumns.fill}</th>
              <th scope="col">{text.tradeColumns.versions}</th>
            </tr>
          </thead>
          <tbody>
            {load.trades.map((trade) => (
              <tr key={trade.orderId} data-testid={`bot-trade-${trade.orderId}`}>
                <td>{formatInstant(trade.filledAt ?? trade.createdAt, timeZone)}</td>
                <td>{text.sides[trade.side] ?? trade.side}</td>
                <td>
                  {text.statuses[trade.status] ?? trade.status}
                  {trade.notFilledReason && (
                    <span className="muted">
                      {' '}
                      ({text.notFilledReasons[trade.notFilledReason] ?? trade.notFilledReason})
                    </span>
                  )}
                </td>
                <td>{formatDecimal(trade.requestedQuantity)}</td>
                <td>{decimal(trade.filledQuantity)}</td>
                <td>{decimal(trade.referencePrice)}</td>
                <td>{decimal(trade.quotedPrice)}</td>
                <td>{decimal(trade.fillPrice)}</td>
                <td>{text.versions(trade.simulationPolicyVersion ?? '—', trade.limitsVersion)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
