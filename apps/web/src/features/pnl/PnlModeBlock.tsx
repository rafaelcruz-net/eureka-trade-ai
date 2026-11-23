'use client';

import Link from 'next/link';
import { useEffect, useRef } from 'react';
import type { LamportsAmount, PaperModeBlock, PnlAmount, RealModeBlock } from '@/lib/api/guest-api';
import { formatClock } from '@/lib/format/format';
import { SimulatedBadge } from '@/components/SimulatedBadge';
import { TableScroll } from '@/components/TableScroll';
import { RealBadge } from '@/features/real/components/RealBadge';
import { modeMessages as text } from '@/features/pnl/mode-messages';
import {
  formatSol,
  formatUsd,
  solSign,
  usdSign,
  type MoneySign,
} from '@/features/pnl/money-format';

/** The load of ONE block of C4: an UNAVAILABLE block or a failed route is an error with the code for the support. */
export type BlockLoad<B> =
  | { kind: 'loading' }
  | { kind: 'error'; code: string }
  | { kind: 'ready'; block: B };

type Props =
  | {
      market: 'PAPER';
      load: BlockLoad<PaperModeBlock>;
      onRetry: () => void;
      variant?: 'full';
      period?: string;
      timeZone?: string;
      focusHeading?: boolean;
    }
  | {
      market: 'REAL';
      load: BlockLoad<RealModeBlock>;
      onRetry: () => void;
      variant?: 'full';
      period?: string;
      timeZone?: string;
      focusHeading?: boolean;
    };

function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

function signWord(sign: MoneySign): string {
  return sign === 'gain' ? text.gain : sign === 'loss' ? text.loss : text.zero;
}

/** A US$ amount with sign and word; color is never the only signal. */
function Usd({ value, testId }: { value: string; testId: string }) {
  const sign = usdSign(value);
  return (
    <span className={`money money-${sign}`} data-testid={testId} data-sign={sign}>
      {formatUsd(value)} <span className="muted">({signWord(sign)})</span>
    </span>
  );
}

/** A SOL amount (lamports text) with sign and word. */
function Sol({ value, testId }: { value: string; testId: string }) {
  const sign = solSign(value);
  return (
    <span className={`money money-${sign}`} data-testid={testId} data-sign={sign}>
      {formatSol(value)} <span className="muted">({signWord(sign)})</span>
    </span>
  );
}

function UsdAmount({ amount, testId }: { amount: PnlAmount; testId: string }) {
  if (amount.status === 'UNKNOWN') {
    return (
      <span className="money money-unknown" data-testid={testId} data-status="UNKNOWN">
        {text.unknown(text.unknownReasons[amount.reason] ?? amount.reason)}
      </span>
    );
  }
  return <Usd value={amount.value} testId={testId} />;
}

function SolAmount({ amount, testId }: { amount: LamportsAmount; testId: string }) {
  if (amount.status === 'UNKNOWN') {
    return (
      <span className="money money-unknown" data-testid={testId} data-status="UNKNOWN">
        {text.unknown(text.unknownReasons[amount.reason] ?? amount.reason)}
      </span>
    );
  }
  return <Sol value={amount.value} testId={testId} />;
}

/**
 * 261121-paper-real-unico U1 (C17; BR5.1 to BR5.5, BR6.1, BR7.1, BR7.2, BR9.1 to BR9.3; NFR5.x, NFR6.x): ONE block
 * of the P&L by mode, variant {@code full}. It receives a single market (no prop takes both) and never adds,
 * subtracts nor converts a value: the totals arrive ready from the server; it only writes them (US$ for PAPER, SOL for
 * REAL). Five distinct states: loading (never zero), ready, empty, unknown (total unknown, realized and fees exact)
 * and error ("Tentar de novo" reads the route again and updates only this block). The PAPER block always shows its
 * fixed disclaimer when it shows a value. 261121-paper-real-unico U2 (BR10.4): the REAL block has no fixed note any
 * more and its fees are just "Taxas", as in the PAPER: the realized is gross and the total takes the fees out.
 */
export function PnlModeBlock(props: Props) {
  const { market, load, onRetry, period, focusHeading } = props;
  const zone = props.timeZone ?? browserTimeZone();
  const id = `pnl-block-${market}`;
  const messages = text[market];
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    if (focusHeading) {
      heading.current?.focus();
    }
  }, [focusHeading]);

  return (
    <section aria-labelledby={`${id}-title`} className="pnl-mode-block" data-testid={id}>
      <h2 id={`${id}-title`} ref={heading} tabIndex={-1} data-testid={`${id}-title`}>
        {messages.heading}{' '}
        {market === 'PAPER' ? (
          <SimulatedBadge testId={`${id}-badge`} />
        ) : (
          <RealBadge testId={`${id}-badge`} />
        )}
      </h2>
      {period && <p className="muted">{period}</p>}
      {load.kind === 'loading' && (
        <p role="status" aria-busy="true" data-testid={`${id}-loading`}>
          {messages.loading}
        </p>
      )}
      {load.kind === 'error' && (
        <div role="alert" className="notice notice-error" data-testid={`${id}-error`}>
          <p>{messages.error}</p>
          <p className="muted" data-testid={`${id}-code`}>
            {text.supportCode(load.code)}
          </p>
          <button type="button" className="secondary" data-testid={`${id}-retry`} onClick={onRetry}>
            {text.retry}
          </button>
        </div>
      )}
      {load.kind === 'ready' && props.market === 'PAPER' && (
        <PaperContent block={(props.load as { block: PaperModeBlock }).block} zone={zone} />
      )}
      {load.kind === 'ready' && props.market === 'REAL' && (
        <RealContent block={(props.load as { block: RealModeBlock }).block} zone={zone} />
      )}
    </section>
  );
}

