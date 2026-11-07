import { createApiClient } from '@/lib/api/client';
import { guestApi, type BotSummary, type PaperTrade } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import {
  createDashboardLoader,
  LAST_TRADE_CONCURRENCY,
} from '@/features/dashboard/dashboard-loader';
import {
  ALL_STATES,
  dashboardReducer,
  initialDashboardState,
  sourceNotices,
  type BotLists,
  type DashboardAction,
  type DashboardState,
} from '@/features/dashboard/dashboard-reducer';
import { msUntilNextUtcMidnight, utcDayPeriod } from '@/features/dashboard/period';
import {
  BOT_A,
  BOT_B,
  BOT_C,
  botPage,
  botSummary,
  paperTrade,
  pnlReport,
  reconciliation,
  settle,
  sourceStatus,
  START,
  tradeNotice,
} from '@/features/dashboard/test-support';

/**
 * Passo 7 of U9 (BR1.2 to BR1.5, BR2.1, BR2.3, BR3.1 to BR3.4, BR4.1, BR8.1; NFR3.3, NFR3.4, NFR5.1): the state of
 * the dashboard and the order and concurrency of its calls. The calls go through the REAL typed client of the app
 * over a fake fetch that records every request, so the test sees exactly what leaves the browser.
 */
function lists(bots: BotSummary[]): BotLists {
  const result = {} as BotLists;
  for (const state of ALL_STATES) {
    result[state] = bots.filter((bot) => bot.state === state);
  }
  return result;
}

function reduce(actions: DashboardAction[], from: DashboardState = initialDashboardState(START)) {
  return actions.reduce(dashboardReducer, from);
}

const AT = START.toISOString();

type Handler = (
  url: URL,
  init: RequestInit,
) => { status: number; body?: unknown } | Promise<{ status: number; body?: unknown }>;

/** The REST side: a router over a recording fetch, answering JSON built from the generated types. */
function restApi(handler: Handler) {
  const requests: Array<{ url: string; init: RequestInit }> = [];
  const fetchImpl = jest.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    requests.push({ url: String(input), init });
    const answer = await handler(new URL(String(input)), init);
    return {
      status: answer.status,
      ok: answer.status >= 200 && answer.status < 300,
      headers: { get: () => null },
      json: async () => answer.body,
    } as unknown as Response;
  });
  const api = guestApi(
    createApiClient({
      baseUrl: 'http://api.test',
      fetchImpl: fetchImpl as unknown as typeof fetch,
      maxRetries: 0,
    }),
  );
  return { api, requests };
}

function loaderOver(handler: Handler) {
  const { api, requests } = restApi(handler);
  const actions: DashboardAction[] = [];
  const onUnauthenticated = jest.fn();
  const loader = createDashboardLoader({
    api,
    dispatch: (action) => actions.push(action),
    now: () => START,
    onUnauthenticated,
  });
  return { loader, actions, requests, onUnauthenticated };
}

function botsBy(state: string | null, all: BotSummary[]) {
  return all.filter((bot) => bot.state === state);
}

beforeEach(() => tokenStore.set('token-one'));
afterEach(() => tokenStore.clear());

