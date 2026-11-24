import { ApiError } from '@/lib/api/client';
import type { BotSummary, GuestApi } from '@/lib/api/guest-api';
import { createLimiter, singleFlight } from '@/features/dashboard/concurrency';
import {
  ALL_STATES,
  type BotLists,
  type BotState,
  type BotTrade,
  type DashboardAction,
} from '@/features/dashboard/dashboard-reducer';
import type { TradeNotice } from '@/features/dashboard/envelopes';
import { dashboardWarn } from '@/features/dashboard/warn';

/**
 * The calls of the dashboard, outside React so their order and concurrency are tested alone (performance-design.md,
 * NFR3.1, NFR3.3, NFR3.4; BR1.5, BR2.3, BR3.3, BR8.1). Every call uses the typed REST client of the app (same renewal
 * on 401 and the same ProblemDetail handling) and only ids that came back from the API itself: no tenant or person id
 * ever leaves the dashboard (BR8.1, NFR5.1).
 *
 * - Bots: the full load by state (`GET /bots?state=…&limit=100` for each of the six states, in parallel, following
 *   `nextCursor` to `null`), one at a time with at most one more after it (BR1.5).
 * - Last order: `GET /bots/{botId}/trades?limit=1` of every bot that is not a draft, at most 3 at the same time
 *   (NFR3.3); a bot already read is not read again by a later full load (the order of a STOPPED bot never changes),
 *   only when `trade.recorded` names it (BR2.3).
 * - Reconciliation: one in progress and at most one pending (BR3.3, NFR3.4). 261121-paper-real-unico U3 (BR7.1): the
 *   P&L of the UTC day left the dashboard; the cards by mode read C4 themselves (DashboardModeCards).
 * - Sources: the fallback `GET /scanner/tokens?limit=1` when no `data.status` arrives in 5 s (F1 step 3).
 */
export const PAGE_LIMIT = 100;
export const LAST_TRADE_CONCURRENCY = 3;

export interface LoaderOptions {
  api: GuestApi;
  dispatch: (action: DashboardAction) => void;
  now: () => Date;
  /** An authenticated call answered 401 even after the renewal: the session-expired flow of U3. */
  onUnauthenticated: () => void;
}

export type DashboardLoader = ReturnType<typeof createDashboardLoader>;

const NEEDS_LAST_TRADE: readonly BotState[] = ['ACTIVE', 'PAUSED', 'CLOSING_PENDING', 'STOPPED'];