function PaperContent({ block, zone }: { block: PaperModeBlock; zone: string }) {
  const id = 'pnl-block-PAPER';
  if (block.empty) {
    return (
      <div data-testid={`${id}-empty`}>
        <p>{text.PAPER.empty}</p>
        <Link href="/bots/new">{text.PAPER.emptyLink}</Link>
      </div>
    );
  }
  const unknown = block.total.status === 'UNKNOWN';
  return (
    <>
      <p role="note" className="notice" data-testid={`${id}-disclaimer`}>
        {block.disclaimer}
      </p>
      {unknown && (
        <p role="note" className="notice notice-warning" data-testid={`${id}-partial`}>
          {text.partial}
        </p>
      )}
      <dl className="totals">
        <dt>{text.PAPER.total}</dt>
        <dd aria-live="polite" aria-atomic="true" aria-label={text.PAPER.total}>
          {unknown ? (
            <span className="money money-unknown" data-testid={`${id}-total`} data-status="UNKNOWN">
              {text.totalUnknown}
            </span>
          ) : (
            <UsdAmount amount={block.total} testId={`${id}-total`} />
          )}
        </dd>
        <dt>{text.realized}</dt>
        <dd>
          <Usd value={block.realized} testId={`${id}-realized`} />
        </dd>
        <dt>{text.unrealized}</dt>
        <dd>
          <UsdAmount amount={block.unrealized} testId={`${id}-unrealized`} />
          <p className="muted">
            {text.unrealizedNow(formatClock(new Date(block.unrealizedAsOf), zone))}
          </p>
        </dd>
        <dt>{text.PAPER.fees}</dt>
        <dd>
          <Usd value={block.fees} testId={`${id}-fees`} />
        </dd>
      </dl>
      <TableScroll label={`${text.bots}: ${text.PAPER.heading}`} testId={`${id}-bots-scroll`}>
        <table data-testid={`${id}-bots`}>
          <caption>{text.bots}</caption>
          <thead>
            <tr>
              <th scope="col">{text.columns.bot}</th>
              <th scope="col">{text.columns.status}</th>
              <th scope="col">{text.columns.total}</th>
            </tr>
          </thead>
          <tbody>
            {block.bots.map((bot) => (
              <tr key={bot.botId} data-testid={`${id}-bot-${bot.botId}`}>
                <th scope="row">{bot.name}</th>
                <td>{text.botStatus[bot.botStatus] ?? bot.botStatus}</td>
                <td>
                  <UsdAmount amount={bot.total} testId={`${id}-bot-total-${bot.botId}`} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </TableScroll>
    </>
  );
}

function RealContent({ block, zone }: { block: RealModeBlock; zone: string }) {
  const id = 'pnl-block-REAL';
  if (block.empty) {
    return (
      <div data-testid={`${id}-empty`}>
        <p>{text.REAL.empty}</p>
        <Link href="/real">{text.REAL.emptyLink}</Link>
      </div>
    );
  }
  const unknown = block.totalLamports.status === 'UNKNOWN';
  return (
    <>
      {unknown && (
        <p role="note" className="notice notice-warning" data-testid={`${id}-partial`}>
          {text.partial}
        </p>
      )}
      <dl className="totals">
        <dt>{text.REAL.total}</dt>
        <dd aria-live="polite" aria-atomic="true" aria-label={text.REAL.total}>
          {unknown ? (
            <span className="money money-unknown" data-testid={`${id}-total`} data-status="UNKNOWN">
              {text.totalUnknown}
            </span>
          ) : (
            <SolAmount amount={block.totalLamports} testId={`${id}-total`} />
          )}
        </dd>
        <dt>{text.realized}</dt>
        <dd>
          <Sol value={block.realizedLamports} testId={`${id}-realized`} />
        </dd>
        <dt>{text.unrealized}</dt>
        <dd>
          <SolAmount amount={block.unrealizedLamports} testId={`${id}-unrealized`} />
          <p className="muted">
            {text.unrealizedNow(formatClock(new Date(block.unrealizedAsOf), zone))}
          </p>
        </dd>
        <dt>{text.REAL.fees}</dt>
        <dd>
          <Sol value={block.feesLamports} testId={`${id}-fees`} />
        </dd>
      </dl>
      <TableScroll label={`${text.bots}: ${text.REAL.heading}`} testId={`${id}-bots-scroll`}>
        <table data-testid={`${id}-bots`}>
          <caption>{text.bots}</caption>
          <thead>
            <tr>
              <th scope="col">{text.columns.bot}</th>
              <th scope="col">{text.columns.status}</th>
              <th scope="col">{text.columns.total}</th>
            </tr>
          </thead>
          <tbody>
            {block.bots.map((bot) => (
              <tr key={bot.botId} data-testid={`${id}-bot-${bot.botId}`}>
                <th scope="row">{bot.name}</th>
                <td>{text.botStatus[bot.botStatus] ?? bot.botStatus}</td>
                <td>
                  <SolAmount amount={bot.totalLamports} testId={`${id}-bot-total-${bot.botId}`} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </TableScroll>
    </>
  );
}