describe('dashboard reducer', () => {
  it('is empty with no bot in use, with and without a draft to continue', () => {
    const withoutDrafts = reduce([
      {
        type: 'BOTS_LOADED',
        lists: lists([botSummary({ state: 'STOPPED' })]),
        activeLimit: 3,
        at: AT,
      },
    ]);
    const withDrafts = reduce([
      {
        type: 'BOTS_LOADED',
        lists: lists([
          botSummary({ botId: BOT_B, state: 'VALIDATED' }),
          botSummary({ botId: BOT_C, state: 'DRAFT', name: 'Mais recente' }),
          botSummary({ botId: BOT_A, state: 'DRAFT', name: 'Antigo' }),
        ]),
        activeLimit: 3,
        at: AT,
      },
    ]);

    expect(withoutDrafts.bots).toMatchObject({
      status: 'EMPTY',
      draftCount: 0,
      stoppedCount: 1,
      draftToContinueId: null,
    });
    // BR1.3: the first item of state=DRAFT in the order of the route (last change first).
    expect(withDrafts.bots).toMatchObject({
      status: 'EMPTY',
      draftCount: 3,
      draftToContinueId: BOT_C,
    });
  });

  it('continues a VALIDATED bot only when there is no DRAFT', () => {
    const state = reduce([
      {
        type: 'BOTS_LOADED',
        lists: lists([botSummary({ botId: BOT_B, state: 'VALIDATED' })]),
        activeLimit: 3,
        at: AT,
      },
    ]);

    expect(state.bots.draftToContinueId).toBe(BOT_B);
  });

  it('takes the counts only from the full load and orders active, paused and closing by name', () => {
    const state = reduce([
      {
        type: 'BOTS_LOADED',
        lists: lists([
          botSummary({ botId: BOT_A, name: 'Zeta', state: 'ACTIVE' }),
          botSummary({
            botId: BOT_B,
            name: 'Beta',
            state: 'PAUSED',
            pauseReasons: ['DAILY_LOSS', 'SYSTEM'],
          }),
          botSummary({ botId: BOT_C, name: 'Alfa', state: 'ACTIVE' }),
          botSummary({ botId: 'closing', name: 'Aaa', state: 'CLOSING_PENDING' }),
        ]),
        activeLimit: 3,
        at: AT,
      },
    ]);

    expect(state.bots.inUse.map((bot) => bot.name)).toEqual(['Alfa', 'Zeta', 'Beta', 'Aaa']);
    expect(state.bots).toMatchObject({
      status: 'READY',
      activeCount: 2,
      pausedCount: 1,
      activeLimit: 3,
    });
    expect(state.channel.lastSuccessAt).toBe(AT);
  });

  it('a truncated bot.state updates the bots shown and never the counts', () => {
    const loaded = reduce([
      {
        type: 'BOTS_LOADED',
        lists: lists([botSummary({ botId: BOT_A, state: 'ACTIVE' })]),
        activeLimit: 3,
        at: AT,
      },
    ]);

    const next = reduce(
      [
        {
          type: 'BOT_STATE_EVENT',
          data: {
            items: [
              botSummary({
                botId: BOT_A,
                state: 'PAUSED',
                pauseReasons: ['SYSTEM'],
                updatedAt: '2026-10-28T11:59:00Z',
              }),
              botSummary({ botId: 'not-shown', state: 'ACTIVE' }),
            ],
            activeCount: 7,
          },
        },
      ],
      loaded,
    );

    expect(next.bots.inUse).toHaveLength(1);
    expect(next.bots.inUse[0]?.state).toBe('PAUSED');
    expect(next.bots.activeCount).toBe(1);
    expect(next.bots.pausedCount).toBe(0);
  });

  it('a bot.state that arrives before the REST answer waits and wins when it is newer', () => {
    const state = reduce([
      {
        type: 'BOT_STATE_EVENT',
        data: {
          items: [
            botSummary({
              state: 'PAUSED',
              pauseReasons: ['PERSON'],
              updatedAt: '2026-10-28T11:58:00Z',
            }),
          ],
          activeCount: 0,
        },
      },
      {
        type: 'BOTS_LOADED',
        lists: lists([botSummary({ state: 'ACTIVE', updatedAt: '2026-10-28T11:00:00Z' })]),
        activeLimit: 3,
        at: AT,
      },
    ]);

    expect(state.bots.inUse[0]?.state).toBe('PAUSED');
    expect(state.bots.queued).toEqual([]);
  });

  it('keeps the order with the greatest createdAt and never takes an older trade.recorded', () => {
    const newest = paperTrade({ orderId: 'new', createdAt: '2026-10-28T11:50:00Z' });
    const state = reduce([
      {
        type: 'LAST_TRADES_LOADED',
        trades: [
          {
            botId: BOT_B,
            trade: paperTrade({ orderId: 'old', createdAt: '2026-10-28T10:00:00Z' }),
          },
          { botId: BOT_A, trade: newest },
        ],
        failed: false,
        at: AT,
      },
      {
        type: 'TRADE_RECORDED_EVENT',
        items: [tradeNotice({ orderId: 'older', createdAt: '2026-10-28T11:00:00Z' })],
      },
    ]);

    expect(state.lastTrade).toEqual({
      status: 'READY',
      trade: { source: 'full', botId: BOT_A, trade: newest },
    });
  });

  it('a trade.recorded becomes the last order and the full order completes it with the reason', () => {
    const notice = tradeNotice({
      orderId: 'n1',
      createdAt: '2026-10-28T11:55:00Z',
      status: 'NOT_FILLED',
    });
    const full = paperTrade({
      orderId: 'n1',
      createdAt: '2026-10-28T11:55:00Z',
      status: 'NOT_FILLED',
      notFilledReason: 'NO_LIQUIDITY',
    });

    const afterNotice = reduce([
      { type: 'LAST_TRADES_LOADED', trades: [], failed: false, at: AT },
      { type: 'TRADE_RECORDED_EVENT', items: [notice] },
    ]);
    const completed = reduce(
      [{ type: 'TRADE_DETAIL', botId: BOT_A, trade: full, at: AT }],
      afterNotice,
    );
    const notBack = reduce([{ type: 'TRADE_RECORDED_EVENT', items: [notice] }], completed);

    expect(afterNotice.lastTrade.trade).toEqual({ source: 'notice', botId: BOT_A, trade: notice });
    expect(completed.lastTrade.trade).toEqual({ source: 'full', botId: BOT_A, trade: full });
    expect(notBack.lastTrade.trade).toEqual({ source: 'full', botId: BOT_A, trade: full });
  });

  it('a failed new read keeps the values already shown', () => {
    const report = pnlReport();
    const state = reduce([
      { type: 'PNL_LOADED', report, at: AT },
      { type: 'PNL_FAILED' },
      { type: 'RECONCILIATION_LOADED', result: reconciliation(), at: AT },
      { type: 'RECONCILIATION_FAILED' },
      { type: 'SOURCES_LOADED', items: [sourceStatus()], at: AT },
      { type: 'SOURCES_FAILED' },
    ]);
    const neverLoaded = reduce([
      { type: 'PNL_FAILED' },
      { type: 'RECONCILIATION_FAILED' },
      { type: 'SOURCES_FAILED' },
      { type: 'BOTS_FAILED' },
    ]);

    expect(state.pnl).toMatchObject({ status: 'READY', report });
    expect(state.reconciliation.status).toBe('READY');
    expect(state.sources.status).toBe('READY');
    expect(neverLoaded.pnl.status).toBe('ERROR');
    expect(neverLoaded.reconciliation.status).toBe('ERROR');
    expect(neverLoaded.sources.status).toBe('ERROR');
    expect(neverLoaded.bots.status).toBe('ERROR');
  });

  it('a P&L without operations is EMPTY', () => {
    expect(
      reduce([{ type: 'PNL_LOADED', report: pnlReport({ empty: true }), at: AT }]).pnl.status,
    ).toBe('EMPTY');
  });
});

