'use client';

import { abbreviateMint, formatClock } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';
import type { LastTradeSlice } from '@/features/dashboard/dashboard-reducer';
import { dashboardMessages } from '@/features/dashboard/messages';

const text = dashboardMessages.lastTrade;

/**
 * "Última operação" (BR2.3, BR2.4): the newest simulated order among every bot of the person, with local time, bot,
 * side and status in TEXT; a not filled order shows its reason once the full order is read (the live notice does
 * not carry it). "Nenhuma operação ainda" when there is none. The line has its own loading and error.
 */
export function LastTradeLine({
  slice,
  names,
  timeZone,
}: {
  slice: LastTradeSlice;
  names: Record<string, string>;
  timeZone: string;
}) {
  return (
    <p data-testid="dashboard-last-trade" aria-busy={slice.status === 'LOADING'}>
      <strong>{text.label}:</strong> {content(slice, names, timeZone)}
    </p>
  );
}

function content(slice: LastTradeSlice, names: Record<string, string>, timeZone: string): string {
  if (slice.trade) {
    const { trade, botId } = slice.trade;
    const reason = slice.trade.source === 'full' ? slice.trade.trade.notFilledReason : undefined;
    const status =
      trade.status === 'NOT_FILLED' && reason
        ? text.notFilledWithReason(messages.pnl.notFilledReasons[reason] ?? reason)
        : (text.statuses[trade.status] ?? trade.status);
    return text.line(
      formatClock(new Date(trade.createdAt), timeZone),
      names[botId] ?? text.unknownBot,
      text.sides[trade.side] ?? trade.side,
      abbreviateMint(trade.tokenMint),
      status,
    );
  }
  if (slice.status === 'LOADING') {
    return text.loading;
  }
  return slice.status === 'ERROR' ? text.error : text.none;
}
