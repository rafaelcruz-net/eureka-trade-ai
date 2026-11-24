'use client';

import { useCallback, useEffect, useReducer, useRef } from 'react';
import { ApiError, RequestCancelled } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { RealEligibility } from '@/lib/api/guest-api';
import { useSession } from '@/features/auth/SessionProvider';
import { INITIAL_MODE_CARDS, modeCardsReducer } from '@/features/dashboard/dashboard-reducer';
import {
  windowedRefresh,
  type TimerHandle,
  type WindowedRefreshOptions,
} from '@/features/dashboard/concurrency';
import { useDashboard } from '@/features/dashboard/DashboardProvider';
import { dashboardMessages } from '@/features/dashboard/messages';
import { ReconciliationLine } from '@/features/dashboard/ReconciliationLine';
import { failureCode, paperLoad, realLoad } from '@/features/pnl/by-mode';
import { PnlModeBlock, type RealEmptyReason } from '@/features/pnl/PnlModeBlock';

const text = dashboardMessages.modes;
export const MODE_CARD_WINDOW_MS = 30_000;

type Market = 'PAPER' | 'REAL';

/** BR7.4: why the REAL may be empty, from C2. */
function realEmptyReason(eligibility: RealEligibility): RealEmptyReason {
  if (eligibility.available) {
    return { kind: 'available' };
  }
  return eligibility.reason ? { kind: 'reason', reason: eligibility.reason } : { kind: 'unknown' };
}

/**
 * 261121-paper-real-unico U3 (BR7.1 to BR7.5, BR2.8, BR3.2, BR3.4, BR9.2; AC4.3.1 to AC4.3.4, AC4.2.2): the dashboard
 * shows two cards, "Resultado simulado" (US$) and "Resultado real" (SOL), since the start and never added. The load
 * reads C4 without `market` with `period=ALL` and C2 at the same time. Each card has its own states, its own
 * "Tentar de novo" (C4 with its `market`, at once, focus on its title) and its own window of 30 s: an event of its mode
 * (`pnl.updated` for the simulated; `real.order.state` final and `real.conditional.state` for the real) reads C4 with
 * its `market` without going back to loading, at most once per window, and an event inside the window is read at the
 * end of it. The total is announced at most once every 30 s per card and never on the first load. The component
 * composes and never formats: the values are written by PnlModeBlock. `now`, `setTimer` and `clearTimer` are
 * injectable for the tests.
 */