describe('sources and the UTC day', () => {
  it('marks a source late by status and by age above 5 minutes on the app clock', () => {
    const now = '2026-10-28T12:00:00Z';
    const notices = sourceNotices(
      [
        sourceStatus({
          source: { sourceId: 'a', name: 'A', simulated: true },
          lastUpdatedAt: '2026-10-28T11:56:00Z',
        }),
        sourceStatus({
          source: { sourceId: 'b', name: 'B', simulated: true },
          lastUpdatedAt: '2026-10-28T11:54:00Z',
        }),
        sourceStatus({ source: { sourceId: 'c', name: 'C', simulated: true }, status: 'DEGRADED' }),
        sourceStatus({ source: { sourceId: 'd', name: 'D', simulated: true }, status: 'FAILED' }),
        sourceStatus({
          source: { sourceId: 'e', name: 'E', simulated: true },
          status: 'PENDING',
          lastUpdatedAt: undefined,
        }),
      ],
      now,
    );

    expect(notices).toEqual([
      { kind: 'stale', name: 'B', lastUpdatedAt: '2026-10-28T11:54:00Z' },
      { kind: 'stale', name: 'C', lastUpdatedAt: '2026-10-28T11:59:30Z' },
      { kind: 'failed', name: 'D', lastUpdatedAt: '2026-10-28T11:59:30Z' },
      { kind: 'never', name: 'E' },
    ]);
  });

  it('the period is 00:00 UTC of the current day to now, and the next midnight is counted on the UTC clock', () => {
    const late = new Date('2026-10-28T23:59:00Z');

    expect(utcDayPeriod(late)).toEqual({
      from: '2026-10-28T00:00:00.000Z',
      to: '2026-10-28T23:59:00.000Z',
    });
    expect(msUntilNextUtcMidnight(late)).toBe(60_000);
    expect(msUntilNextUtcMidnight(new Date('2026-10-29T00:00:00Z'))).toBe(86_400_000);
    expect(utcDayPeriod(new Date('2026-10-29T00:00:01Z')).from).toBe('2026-10-29T00:00:00.000Z');
  });
});

