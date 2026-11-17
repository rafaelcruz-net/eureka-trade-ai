'use client';

import type { Pnl } from '@/features/real/api';
import type { Load } from '@/features/real/hooks/useLoad';
import { formatSignedSol, formatSolAmount, formatToken, isUnknown } from '@/features/real/money';
import { realMessages as text } from '@/features/real/messages';
import { RealBadge } from '@/features/real/components/RealBadge';
import { RealErrorNotice } from '@/features/real/components/RealErrorNotice';
import { abbreviateMint } from '@/lib/format/format';

/**
 * 261113-trading-real-bot U6 (BR7.2, BR8.2; U6-NFR2.1, U6-NFR7.5; US4.2): the card of the P&L REAL, with fees, in its
 * OWN card with the REAL label. It receives only the P&L REAL; the PAPER card receives only the PAPER one, and nothing
 * adds them. A position without a price shows its cost of purchase with "sem preço", never zero.
 */
export function RealPnlCard({ load, onRetry }: { load: Load<Pnl>; onRetry: () => void }) {
  return (
    <section
      className="real-card"
      aria-labelledby="real-pnl-title"
      aria-busy={load.kind === 'loading'}
      data-testid="real-pnl-card"
    >
      <h2 id="real-pnl-title">
        {text.pnl.title} <RealBadge testId="real-pnl-badge" />
      </h2>
      <p className="muted">{text.pnl.separated}</p>
      {load.kind === 'loading' && (
        <p role="status" data-testid="real-pnl-loading">
          {text.pnl.loading}
        </p>
      )}
      {load.kind === 'error' && (
        <RealErrorNotice
          error={load.error}
          message={text.pnl.error}
          onRetry={onRetry}
          testId="real-pnl-error"
        />
      )}
      {load.kind === 'ready' && <PnlValues pnl={load.data} />}
    </section>
  );
}

function PnlValues({ pnl }: { pnl: Pnl }) {
  return (
    <>
      <dl>
        <dt>{text.pnl.realized}</dt>
        <dd data-testid="real-pnl-realized">{formatSignedSol(pnl.realized)}</dd>
        <dt>{text.pnl.unrealized}</dt>
        <dd data-testid="real-pnl-unrealized">
          {isUnknown(pnl.unrealized) ? text.noPrice : formatSignedSol(pnl.unrealized)}
        </dd>
        <dt>{text.pnl.fees}</dt>
        <dd data-testid="real-pnl-fees">{formatSolAmount(pnl.fees)}</dd>
      </dl>
      {pnl.positions.length === 0 ? (
        <p data-testid="real-pnl-empty">{text.pnl.empty}</p>
      ) : (
        <ul className="real-positions" data-testid="real-pnl-positions">
          {pnl.positions.map((position) => (
            <li key={position.mint} data-testid={`real-pnl-position-${position.mint}`}>
              <strong>{text.pnl.position(abbreviateMint(position.mint))}</strong>:{' '}
              {formatToken(position.baseUnits, undefined)} · {text.pnl.cost}{' '}
              {formatSolAmount(position.cost)} · {text.pnl.value}{' '}
              <span data-testid={`real-pnl-position-value-${position.mint}`}>
                {formatSolAmount(position.value, text.noPrice)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
