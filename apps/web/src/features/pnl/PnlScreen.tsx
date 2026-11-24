'use client';

import { useRouter, useSearchParams } from 'next/navigation';
import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { ApiError, RequestCancelled } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { PnlReport } from '@/lib/api/guest-api';
import { abbreviateMint, formatClock, formatDecimal } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';
import { AppShell } from '@/components/shell/AppShell';
import { TableScroll } from '@/components/TableScroll';
import { useSession } from '@/features/auth/SessionProvider';
import { useRequireSession } from '@/features/auth/useRequireSession';
import { failureCode, paperLoad, realLoad } from '@/features/pnl/by-mode';
import {
  ComparisonView,
  comparisonAddress,
  parseComparison,
  periodRequest,
  type ComparisonSelection,
} from '@/features/pnl/ComparisonView';
import { LedgerList } from '@/features/pnl/LedgerList';
import { modeMessages } from '@/features/pnl/mode-messages';
import {
  PeriodSelector,
  periodAddress,
  periodFromAddress,
  type AppliedPeriod,
} from '@/features/pnl/PeriodSelector';
import { PnlModeBlock } from '@/features/pnl/PnlModeBlock';
import {
  initialPnlScreenState,
  pnlScreenReducer,
  type PnlSlot,
} from '@/features/pnl/pnl-screen-state';
import { ReconciliationPanel } from '@/features/pnl/ReconciliationPanel';
import { PnlAmountView, SignedValue } from '@/features/pnl/SignedValue';

const text = messages.pnl;
const periodText = modeMessages.period;

/** The browser's time zone; the screen converts the chosen days to UTC instants (project.md). */
function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

/** The address of /pnl with the period and the comparison (BR2.6, BR5.6). */
function addressOf(period: AppliedPeriod, selection: ComparisonSelection | null): string {
  const params = new URLSearchParams(periodAddress(period));
  if (selection !== null) {
    params.set('comparar', comparisonAddress(selection));
  }
  const query = params.toString();
  return query ? `/pnl?${query}` : '/pnl';
}

/**
 * S6 - P&L (US5.1, US5.2; BR6.1 to BR6.6, BR8.4, BR10.1): realized, unrealized and fees separated, each with sign and
 * word; unknown with its reason and "parcial"; "sem liquidez" with source and time; loading, empty and error are
 * distinct and nothing partial looks complete. Decimals stay text from the API to the screen.
 *
 * 261121-paper-real-unico U3 (decision F-3; BR2.1 to BR2.9, BR3.1 to BR3.4, BR5.6, BR6.2; AC7.1.x, AC7.2.3): a pure
 * reducer (pnl-screen-state.ts) with one AbortController per slot, all aborted when the screen leaves. The period
 * starts on "Desde o início" and lives in the address (an invalid one opens on "Desde o início"); C4 is read without
 * `market` on the load and on every change; "Tentar de novo" of a block reads C4 with its `market` and the same
 * period and puts the focus on the title of that block. The table by token of the PAPER is inside the block Simulado
 * and is read from `GET /api/v1/pnl` with the same pair only with a concrete period; in "Desde o início" a note takes
 * its place (BR2.9). The comparison (C6) reads the same period. Nothing is ever added across the modes.
 */