export function DashboardModeCards({
  now = () => Date.now(),
  setTimer,
  clearTimer,
}: {
  now?: () => number;
  setTimer?: (run: () => void, ms: number) => TimerHandle;
  clearTimer?: (handle: TimerHandle) => void;
} = {}) {
  const api = useApi();
  const { status, expire } = useSession();
  const { state: dashboard, loader, timeZone, subscribeModeCards } = useDashboard();
  const [state, dispatch] = useReducer(modeCardsReducer, INITIAL_MODE_CARDS);
  const requestIds = useRef(0);
  const slots = useRef<Partial<Record<string, AbortController>>>({});
  const paperTitle = useRef<HTMLHeadingElement>(null);
  const realTitle = useRef<HTMLHeadingElement>(null);
  const clock = useRef(now);
  clock.current = now;

  const start = useCallback((slot: string) => {
    slots.current[slot]?.abort();
    const controller = new AbortController();
    slots.current[slot] = controller;
    requestIds.current += 1;
    return { requestId: requestIds.current, signal: controller.signal };
  }, []);

  useEffect(() => {
    const open = slots.current;
    return () => Object.values(open).forEach((controller) => controller?.abort());
  }, []);

  const failed = useCallback(
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

  /** The first load: C4 without market and C2 in parallel. */
  const loadAll = useCallback(async () => {
    const { requestId, signal } = start('cards');
    dispatch({ type: 'MODE_CARDS_REQUESTED', requestId });
    const eligibility = api.getRealEligibility().then(
      (answer) =>
        dispatch({ type: 'REAL_ELIGIBILITY_LOADED', realUnavailable: realEmptyReason(answer) }),
      () => dispatch({ type: 'REAL_ELIGIBILITY_LOADED', realUnavailable: { kind: 'unknown' } }),
    );
    try {
      const answer = await api.getPnlByMode({ period: { kind: 'ALL' } }, { signal });
      const at = clock.current();
      const paper = paperLoad(answer.paper);
      const real = realLoad(answer.real);
      if (paper) {
        dispatch({
          type: 'MODE_CARD_LOADED',
          market: 'PAPER',
          requestId,
          load: paper,
          at,
          initial: true,
        });
      }
      if (real) {
        dispatch({
          type: 'MODE_CARD_LOADED',
          market: 'REAL',
          requestId,
          load: real,
          at,
          initial: true,
        });
      }
    } catch (failure) {
      if (!failed(failure)) {
        const code = failureCode(failure);
        dispatch({ type: 'MODE_CARD_FAILED', market: 'PAPER', requestId, code });
        dispatch({ type: 'MODE_CARD_FAILED', market: 'REAL', requestId, code });
      }
    }
    await eligibility;
  }, [api, failed, start]);

  /** One card again, with its `market`: by event (no loading, never announced on top of a failure) or by retry. */
  const loadCard = useCallback(
    async (market: Market, how: 'event' | 'retry') => {
      const { requestId, signal } = start(`card:${market}`);
      dispatch({ type: 'MODE_CARD_REQUESTED', market, requestId, showLoading: how === 'retry' });
      try {
        const answer = await api.getPnlByMode({ market, period: { kind: 'ALL' } }, { signal });
        const load = market === 'PAPER' ? paperLoad(answer.paper) : realLoad(answer.real);
        if (load) {
          dispatch({
            type: 'MODE_CARD_LOADED',
            market,
            requestId,
            load,
            at: clock.current(),
            initial: how === 'retry',
          });
        }
      } catch (failure) {
        if (!failed(failure)) {
          dispatch({
            type: 'MODE_CARD_FAILED',
            market,
            requestId,
            code: failureCode(failure),
            background: how === 'event',
          });
        }
      }
      if (how === 'retry') {
        (market === 'PAPER' ? paperTitle : realTitle).current?.focus();
      }
    },
    [api, failed, start],
  );

  useEffect(() => {
    if (status === 'authenticated') {
      void loadAll();
    }
  }, [status, loadAll]);

  // BR7.5: one window of 30 s per card; the events of the provider trigger it.
  useEffect(() => {
    const options: WindowedRefreshOptions = {
      windowMs: MODE_CARD_WINDOW_MS,
      now: () => clock.current(),
      ...(setTimer ? { setTimer } : {}),
      ...(clearTimer ? { clearTimer } : {}),
    };
    const windows = {
      PAPER: windowedRefresh(() => loadCard('PAPER', 'event'), options),
      REAL: windowedRefresh(() => loadCard('REAL', 'event'), options),
    };
    const stop = subscribeModeCards((market) => windows[market].trigger());
    return () => {
      stop();
      windows.PAPER.dispose();
      windows.REAL.dispose();
    };
  }, [clearTimer, loadCard, setTimer, subscribeModeCards]);

  return (
    <section
      aria-labelledby="dashboard-modes-title"
      className="dashboard-card dashboard-mode-cards"
      data-testid="dashboard-mode-cards"
    >
      <h2 id="dashboard-modes-title">{text.title}</h2>
      <p className="muted">{text.intro}</p>
      <div className="pnl-modes">
        <PnlModeBlock
          variant="compact"
          market="PAPER"
          load={state.PAPER.load}
          announce={state.PAPER.announce}
          headingRef={paperTitle}
          onRetry={() => void loadCard('PAPER', 'retry')}
        />
        <PnlModeBlock
          variant="compact"
          market="REAL"
          load={state.REAL.load}
          announce={state.REAL.announce}
          realUnavailable={state.REAL.realUnavailable}
          headingRef={realTitle}
          onRetry={() => void loadCard('REAL', 'retry')}
        />
      </div>
      <ReconciliationLine
        slice={dashboard.reconciliation}
        timeZone={timeZone}
        onRetry={() => void loader.refreshReconciliation(true)}
      />
    </section>
  );
}
