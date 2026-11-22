'use client';

import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { PnlReport } from '@/lib/api/guest-api';
import { abbreviateMint, formatClock, formatDecimal } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';
import { AppShell } from '@/components/shell/AppShell';
import { useSession } from '@/features/auth/SessionProvider';
import { useRequireSession } from '@/features/auth/useRequireSession';
import { LedgerList } from '@/features/pnl/LedgerList';
import { addDays, todayIn, toUtcPeriod } from '@/features/pnl/period';
import { ReconciliationPanel } from '@/features/pnl/ReconciliationPanel';
import { PnlAmountView, SignedValue } from '@/features/pnl/SignedValue';
import type { PaperModeBlock, RealModeBlock } from '@/lib/api/guest-api';
import { SimulatedBadge } from '@/components/SimulatedBadge';
import { TableScroll } from '@/components/TableScroll';
import { failureCode, paperLoad, realLoad } from '@/features/pnl/by-mode';
import { modeMessages } from '@/features/pnl/mode-messages';
import { PnlModeBlock, type BlockLoad } from '@/features/pnl/PnlModeBlock';

const text = messages.pnl;

type Load =
  | { kind: 'loading' }
  | { kind: 'error' }
  | { kind: 'invalid' }
  | { kind: 'ready'; report: PnlReport };

/** The browser's time zone; the screen converts the chosen days to UTC instants (project.md). */
function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

/**
 * S6 - P&L (US5.1, US5.2; BR6.1 to BR6.6, BR8.4, BR10.1): "MODO PAPER (simulado)" in the title; the period chosen
 * in the person's time zone and sent in UTC; realized, unrealized and fees separated, each with sign and word;
 * unknown with its reason and "parcial"; "sem liquidez" with source and time; loading, empty and error are distinct
 * and nothing partial looks complete. Decimals stay text from the API to the screen.
 */
export function PnlScreen({ timeZone, now }: { timeZone?: string; now?: Date } = {}) {
  const zone = timeZone ?? browserTimeZone();
  const status = useRequireSession('/pnl');
  const api = useApi();
  const { expire } = useSession();
  const heading = useRef<HTMLHeadingElement>(null);
  const today = todayIn(zone, now ?? new Date());
  const [fromDay, setFromDay] = useState(addDays(today, -6));
  const [toDay, setToDay] = useState(today);
  const [period, setPeriod] = useState(() => toUtcPeriod(addDays(today, -6), today, zone));
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [paperBlock, setPaperBlock] = useState<BlockLoad<PaperModeBlock>>({ kind: 'loading' });
  const [realBlock, setRealBlock] = useState<BlockLoad<RealModeBlock>>({ kind: 'loading' });
  const [retried, setRetried] = useState<'PAPER' | 'REAL' | null>(null);

  /**
   * 261121-paper-real-unico U1 (BR5.3, NFR4.8): one read of C4 fills both blocks on load; "Tentar de novo" of a block
   * reads the route again and updates ONLY that block, leaving the other as it was.
   */
  const fetchByMode = useCallback(
    async (only?: 'PAPER' | 'REAL') => {
      if (only !== 'REAL') {
        setPaperBlock({ kind: 'loading' });
      }
      if (only !== 'PAPER') {
        setRealBlock({ kind: 'loading' });
      }
      try {
        const answer = await api.getPnlByMode();
        if (only !== 'REAL') {
          setPaperBlock(paperLoad(answer.paper));
        }
        if (only !== 'PAPER') {
          setRealBlock(realLoad(answer.real));
        }
      } catch (failure) {
        if (failure instanceof ApiError && failure.status === 401) {
          expire();
          return;
        }
        const code = failureCode(failure);
        if (only !== 'REAL') {
          setPaperBlock({ kind: 'error', code });
        }
        if (only !== 'PAPER') {
          setRealBlock({ kind: 'error', code });
        }
      }
    },
    [api, expire],
  );

  useEffect(() => {
    if (status === 'authenticated') {
      void fetchByMode();
    }
  }, [status, fetchByMode]);

  function retryBlock(market: 'PAPER' | 'REAL') {
    setRetried(market);
    void fetchByMode(market);
  }

  const fetchReport = useCallback(async () => {
    if (!period) {
      setLoad({ kind: 'invalid' });
      return;
    }
    setLoad({ kind: 'loading' });
    try {
      setLoad({ kind: 'ready', report: await api.getPnl(period) });
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        expire();
        return;
      }
      setLoad({ kind: 'error' });
    }
  }, [api, expire, period]);

  useEffect(() => {
    if (status === 'authenticated') {
      void fetchReport();
    }
  }, [status, fetchReport]);
  function apply(event: FormEvent) {
    event.preventDefault();
    setPeriod(toUtcPeriod(fromDay, toDay, zone));
  }

  return (
    <AppShell current="pnl" titleId="pnl-title">
      <h1 id="pnl-title" ref={heading} tabIndex={-1} data-testid="pnl-title">
        {modeMessages.title}
      </h1>
      <p className="muted">{modeMessages.intro}</p>
      <div className="pnl-modes" data-testid="pnl-modes">
        <PnlModeBlock
          market="PAPER"
          load={paperBlock}
          period={modeMessages.sinceStart}
          timeZone={zone}
          focusHeading={retried === null || retried === 'PAPER'}
          onRetry={() => retryBlock('PAPER')}
        />
        <PnlModeBlock
          market="REAL"
          load={realBlock}
          period={modeMessages.sinceStart}
          timeZone={zone}
          focusHeading={retried === 'REAL'}
          onRetry={() => retryBlock('REAL')}
        />
      </div>
      <h2 data-testid="pnl-paper-section">
        {modeMessages.paperSection} <SimulatedBadge testId="pnl-paper-badge" />{' '}
        <span className="tag">{text.paperLabel}</span>
      </h2>
      <p className="muted">{text.intro}</p>
      <form onSubmit={apply} data-testid="pnl-period">
        <fieldset>
          <legend>{text.periodLegend}</legend>
          <label htmlFor="pnl-from">{text.from}</label>
          <input
            id="pnl-from"
            type="date"
            value={fromDay}
            data-testid="pnl-from"
            onChange={(event) => setFromDay(event.target.value)}
          />
          <label htmlFor="pnl-to">{text.to}</label>
          <input
            id="pnl-to"
            type="date"
            value={toDay}
            data-testid="pnl-to"
            onChange={(event) => setToDay(event.target.value)}
          />
          <p className="muted">{text.periodHelp(zone)}</p>
          <button type="submit" data-testid="pnl-apply">
            {text.apply}
          </button>
        </fieldset>
      </form>
      {load.kind === 'invalid' && (
        <p role="alert" className="notice notice-error" data-testid="pnl-invalid">
          {text.invalidPeriod}
        </p>
      )}
      {load.kind === 'loading' && (
        <p role="status" data-testid="pnl-loading">
          {text.loading}
        </p>
      )}
      {load.kind === 'error' && (
        <div role="alert" className="notice notice-error" data-testid="pnl-error">
          <p>{text.error}</p>
          <button
            type="button"
            className="secondary"
            data-testid="pnl-retry"
            onClick={() => void fetchReport()}
          >
            {text.retry}
          </button>
        </div>
      )}
      {load.kind === 'ready' && <Report report={load.report} timeZone={zone} />}
      <ReconciliationPanel timeZone={zone} />
      <LedgerList timeZone={zone} />
    </AppShell>
  );
}