export function createDashboardLoader(options: LoaderOptions) {
  const { api, dispatch, now } = options;
  const limiter = createLimiter(LAST_TRADE_CONCURRENCY);
  /** Bots whose last order was already read (kept for the life of the screen). */
  const lastTradeRead = new Set<string>();
  let lastTradeLoadedOnce = false;
  let showBotsLoading = true;
  let showReconciliationLoading = true;

  function isUnauthenticated(failure: unknown): boolean {
    if (failure instanceof ApiError && failure.status === 401) {
      options.onUnauthenticated();
      return true;
    }
    return false;
  }

  async function listState(state: BotState): Promise<{ items: BotSummary[]; activeLimit: number }> {
    const items: BotSummary[] = [];
    let cursor: string | null = null;
    let activeLimit = 3;
    do {
      const page = await api.listBots({ state, cursor, limit: PAGE_LIMIT });
      items.push(...page.items);
      activeLimit = page.activeLimit;
      cursor = page.nextCursor;
    } while (cursor);
    return { items, activeLimit };
  }

  async function loadBots(): Promise<void> {
    if (showBotsLoading) {
      dispatch({ type: 'BOTS_LOADING' });
      showBotsLoading = false;
    }
    let lists: BotLists;
    let activeLimit = 3;
    try {
      const results = await Promise.all(ALL_STATES.map((state) => listState(state)));
      lists = {} as BotLists;
      ALL_STATES.forEach((state, index) => {
        const result = results[index] as { items: BotSummary[]; activeLimit: number };
        lists[state] = result.items;
        activeLimit = result.activeLimit;
      });
    } catch (failure) {
      if (!isUnauthenticated(failure)) {
        dispatch({ type: 'BOTS_FAILED' });
      }
      return;
    }
    dispatch({ type: 'BOTS_LOADED', lists, activeLimit, at: now().toISOString() });
    const toRead = NEEDS_LAST_TRADE.flatMap((state) => lists[state])
      .map((bot) => bot.botId)
      .filter((botId) => !lastTradeRead.has(botId));
    void loadLastTrades(toRead);
  }

  async function readLastTrade(botId: string): Promise<BotTrade | null | 'failed'> {
    try {
      const page = await limiter.run(() => api.listBotTrades(botId, { limit: 1 }));
      const trade = page.items[0];
      return trade ? { botId, trade } : null;
    } catch (failure) {
      if (isUnauthenticated(failure)) {
        return 'failed';
      }
      if (failure instanceof ApiError && failure.status === 404) {
        // NFR5.2: a bot that is not (or no longer) of this person leaves the screen.
        dispatch({ type: 'BOT_REMOVED', botId });
        return null;
      }
      lastTradeRead.delete(botId);
      return 'failed';
    }
  }

  async function loadLastTrades(botIds: string[]): Promise<void> {
    if (botIds.length === 0 && lastTradeLoadedOnce) {
      return;
    }
    botIds.forEach((botId) => lastTradeRead.add(botId));
    const results = await Promise.all(botIds.map((botId) => readLastTrade(botId)));
    lastTradeLoadedOnce = true;
    const trades = results.filter(
      (result): result is BotTrade => result !== null && result !== 'failed',
    );
    dispatch({
      type: 'LAST_TRADES_LOADED',
      trades,
      failed: results.includes('failed'),
      at: now().toISOString(),
    });
  }

  async function loadReconciliation(): Promise<void> {
    dispatch({ type: 'RECONCILIATION_REQUESTED', showLoading: showReconciliationLoading });
    showReconciliationLoading = false;
    try {
      const result = await api.getReconciliation();
      dispatch({ type: 'RECONCILIATION_LOADED', result, at: now().toISOString() });
    } catch (failure) {
      if (!isUnauthenticated(failure)) {
        dispatch({ type: 'RECONCILIATION_FAILED' });
      }
    }
  }

  const bots = singleFlight(loadBots);
  const reconciliation = singleFlight(loadReconciliation);

  return {
    /** The full load of the bots card; `showLoading` only for the opening and "Tentar de novo" of the card. */
    reloadBots(showLoading = false) {
      showBotsLoading = showBotsLoading || showLoading;
      return bots.trigger();
    },
    refreshReconciliation(showLoading = false) {
      showReconciliationLoading = showReconciliationLoading || showLoading;
      return reconciliation.trigger();
    },
    /** BR2.3: the notices go in at once; the full order of each named bot completes the line. */
    tradeRecorded(items: TradeNotice[]) {
      dispatch({ type: 'TRADE_RECORDED_EVENT', items });
      const botIds = [...new Set(items.map((item) => item.botId))];
      return Promise.all(
        botIds.map(async (botId) => {
          lastTradeRead.add(botId);
          try {
            const page = await limiter.run(() => api.listBotTrades(botId, { limit: 1 }));
            const trade = page.items[0];
            if (trade) {
              dispatch({ type: 'TRADE_DETAIL', botId, trade, at: now().toISOString() });
            }
          } catch (failure) {
            if (failure instanceof ApiError && failure.status === 404) {
              dispatch({ type: 'BOT_REMOVED', botId });
            } else if (!isUnauthenticated(failure)) {
              dashboardWarn('trade.recorded', 'NETWORK');
            }
          }
        }),
      );
    },
    /** F1 step 3: no `data.status` within 5 s, so the sources come from the scanner list of one row. */
    async loadSources() {
      try {
        const page = await api.listTokens({ cursor: null, limit: 1 });
        dispatch({ type: 'SOURCES_LOADED', items: page.sources, at: now().toISOString() });
      } catch (failure) {
        if (failure instanceof ApiError && failure.code === 'SOURCE_UNAVAILABLE') {
          dispatch({ type: 'SOURCES_LOADED', items: failure.sources, at: now().toISOString() });
        } else if (!isUnauthenticated(failure)) {
          dispatch({ type: 'SOURCES_FAILED' });
        }
      }
    },
  };
}
