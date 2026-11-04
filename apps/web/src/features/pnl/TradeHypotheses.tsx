import type { PaperTrade } from '@/lib/api/guest-api';
import { formatDecimal, formatInstant } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';

const text = messages.pnl.hypotheses;

function decimal(value: string | undefined): string {
  return value === undefined ? '—' : formatDecimal(value);
}

/**
 * "Ver hipóteses" (BR10.2): every assumption of a simulated fill - reference, quote, fill price, spread, impact,
 * liquidity consumed, fee, requested and filled quantity, market source and time, policy version.
 */
export function TradeHypotheses({ trade, timeZone }: { trade: PaperTrade; timeZone?: string }) {
  return (
    <dl className="hypotheses" data-testid={`hypotheses-${trade.orderId}`}>
      <dt>{text.referencePrice}</dt>
      <dd>{decimal(trade.referencePrice)}</dd>
      <dt>{text.quotedPrice}</dt>
      <dd>{decimal(trade.quotedPrice)}</dd>
      <dt>{text.fillPrice}</dt>
      <dd>{decimal(trade.fillPrice)}</dd>
      <dt>{text.spread}</dt>
      <dd>{decimal(trade.spreadPct)}</dd>
      <dt>{text.impact}</dt>
      <dd>{decimal(trade.impactPct)}</dd>
      <dt>{text.consumed}</dt>
      <dd>{decimal(trade.liquidityConsumedPct)}</dd>
      <dt>{text.fees}</dt>
      <dd>{decimal(trade.fees)}</dd>
      <dt>{text.requested}</dt>
      <dd>{formatDecimal(trade.requestedQuantity)}</dd>
      <dt>{text.filled}</dt>
      <dd>{decimal(trade.filledQuantity)}</dd>
      <dt>{text.source}</dt>
      <dd>
        {trade.marketSource && trade.marketObservedAt
          ? `${trade.marketSource}, ${formatInstant(trade.marketObservedAt, timeZone)}`
          : '—'}
      </dd>
      <dt>{text.policy}</dt>
      <dd>{trade.simulationPolicyVersion ?? '—'}</dd>
    </dl>
  );
}