export function PnlScreen({ timeZone, now }: { timeZone?: string; now?: Date } = {}) {
  const zone = timeZone ?? browserTimeZone();
  const status = useRequireSession('/pnl');
  const api = useApi();
  const router = useRouter();
  const search = useSearchParams();
  const { expire } = useSession();
  const clock = useCallback(() => now ?? new Date(), [now]);
  const [state, dispatch] = useReducer(pnlScreenReducer, undefined, () =>
    initialPnlScreenState(periodFromAddress(search, zone, now ?? new Date())),
  );
  const [selection, setSelection] = useState<ComparisonSelection | null>(() =>
    parseComparison(search?.get('comparar')),
  );
  const [periodTouched, setPeriodTouched] = useState(false);
  const controllers = useRef<Partial<Record<PnlSlot, AbortController>>>({});
  const requestIds = useRef(0);
  const { period } = state;

  useEffect(() => {
    const slots = controllers.current;
    return () => Object.values(slots).forEach((controller) => controller?.abort());
  }, []);

  /** A fresh request of a slot: the previous one of the same slot is aborted. */
  const start = useCallback((slot: PnlSlot) => {
    controllers.current[slot]?.abort();
    const controller = new AbortController();
    controllers.current[slot] = controller;
    requestIds.current += 1;
    return { requestId: requestIds.current, signal: controller.signal };
  }, []);

  /** A failure that is not the business of the block: cancelled (ignored) or the expired session. */
  const handled = useCallback(
    (failure: unknown) => {
      if (failure instanceof RequestCancelled) {
        return true;
      }
      if (failure instanceof ApiError && failure.status === 401) {
        expire();
        return true;
      }
      return false;
    },
    [expire],
  );

  const readByMode = useCallback(async () => {
    const { requestId, signal } = start('byMode');
    dispatch({ type: 'BY_MODE_REQUESTED', requestId });
    try {
      const answer = await api.getPnlByMode({ period: periodRequest(period) }, { signal });
      dispatch({
        type: 'BY_MODE_LOADED',
        requestId,
        paper: paperLoad(answer.paper),
        real: realLoad(answer.real),
      });
    } catch (failure) {
      if (!handled(failure)) {
        dispatch({ type: 'BY_MODE_FAILED', requestId, code: failureCode(failure) });
      }
    }
  }, [api, handled, period, start]);

  const readTokens = useCallback(async () => {
    if (period.utc === null) {
      controllers.current.tokens?.abort();
      dispatch({ type: 'TOKENS_IDLE' });
      return;
    }
    const { requestId, signal } = start('tokens');
    dispatch({ type: 'TOKENS_REQUESTED', requestId });
    try {
      const report = await api.getPnl(
        { from: period.utc.from, to: period.utc.to },
        { signal, retry: false },
      );
      dispatch({ type: 'TOKENS_LOADED', requestId, report });
    } catch (failure) {
      if (!handled(failure)) {
        dispatch({ type: 'TOKENS_FAILED', requestId, code: failureCode(failure) });
      }
    }
  }, [api, handled, period, start]);

  useEffect(() => {
    if (status === 'authenticated') {
      void readByMode();
      void readTokens();
    }
  }, [status, readByMode, readTokens]);

  async function retryBlock(market: 'PAPER' | 'REAL') {
    const { requestId, signal } = start(`retry:${market}`);
    dispatch({ type: 'BLOCK_RETRY_REQUESTED', market, requestId });
    try {
      const answer = await api.getPnlByMode({ market, period: periodRequest(period) }, { signal });
      dispatch({
        type: 'BLOCK_RETRY_LOADED',
        market,
        requestId,
        load: market === 'PAPER' ? paperLoad(answer.paper) : realLoad(answer.real),
      });
    } catch (failure) {
      if (!handled(failure)) {
        dispatch({ type: 'BLOCK_RETRY_FAILED', market, requestId, code: failureCode(failure) });
      }
    }
  }

  function applyPeriod(next: AppliedPeriod) {
    setPeriodTouched(true);
    dispatch({ type: 'PERIOD_APPLIED', period: next });
    router.replace(addressOf(next, selection));
  }

  function select(next: ComparisonSelection | null) {
    setSelection(next);
    router.replace(addressOf(period, next));
  }

  const periodLine =
    period.kind === 'ALL' ? modeMessages.sinceStart : periodText.options[period.kind];
  return (
    <AppShell current="pnl" titleId="pnl-title">
      <h1 id="pnl-title" tabIndex={-1} data-testid="pnl-title">
        {modeMessages.title}
      </h1>
      <p className="muted">{modeMessages.intro}</p>
      <PeriodSelector value={period} onChange={applyPeriod} timeZone={zone} now={clock} />
      <div className="pnl-modes" data-testid="pnl-modes">
        <PnlModeBlock
          market="PAPER"
          load={state.paper}
          period={periodLine}
          timeZone={zone}
          focusHeading={state.focus === 'PAPER' || (state.focus === null && !periodTouched)}
          onRetry={() => void retryBlock('PAPER')}
        >
          <TokensSection
            state={state.tokens}
            concrete={period.utc !== null}
            timeZone={zone}
            onRetry={() => void readTokens()}
          />
        </PnlModeBlock>
        <PnlModeBlock
          market="REAL"
          load={state.real}
          period={periodLine}
          timeZone={zone}
          focusHeading={state.focus === 'REAL'}
          onRetry={() => void retryBlock('REAL')}
        />
      </div>
      <ComparisonView selection={selection} onSelect={select} period={period} timeZone={zone} />
      <ReconciliationPanel timeZone={zone} />
      <LedgerList timeZone={zone} />
    </AppShell>
  );
}

/**
 * BR2.9 (decision of the founder): "Ver por token" of the PAPER, with its own loading, error with code and "Tentar de
 * novo"; in "Desde o início" the note "Escolha um período para ver o resultado por token" and no request.
 */
function TokensSection({
  state,
  concrete,
  timeZone,
  onRetry,
}: {
  state: ReturnType<typeof initialPnlScreenState>['tokens'];
  concrete: boolean;
  timeZone: string;
  onRetry: () => void;
}) {
  return (
    <section aria-labelledby="pnl-paper-section" className="pnl-by-token">
      <h3 id="pnl-paper-section" data-testid="pnl-paper-section">
        {periodText.byToken}
      </h3>
      {!concrete && (
        <p className="muted" data-testid="pnl-by-token-note">
          {periodText.byTokenNote}
        </p>
      )}
      {concrete && state.kind === 'loading' && (
        <p role="status" data-testid="pnl-loading">
          {text.loading}
        </p>
      )}
      {concrete && state.kind === 'error' && (
        <div role="alert" className="notice notice-error" data-testid="pnl-error">
          <p>{text.error}</p>
          <p className="muted" data-testid="pnl-error-code">
            {modeMessages.supportCode(state.code)}
          </p>
          <button type="button" className="secondary" data-testid="pnl-retry" onClick={onRetry}>
            {text.retry}
          </button>
        </div>
      )}
      {concrete && state.kind === 'ready' && <Report report={state.report} timeZone={timeZone} />}
    </section>
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
      <h4 id="pnl-totals-title">{text.totalsCaption}</h4>
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