function Report({ report, timeZone }: { report: PnlReport; timeZone: string }) {
  if (report.empty) {
    return (
      <p className="notice" data-testid="pnl-empty">
        {text.empty}
      </p>
    );
  }
  return (
    <section aria-labelledby="pnl-totals-title" data-testid="pnl-report">
      <h2 id="pnl-totals-title">{text.totalsCaption}</h2>
      {report.partial && (
        <p role="note" className="notice notice-warning" data-testid="pnl-partial">
          {text.partial}
        </p>
      )}
      <dl className="totals">
        <dt>{text.realized}</dt>
        <dd>
          <SignedValue value={report.realized} testId="pnl-total-realized" />
        </dd>
        <dt>
          {text.unrealized}
          {report.partial && (
            <span className="tag" data-testid="pnl-partial-tag">
              {' '}
              {text.partialTag}
            </span>
          )}
        </dt>
        <dd>
          <PnlAmountView amount={report.unrealized} testId="pnl-total-unrealized" />
          <p className="muted" data-testid="pnl-unrealized-now">
            {text.unrealizedNow(formatClock(new Date(report.unrealizedAsOf), timeZone))}
          </p>
        </dd>
        <dt>{text.fees}</dt>
        <dd data-testid="pnl-total-fees">
          {formatDecimal(report.fees)} <span className="muted">({text.feesPaid})</span>
        </dd>
      </dl>
      <TableScroll label={text.byTokenCaption} testId="pnl-lines-scroll">
        <table data-testid="pnl-lines">
          <caption>{text.byTokenCaption}</caption>
          <thead>
            <tr>
              <th scope="col">{text.columns.token}</th>
              <th scope="col">{text.columns.realized}</th>
              <th scope="col">{text.columns.unrealized}</th>
              <th scope="col">{text.columns.fees}</th>
              <th scope="col">{text.columns.mark}</th>
            </tr>
          </thead>
          <tbody>
            {report.byToken.map((line) => (
              <tr key={`${line.chain}-${line.mint}`} data-testid={`pnl-line-${line.mint}`}>
                <th scope="row" title={line.mint}>
                  {abbreviateMint(line.mint)}
                </th>
                <td>
                  <SignedValue value={line.realized} testId={`pnl-line-realized-${line.mint}`} />
                </td>
                <td>
                  <PnlAmountView
                    amount={line.unrealized}
                    testId={`pnl-line-unrealized-${line.mint}`}
                  />
                </td>
                <td>{formatDecimal(line.fees)}</td>
                <td data-testid={`pnl-line-mark-${line.mint}`}>
                  {line.noLiquidity && <span className="tag">{text.noLiquidity}</span>}{' '}
                  {line.mark.status === 'KNOWN'
                    ? text.markAt(
                        `${formatDecimal(line.mark.value)} (${line.mark.source.name})`,
                        formatClock(new Date(line.mark.observedAt), timeZone),
                      )
                    : text.markUnknown}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </TableScroll>
    </section>
  );
}