describe('dashboard loader', () => {
  it('loads every state through every page of nextCursor with limit=100', async () => {
    const stopped = Array.from({ length: 120 }, (_, i) =>
      botSummary({ botId: `s${i}`, state: 'STOPPED' }),
    );
    const { loader, actions, requests } = loaderOver((url) => {
      if (url.pathname === '/api/v1/bots') {
        const state = url.searchParams.get('state');
        const all =
          state === 'STOPPED'
            ? stopped
            : botsBy(state, [botSummary(), botSummary({ botId: BOT_B })]);
        const start = url.searchParams.get('cursor') === 'p2' ? 100 : 0;
        return {
          status: 200,
          body: botPage(all.slice(start, start + 100), {
            nextCursor: start === 0 && all.length > 100 ? 'p2' : null,
          }),
        };
      }
      return { status: 200, body: { items: [], nextCursor: null, total: 0 } };
    });

    await loader.reloadBots(true);
    await settle(50);

    const botCalls = requests.filter((request) => request.url.includes('/api/v1/bots?'));
    expect(botCalls).toHaveLength(7);
    expect(botCalls.every((request) => request.url.includes('limit=100'))).toBe(true);
    const loaded = actions.find((action) => action.type === 'BOTS_LOADED');
    expect(loaded && loaded.type === 'BOTS_LOADED' && loaded.lists.STOPPED).toHaveLength(120);
    expect(actions[0]).toEqual({ type: 'BOTS_LOADING' });
  });

  it('reads the last order of at most 3 bots at the same time and keeps the newest', async () => {
    let running = 0;
    let peak = 0;
    const bots = Array.from({ length: 8 }, (_, i) =>
      botSummary({ botId: `bot-${i}`, state: 'STOPPED' }),
    );
    const releases: Array<() => void> = [];
    const { loader, actions } = loaderOver(async (url) => {
      if (url.pathname === '/api/v1/bots') {
        return { status: 200, body: botPage(botsBy(url.searchParams.get('state'), bots)) };
      }
      running += 1;
      peak = Math.max(peak, running);
      await new Promise<void>((resolve) => releases.push(resolve));
      running -= 1;
      const index = url.pathname.split('/')[4]?.split('-')[1] ?? '0';
      return {
        status: 200,
        body: {
          items: [paperTrade({ orderId: `o${index}`, createdAt: `2026-10-28T10:0${index}:00Z` })],
          nextCursor: null,
          total: 1,
        },
      };
    });

    void loader.reloadBots(true);
    for (
      let i = 0;
      i < 40 && actions.every((action) => action.type !== 'LAST_TRADES_LOADED');
      i++
    ) {
      await settle(20);
      releases.splice(0).forEach((release) => release());
    }

    expect(peak).toBe(LAST_TRADE_CONCURRENCY);
    const loaded = actions.find((action) => action.type === 'LAST_TRADES_LOADED');
    expect(loaded && loaded.type === 'LAST_TRADES_LOADED' && loaded.trades).toHaveLength(8);
    const state = reduce(actions);
    expect(state.lastTrade.trade?.trade.orderId).toBe('o7');
  });

  it('never reads again the last order of a bot already read in a later full load', async () => {
    const { loader, requests } = loaderOver((url) => {
      if (url.pathname === '/api/v1/bots') {
        return {
          status: 200,
          body: botPage(
            botsBy(url.searchParams.get('state'), [
              botSummary({ state: 'STOPPED' }),
              botSummary({ botId: BOT_B, state: 'DRAFT' }),
            ]),
          ),
        };
      }
      return { status: 200, body: { items: [paperTrade()], nextCursor: null, total: 1 } };
    });

    await loader.reloadBots(true);
    await settle(30);
    await loader.reloadBots();
    await settle(30);

    const trades = requests.filter((request) => request.url.includes('/trades'));
    expect(trades.map((request) => request.url)).toEqual([
      `http://api.test/api/v1/bots/${BOT_A}/trades?limit=1`,
    ]);
  });

  it('ten pnl.updated in a row make at most two reads of the P&L and of the reconciliation', async () => {
    const answers: Array<() => void> = [];
    const { loader, requests } = loaderOver(
      (url) =>
        new Promise((resolve) =>
          answers.push(() =>
            resolve({
              status: 200,
              body: url.pathname === '/api/v1/pnl' ? pnlReport() : reconciliation(),
            }),
          ),
        ),
    );

    for (let i = 0; i < 10; i++) {
      void loader.refreshPnl();
      void loader.refreshReconciliation();
    }
    for (let i = 0; i < 5; i++) {
      await settle(20);
      answers.splice(0).forEach((answer) => answer());
    }
    await settle(20);

    expect(requests.filter((request) => request.url.includes('/api/v1/pnl'))).toHaveLength(2);
    expect(
      requests.filter((request) => request.url.includes('/api/v1/reconciliation')),
    ).toHaveLength(2);
  });

  it('asks the P&L of the UTC day to now', async () => {
    const { loader, requests } = loaderOver(() => ({ status: 200, body: pnlReport() }));

    await loader.refreshPnl(true);

    expect(requests[0]?.url).toBe(
      'http://api.test/api/v1/pnl?from=2026-10-28T00%3A00%3A00.000Z&to=2026-10-28T12%3A00%3A00.000Z',
    );
  });

  it('a 404 on the last order removes the bot, a 401 starts the session-expired flow', async () => {
    const { loader, actions, onUnauthenticated } = loaderOver((url) => {
      if (url.pathname === '/api/v1/bots') {
        return {
          status: 200,
          body: botPage(botsBy(url.searchParams.get('state'), [botSummary()])),
        };
      }
      if (url.pathname.endsWith('/trades')) {
        return { status: 404, body: { code: 'NOT_FOUND' } };
      }
      return { status: 401, body: { code: 'UNAUTHENTICATED' } };
    });

    await loader.reloadBots(true);
    await settle(30);
    await loader.refreshPnl(true);

    expect(actions).toContainEqual({ type: 'BOT_REMOVED', botId: BOT_A });
    expect(onUnauthenticated).toHaveBeenCalled();
    expect(actions.some((action) => action.type === 'PNL_FAILED')).toBe(false);
  });

  it('the full order named by trade.recorded is read and completes the line', async () => {
    const full: PaperTrade = paperTrade({
      orderId: 'n1',
      status: 'NOT_FILLED',
      notFilledReason: 'NO_LIQUIDITY',
    });
    const { loader, actions, requests } = loaderOver(() => ({
      status: 200,
      body: { items: [full], nextCursor: null, total: 1 },
    }));

    await loader.tradeRecorded([
      tradeNotice({ orderId: 'n1', botId: BOT_C }),
      tradeNotice({ orderId: 'n2', botId: BOT_C }),
    ]);

    expect(requests.map((request) => request.url)).toEqual([
      `http://api.test/api/v1/bots/${BOT_C}/trades?limit=1`,
    ]);
    expect(actions.map((action) => action.type)).toEqual(['TRADE_RECORDED_EVENT', 'TRADE_DETAIL']);
  });

  it('reads the sources from the scanner list of one row, also when every source failed', async () => {
    const failedSources = [sourceStatus({ status: 'FAILED' })];
    let call = 0;
    const { loader, actions, requests } = loaderOver(() => {
      call += 1;
      return call === 1
        ? {
            status: 200,
            body: {
              items: [],
              nextCursor: null,
              total: 0,
              sources: [sourceStatus()],
              excludedByUnknown: 0,
              pendingNewTokens: 0,
            },
          }
        : call === 2
          ? { status: 503, body: { code: 'SOURCE_UNAVAILABLE', sources: failedSources } }
          : { status: 500, body: {} };
    });

    await loader.loadSources();
    await loader.loadSources();
    await loader.loadSources();

    expect(requests[0]?.url).toBe('http://api.test/api/v1/scanner/tokens?limit=1');
    expect(actions.map((action) => action.type)).toEqual([
      'SOURCES_LOADED',
      'SOURCES_LOADED',
      'SOURCES_FAILED',
    ]);
  });

  it('no request of the dashboard carries a tenant or person id', async () => {
    const { loader, requests } = loaderOver((url) => {
      if (url.pathname === '/api/v1/bots') {
        return {
          status: 200,
          body: botPage(
            botsBy(url.searchParams.get('state'), [
              botSummary(),
              botSummary({ botId: BOT_B, state: 'STOPPED' }),
            ]),
          ),
        };
      }
      if (url.pathname.endsWith('/trades')) {
        return { status: 200, body: { items: [paperTrade()], nextCursor: null, total: 1 } };
      }
      return { status: 200, body: url.pathname === '/api/v1/pnl' ? pnlReport() : reconciliation() };
    });

    await loader.reloadBots(true);
    await loader.refreshPnl(true);
    await loader.refreshReconciliation(true);
    await loader.tradeRecorded([tradeNotice()]);
    await settle(30);

    expect(requests.length).toBeGreaterThan(8);
    for (const request of requests) {
      const sent = `${request.url} ${String(request.init.body ?? '')}`;
      expect(sent).not.toMatch(/tenantId|userId|tenant_id|user_id/);
      expect((request.init.headers as Record<string, string>).Authorization).toBe(
        'Bearer token-one',
      );
    }
  });
});
