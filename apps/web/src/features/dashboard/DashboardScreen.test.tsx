import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import type { BotSummary, GuestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { messages } from '@/lib/i18n/messages';
import { findProhibited, prohibitedTerms } from '@/content/prohibited-terms';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { resetWalletCache } from '@/components/shell/WalletBadge';
import { DashboardScreen } from '@/features/dashboard/DashboardScreen';
import {
  BOT_A,
  BOT_B,
  BOT_C,
  botPage,
  botSummary,
  fakeChannel,
  fakeFetch,
  paperTrade,
  pnlReport,
  reconciliation,
  sourceStatus,
  sseBlock,
  tradeNotice,
  ZONE,
} from '@/features/dashboard/test-support';
import HomePage from '@/app/page';

/**
 * Passo 8 of U9 (US5.3, US5.4; BR1.1 to BR3.5, BR6.5, BR6.6, BR7.1 to BR7.3, BR8.1, BR8.2; NFR3.2, NFR4.1, NFR4.2,
 * NFR5.2): the dashboard S2 rendered with the API double typed by the generated contract and a fake live channel.
 */
const replace = jest.fn();

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: replace }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => '/',
}));

const text = messages.dashboard;
const NOW = new Date('2026-10-28T12:00:00Z');

function botsApi(bots: BotSummary[], overrides: Partial<GuestApi> = {}) {
  return fakeApi({
    listBots: jest.fn(async (request: { state?: BotSummary['state'] } = {}) =>
      botPage(bots.filter((bot) => bot.state === request.state)),
    ),
    getPnl: jest.fn(async () => pnlReport()),
    getReconciliation: jest.fn(async () => reconciliation()),
    ...overrides,
  });
}

function renderDashboard(
  api: jest.Mocked<GuestApi> = botsApi([]),
  options: { net?: ReturnType<typeof fakeFetch>; now?: () => Date } = {},
) {
  const net = options.net ?? fakeFetch();
  tokenStore.set('access-token-value');
  render(
    <Providers api={api}>
      <DashboardScreen now={options.now ?? (() => NOW)} timeZone={ZONE} fetchImpl={net.impl} />
    </Providers>,
  );
  return { api, net };
}

const ACTIVE = botSummary({ botId: BOT_A, name: 'Alfa', state: 'ACTIVE' });
const PAUSED = botSummary({
  botId: BOT_B,
  name: 'Beta',
  state: 'PAUSED',
  pauseReasons: ['DAILY_LOSS', 'SYSTEM'],
  resumesAt: '2026-10-29T00:00:00Z',
});
const PAUSED_BY_PERSON = { ...ACTIVE, state: 'PAUSED' as const, pauseReasons: ['PERSON' as const] };

beforeEach(() => {
  replace.mockReset();
  resetWalletCache();
});

afterEach(() => {
  jest.useRealTimers();
  tokenStore.clear();
  jest.restoreAllMocks();
});

describe('S2 dashboard: loading, errors and empty state', () => {
  it('the route / is the dashboard', () => {
    tokenStore.set('access-token-value');
    render(
      <Providers api={botsApi([])}>
        <HomePage />
      </Providers>,
    );

    expect(screen.getByRole('heading', { level: 1, name: text.title })).toBeInTheDocument();
  });

  it('shows the loading of the three cards at the first render, before any answer', () => {
    const never = () => new Promise<never>(() => undefined);
    renderDashboard(
      fakeApi({
        listBots: jest.fn(never),
        getPnl: jest.fn(never),
        getReconciliation: jest.fn(never),
      }),
    );

    expect(screen.getByTestId('dashboard-bots-loading')).toHaveTextContent(text.bots.loading);
    expect(screen.getByTestId('dashboard-pnl-loading')).toHaveTextContent(text.pnl.loading);
    expect(screen.getByTestId('dashboard-sources-loading')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-bots-card')).toHaveAttribute('aria-busy', 'true');
  });

  it('a failing card shows its own error and the other cards still appear', async () => {
    renderDashboard(
      botsApi([ACTIVE], {
        getPnl: jest.fn(async () => {
          throw new ApiError(503, 'SERVICE_UNAVAILABLE');
        }),
      }),
    );

    expect(await screen.findByTestId('dashboard-card-error-pnl')).toHaveTextContent(text.pnl.error);
    expect(await screen.findByTestId(`dashboard-bot-row-${BOT_A}`)).toBeInTheDocument();
    expect(screen.queryByTestId('dashboard-card-error-bots')).toBeNull();
  });

  it('"Tentar de novo" of a card repeats only the calls of that card', async () => {
    const getPnl = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
      .mockResolvedValue(pnlReport());
    const { api } = renderDashboard(botsApi([ACTIVE], { getPnl }));
    await screen.findByTestId('dashboard-card-error-pnl');
    const botCalls = api.listBots.mock.calls.length;

    await userEvent.click(screen.getByTestId('dashboard-card-retry-pnl'));

    expect(await screen.findByTestId('dashboard-pnl-realized')).toBeInTheDocument();
    expect(getPnl).toHaveBeenCalledTimes(2);
    expect(api.listBots).toHaveBeenCalledTimes(botCalls);
  });

  it('the bots card fails alone and its retry reads the bots again', async () => {
    const listBots = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
      .mockImplementation(async (request: { state?: string }) =>
        botPage(request.state === 'ACTIVE' ? [ACTIVE] : []),
      );
    renderDashboard(botsApi([], { listBots }));

    expect(await screen.findByTestId('dashboard-card-error-bots')).toHaveTextContent(
      text.bots.error,
    );
    expect(await screen.findByTestId('dashboard-pnl-realized')).toBeInTheDocument();
    await userEvent.click(screen.getByTestId('dashboard-card-retry-bots'));

    expect(await screen.findByTestId(`dashboard-bot-row-${BOT_A}`)).toBeInTheDocument();
  });

  it('with no bot in use shows the empty state with its shortcuts', async () => {
    renderDashboard(botsApi([botSummary({ botId: BOT_C, state: 'STOPPED' })]));

    const empty = await screen.findByTestId('dashboard-bots-empty');
    expect(empty).toHaveTextContent(text.bots.empty);
    expect(within(empty).getByTestId('dashboard-open-scanner-link')).toHaveAttribute(
      'href',
      '/scanner',
    );
    expect(within(empty).getByTestId('dashboard-create-bot-link')).toHaveAttribute(
      'href',
      '/bots/new',
    );
    expect(within(empty).getByTestId('dashboard-bots-stopped')).toHaveTextContent('1 encerrado');
    expect(screen.queryByTestId('dashboard-continue-draft-link')).toBeNull();
  });

  it('with drafts offers "Continuar rascunho" on the first draft of the route order', async () => {
    renderDashboard(
      botsApi([
        botSummary({ botId: BOT_B, state: 'DRAFT', name: 'Mais recente' }),
        botSummary({ botId: BOT_C, state: 'DRAFT', name: 'Antigo' }),
      ]),
    );

    expect(await screen.findByTestId('dashboard-bots-drafts')).toHaveTextContent(
      'Você tem 2 rascunhos',
    );
    expect(screen.getByTestId('dashboard-continue-draft-link')).toHaveAttribute(
      'href',
      `/bots/new/${BOT_B}`,
    );
  });
});

describe('S2 dashboard: bots in use and "Pausar"', () => {
  it('shows the counts, one row per bot in use with state and reasons in text, and the restart time', async () => {
    renderDashboard(botsApi([ACTIVE, PAUSED, botSummary({ botId: BOT_C, state: 'STOPPED' })]));

    expect(await screen.findByTestId('dashboard-bots-counts')).toHaveTextContent(
      'Ativos: 1 de 3 · Pausados: 1',
    );
    const paused = screen.getByTestId(`dashboard-bot-row-${BOT_B}`);
    expect(paused).toHaveTextContent('Estado: Pausado');
    expect(paused).toHaveTextContent('perda diária atingida; pausado pelo sistema');
    expect(paused).toHaveTextContent('volta a operar às 21:00, horário de Brasília');
    expect(within(paused).getByRole('link', { name: 'Beta' })).toHaveAttribute(
      'href',
      `/bots/${BOT_B}`,
    );
    expect(screen.getByTestId('dashboard-bots-others')).toHaveTextContent('1 encerrado');
  });

  it('"Pausar" exists only on the row of an ACTIVE bot', async () => {
    renderDashboard(botsApi([ACTIVE, PAUSED]));

    expect(await screen.findByTestId(`dashboard-bot-pause-button-${BOT_A}`)).toHaveTextContent(
      text.bots.pause,
    );
    expect(screen.queryByTestId(`dashboard-bot-pause-button-${BOT_B}`)).toBeNull();
  });

  it('a successful pause shows the confirmed state, moves the focus to it and announces it', async () => {
    const runBotCommand = jest.fn(async () => PAUSED_BY_PERSON);
    renderDashboard(botsApi([ACTIVE], { runBotCommand }));

    await userEvent.click(await screen.findByTestId(`dashboard-bot-pause-button-${BOT_A}`));

    const state = await screen.findByText('Estado: Pausado');
    expect(state).toHaveFocus();
    expect(screen.getByTestId(`dashboard-bot-row-${BOT_A}`)).toHaveTextContent('pausado por você');
    expect(screen.getByTestId('dashboard-bots-counts')).toHaveTextContent(
      'Ativos: 0 de 3 · Pausados: 1',
    );
    expect(runBotCommand).toHaveBeenCalledWith(
      BOT_A,
      'pause',
      expect.stringMatching(/^[0-9a-f-]{36}$/),
    );
    await waitFor(
      () =>
        expect(screen.getByTestId('app-notices-region')).toHaveTextContent(
          'Bot Alfa pausado por você',
        ),
      { timeout: 3_000 },
    );
  });

  it('a technical failure offers "Tentar de novo" with the SAME Idempotency-Key', async () => {
    const runBotCommand = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK'))
      .mockResolvedValue(PAUSED_BY_PERSON);
    renderDashboard(botsApi([ACTIVE], { runBotCommand }));

    await userEvent.click(await screen.findByTestId(`dashboard-bot-pause-button-${BOT_A}`));
    expect(await screen.findByTestId(`dashboard-bot-pause-failed-${BOT_A}`)).toHaveTextContent(
      text.bots.pauseFailed,
    );
    await userEvent.click(screen.getByTestId(`dashboard-bot-pause-retry-${BOT_A}`));

    await screen.findByText('Estado: Pausado');
    const [first, second] = runBotCommand.mock.calls;
    expect(second?.[2]).toBe(first?.[2]);
  });

  it('a new click after a success uses a new key, and a repeated click sends one command', async () => {
    let answer: (bot: BotSummary) => void = () => undefined;
    const runBotCommand = jest.fn(() => new Promise<BotSummary>((resolve) => (answer = resolve)));
    renderDashboard(botsApi([ACTIVE], { runBotCommand }));
    const button = await screen.findByTestId(`dashboard-bot-pause-button-${BOT_A}`);

    await userEvent.click(button);
    expect(button).toBeDisabled();
    expect(button).toHaveTextContent(text.bots.pausing);
    await userEvent.click(button);

    expect(runBotCommand).toHaveBeenCalledTimes(1);
    await act(async () => answer(PAUSED_BY_PERSON));
  });

  it('409 says the bot was no longer active and reads the list again', async () => {
    const runBotCommand = jest.fn(async () => {
      throw new ApiError(409, 'INVALID_TRANSITION');
    });
    const { api } = renderDashboard(botsApi([ACTIVE], { runBotCommand }));
    const button = await screen.findByTestId(`dashboard-bot-pause-button-${BOT_A}`);
    const before = api.listBots.mock.calls.length;
    await userEvent.click(button);

    expect(await screen.findByTestId(`dashboard-bot-pause-conflict-${BOT_A}`)).toHaveTextContent(
      text.bots.pauseConflict,
    );
    await waitFor(() => expect(api.listBots.mock.calls.length).toBeGreaterThan(before));
  });

  it('404 takes the bot off the dashboard and reads the list again', async () => {
    const listBots = jest
      .fn()
      .mockImplementation(async (request: { state?: string }) =>
        botPage(request.state === 'ACTIVE' ? [ACTIVE] : []),
      );
    const runBotCommand = jest.fn(async () => {
      listBots.mockImplementation(async () => botPage([]));
      throw new ApiError(404, 'NOT_FOUND');
    });
    renderDashboard(botsApi([], { listBots, runBotCommand }));

    await userEvent.click(await screen.findByTestId(`dashboard-bot-pause-button-${BOT_A}`));

    await waitFor(() => expect(screen.queryByTestId(`dashboard-bot-row-${BOT_A}`)).toBeNull());
    expect(await screen.findByTestId('dashboard-bots-empty')).toBeInTheDocument();
  });

  it('the last order is the newest among every bot, with status and reason in text', async () => {
    const listBotTrades = jest.fn(async (botId: string) => ({
      items: [
        botId === BOT_B
          ? paperTrade({
              orderId: 'b',
              createdAt: '2026-10-28T11:40:00Z',
              side: 'SELL',
              status: 'NOT_FILLED',
              notFilledReason: 'NO_LIQUIDITY',
            })
          : paperTrade({ orderId: 'a', createdAt: '2026-10-28T10:00:00Z' }),
      ],
      nextCursor: null,
      total: 1,
    }));
    renderDashboard(botsApi([ACTIVE, PAUSED], { listBotTrades }));

    await waitFor(() =>
      expect(screen.getByTestId('dashboard-last-trade')).toHaveTextContent(
        'Última operação: 08:40 — Beta — Venda Test…1111 — não preenchida: sem liquidez',
      ),
    );
  });

  it('without any order says "Nenhuma operação ainda"', async () => {
    renderDashboard(botsApi([ACTIVE]));

    await waitFor(() =>
      expect(screen.getByTestId('dashboard-last-trade')).toHaveTextContent(text.lastTrade.none),
    );
  });
});

describe('S2 dashboard: P&L and reconciliation', () => {
  it('shows the UTC day in the person time zone and realized, unrealized and fees with written signs', async () => {
    renderDashboard();

    expect(await screen.findByTestId('dashboard-pnl-period')).toHaveTextContent(
      'Hoje (dia UTC, desde 21:00 no seu horário)',
    );
    expect(await screen.findByTestId('dashboard-pnl-realized')).toHaveTextContent('+12,500000');
    expect(screen.getByTestId('dashboard-pnl-unrealized')).toHaveTextContent('−3,250000');
    expect(screen.getByTestId('dashboard-pnl-fees')).toHaveTextContent('−0,750000 USDC (pagas)');
  });

  it('asks the P&L from 00:00 UTC to now', async () => {
    const { api } = renderDashboard();

    await waitFor(() =>
      expect(api.getPnl).toHaveBeenCalledWith({
        from: '2026-10-28T00:00:00.000Z',
        to: '2026-10-28T12:00:00.000Z',
      }),
    );
  });

  it('an unknown unrealized is "unknown" with its reason and never zero, with the partial notice', async () => {
    renderDashboard(
      botsApi([], {
        getPnl: jest.fn(async () =>
          pnlReport({ unrealized: { status: 'UNKNOWN', reason: 'PRICE_UNKNOWN' }, partial: true }),
        ),
      }),
    );

    const unrealized = await screen.findByTestId('dashboard-pnl-unrealized');
    expect(unrealized).toHaveTextContent('unknown (sem preço)');
    expect(unrealized).not.toHaveTextContent('0');
    expect(screen.getByTestId('dashboard-pnl-partial')).toHaveTextContent(text.pnl.partial);
    expect(screen.getByTestId('dashboard-pnl-realized')).toHaveTextContent('+12,500000');
  });

  it('a day without operations says "Nenhuma operação hoje" and no number', async () => {
    renderDashboard(botsApi([], { getPnl: jest.fn(async () => pnlReport({ empty: true })) }));

    expect(await screen.findByTestId('dashboard-pnl-empty')).toHaveTextContent(text.pnl.empty);
    expect(screen.queryByTestId('dashboard-pnl-realized')).toBeNull();
  });

  it('keeps every one of 18 decimal places, without floating point', async () => {
    renderDashboard(
      botsApi([], {
        getPnl: jest.fn(async () =>
          pnlReport({
            realized: '123456789.123456789012345678',
            unrealized: { status: 'KNOWN', value: '0.000000000000000001' },
            fees: '0.000000000000000000',
          }),
        ),
      }),
    );

    expect(await screen.findByTestId('dashboard-pnl-realized')).toHaveTextContent(
      '+123.456.789,123456789012345678',
    );
    expect(screen.getByTestId('dashboard-pnl-unrealized')).toHaveTextContent(
      '+0,000000000000000001',
    );
    expect(screen.getByTestId('dashboard-pnl-fees')).toHaveTextContent('0,000000000000000000 USDC');
  });

  it.each([
    [reconciliation(), 'Reconciliado às 08:50'],
    [reconciliation({ status: 'DIVERGENT', divergenceCount: 2 }), '2 divergências'],
    [reconciliation({ status: 'PENDING', checkedAt: undefined }), 'Ainda não conferido'],
  ])('summarizes the reconciliation %#', async (result, expected) => {
    renderDashboard(botsApi([], { getReconciliation: jest.fn(async () => result) }));

    await waitFor(() =>
      expect(screen.getByTestId('dashboard-reconciliation')).toHaveTextContent(expected),
    );
  });

  it('a failed reconciliation shows only on its line and the P&L values stay', async () => {
    const getReconciliation = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
      .mockResolvedValue(reconciliation());
    renderDashboard(botsApi([], { getReconciliation }));

    await waitFor(() =>
      expect(screen.getByTestId('dashboard-reconciliation')).toHaveTextContent(
        messages.dashboard.reconciliation.error,
      ),
    );
    expect(screen.getByTestId('dashboard-pnl-realized')).toBeInTheDocument();
    await userEvent.click(screen.getByTestId('dashboard-reconciliation-retry'));
    await waitFor(() =>
      expect(screen.getByTestId('dashboard-reconciliation')).toHaveTextContent('Reconciliado'),
    );
  });
});

describe('S2 dashboard: live updates, notices and accessibility', () => {
  it('the first focus goes to the title "Bots PAPER" and both card titles say MODO PAPER (simulado)', async () => {
    renderDashboard();

    const botsTitle = screen.getByRole('heading', { level: 2, name: /Bots PAPER/ });
    expect(botsTitle).toHaveFocus();
    expect(botsTitle).toHaveTextContent('MODO PAPER (simulado)');
    expect(screen.getByRole('heading', { level: 2, name: /P&L PAPER/ })).toHaveTextContent(
      'MODO PAPER (simulado)',
    );
    await screen.findByTestId('dashboard-pnl-realized');
  });

  it('no text of the dashboard promises profit', async () => {
    renderDashboard(botsApi([ACTIVE, PAUSED]));
    await screen.findByTestId('dashboard-pnl-realized');

    const shown = [{ path: 'dashboard', text: document.body.textContent ?? '' }];
    expect(findProhibited(shown, prohibitedTerms())).toEqual([]);
    expect(document.body).toHaveTextContent(/simulad/);
  });

  it('a live event updates the bots at once, "Atualizado às" and reads the full list again', async () => {
    const channel = fakeChannel();
    const net = fakeFetch();
    net.respond(channel.response);
    // The full load that the event starts stays pending, so the screen shows what the event brought.
    let calls = 0;
    const listBots = jest.fn((request: { state?: string } = {}) => {
      calls += 1;
      return calls <= 6
        ? Promise.resolve(botPage(request.state === 'ACTIVE' ? [ACTIVE] : []))
        : new Promise<never>(() => undefined);
    });
    const { api } = renderDashboard(botsApi([], { listBots }), { net });
    await screen.findByTestId(`dashboard-bot-row-${BOT_A}`);
    const before = api.listBots.mock.calls.length;

    await act(async () =>
      channel.push(
        sseBlock(
          'bot.state',
          { items: [{ ...PAUSED_BY_PERSON, updatedAt: '2026-10-28T11:59:00Z' }], activeCount: 0 },
          'b2.l0.s0',
        ),
      ),
    );

    expect(await screen.findByText('Estado: Pausado')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-updated-at')).toHaveTextContent('Atualizado às 09:00');
    await waitFor(() => expect(api.listBots.mock.calls.length).toBeGreaterThan(before));
    expect(net.requests[0]?.headers.Authorization).toBe('Bearer access-token-value');
  });

  it('trade.recorded replaces the last order and completes it with the full order', async () => {
    const channel = fakeChannel();
    const net = fakeFetch();
    net.respond(channel.response);
    const listBotTrades = jest.fn(async () => ({ items: [], nextCursor: null, total: 0 }));
    renderDashboard(botsApi([ACTIVE], { listBotTrades }), { net });
    await waitFor(() =>
      expect(screen.getByTestId('dashboard-last-trade')).toHaveTextContent(text.lastTrade.none),
    );
    listBotTrades.mockResolvedValue({
      items: [
        paperTrade({
          orderId: 'live',
          createdAt: '2026-10-28T11:58:00Z',
          side: 'SELL',
          status: 'NOT_FILLED',
          notFilledReason: 'PRICE_UNAVAILABLE',
        }),
      ],
      nextCursor: null,
      total: 1,
    } as never);

    await act(async () =>
      channel.push(
        sseBlock('trade.recorded', {
          items: [tradeNotice({ orderId: 'live', createdAt: '2026-10-28T11:58:00Z' })],
        }),
      ),
    );

    await waitFor(() =>
      expect(screen.getByTestId('dashboard-last-trade')).toHaveTextContent(
        'não preenchida: sem preço confiável',
      ),
    );
    expect(screen.getByTestId('dashboard-last-trade')).toHaveTextContent('Alfa — Venda');
  });

  it('a late or failed source is named with its last update; all up to date says "Dados: OK"', async () => {
    const channel = fakeChannel();
    const net = fakeFetch();
    net.respond(channel.response);
    renderDashboard(botsApi([]), { net });

    await act(async () =>
      channel.push(
        sseBlock('data.status', {
          items: [
            sourceStatus({
              source: { sourceId: 'b', name: 'B', simulated: true },
              lastUpdatedAt: '2026-10-28T11:54:00Z',
            }),
            sourceStatus({
              source: { sourceId: 'c', name: 'C', simulated: true },
              status: 'FAILED',
              lastUpdatedAt: '2026-10-28T11:00:00Z',
            }),
          ],
        }),
      ),
    );
    const notices = await screen.findByTestId('dashboard-source-notices');
    expect(notices).toHaveTextContent('Fonte B sem atualização desde 08:54');
    expect(notices).toHaveTextContent('Fonte C com falha; última atualização às 08:00');

    await act(async () => channel.push(sseBlock('data.status', { items: [sourceStatus()] })));
    expect(await screen.findByTestId('dashboard-sources-ok')).toHaveTextContent('Dados: OK 08:59');
  });

  it('without data.status in 5 s, the sources come from the scanner list', async () => {
    jest.useFakeTimers({ now: NOW });
    const { api } = renderDashboard(
      botsApi([], {
        listTokens: jest.fn(async () => ({
          items: [],
          nextCursor: null,
          total: 0,
          sources: [sourceStatus({ status: 'DEGRADED' })],
          excludedByUnknown: 0,
          pendingNewTokens: 0,
          dataSource: { kind: 'SIMULATED' as const },
        })),
      }),
    );

    await act(async () => {
      await jest.advanceTimersByTimeAsync(4_999);
    });
    expect(api.listTokens).not.toHaveBeenCalled();
    await act(async () => {
      await jest.advanceTimersByTimeAsync(1);
    });

    expect(api.listTokens).toHaveBeenCalledWith({ cursor: null, limit: 1 });
    expect(screen.getByTestId('dashboard-source-notices')).toHaveTextContent(
      'Fonte A sem atualização',
    );
  });

  it('a source turns late by age within 30 s, without a new call', async () => {
    jest.useFakeTimers({ now: NOW });
    const channel = fakeChannel();
    const net = fakeFetch();
    net.respond(channel.response);
    renderDashboard(botsApi([]), { net, now: () => new Date(Date.now()) });
    await act(async () =>
      channel.push(
        sseBlock('data.status', {
          items: [sourceStatus({ lastUpdatedAt: '2026-10-28T11:56:00Z' })],
        }),
      ),
    );
    expect(screen.getByTestId('dashboard-sources-ok')).toBeInTheDocument();

    await act(async () => {
      await jest.advanceTimersByTimeAsync(90_000);
    });

    expect(screen.getByTestId('dashboard-source-notices')).toHaveTextContent(
      'Fonte A sem atualização desde 08:56',
    );
  });

  it('a dropped channel shows the outdated notice and "Tentar de novo" reopens it and reads the cards', async () => {
    const first = fakeChannel();
    const net = fakeFetch();
    net.respond(first.response, fakeChannel().response);
    jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    const { api } = renderDashboard(botsApi([]), { net });
    await screen.findByTestId('dashboard-pnl-realized');

    await act(async () => first.end());
    const notice = await screen.findByTestId('dashboard-stale-notice');
    expect(notice).toHaveTextContent(
      'Os valores podem estar desatualizados; última atualização às 09:00',
    );
    const pnlCalls = api.getPnl.mock.calls.length;
    await userEvent.click(within(notice).getByTestId('dashboard-stale-retry-button'));

    await waitFor(() => expect(screen.queryByTestId('dashboard-stale-notice')).toBeNull());
    expect(net.requests).toHaveLength(2);
    await waitFor(() => expect(api.getPnl.mock.calls.length).toBeGreaterThan(pnlCalls));
  });

  it('announces only changes, joined within 2 s, and never the heartbeat', async () => {
    jest.useFakeTimers({ now: NOW });
    const channel = fakeChannel();
    const net = fakeFetch();
    net.respond(channel.response);
    renderDashboard(botsApi([ACTIVE]), { net });
    await act(async () => {
      await jest.advanceTimersByTimeAsync(10);
    });
    const region = screen.getByTestId('app-notices-region');

    await act(async () => channel.push(sseBlock('heartbeat', { at: NOW.toISOString() })));
    await act(async () => {
      await jest.advanceTimersByTimeAsync(3_000);
    });
    expect(region).toHaveTextContent('');

    await act(async () => {
      channel.push(
        sseBlock('bot.state', {
          items: [
            {
              ...ACTIVE,
              state: 'PAUSED',
              pauseReasons: ['SYSTEM'],
              updatedAt: '2026-10-28T11:59:00Z',
            },
          ],
          activeCount: 0,
        }),
      );
      channel.push(sseBlock('trade.recorded', { items: [tradeNotice()] }));
    });
    await act(async () => {
      await jest.advanceTimersByTimeAsync(1_000);
    });
    expect(region).toHaveTextContent('');
    await act(async () => {
      await jest.advanceTimersByTimeAsync(1_000);
    });

    expect(region).toHaveTextContent('Bot Alfa: Pausado (pausado pelo sistema)');
    expect(region).toHaveTextContent('Nova operação simulada do bot Alfa: Venda');
    expect(document.activeElement).toBe(
      screen.getByRole('heading', { level: 2, name: /Bots PAPER/ }),
    );
  });

  it('at 00:00 UTC the period is computed again and the P&L read again', async () => {
    jest.useFakeTimers({ now: new Date('2026-10-28T23:59:00Z') });
    const { api } = renderDashboard(botsApi([]), { now: () => new Date(Date.now()) });
    await act(async () => {
      await jest.advanceTimersByTimeAsync(10);
    });

    await act(async () => {
      await jest.advanceTimersByTimeAsync(60_000);
    });

    expect(api.getPnl).toHaveBeenLastCalledWith({
      from: '2026-10-29T00:00:00.000Z',
      to: '2026-10-29T00:00:00.000Z',
    });
    expect(screen.getByTestId('dashboard-pnl-period')).toHaveTextContent('desde 21:00');
  });

  it('a failed renewal of the channel sends the person to the entry with the return to the dashboard', async () => {
    jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    const channel = fakeChannel();
    const net = fakeFetch();
    net.respond(channel.response);
    // The renewal of the API double fails (no refresh cookie).
    const api = botsApi([]);
    renderDashboard(api, { net });
    await screen.findByTestId('dashboard-pnl-realized');

    await act(async () =>
      channel.push(sseBlock('auth.expired', { expiredAt: '2026-10-28T12:00:00Z' })),
    );

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/entrar?origem=%2F&expirada=1'));
    expect(api.refreshSession).toHaveBeenCalledTimes(1);
    expect(net.requests).toHaveLength(1);
  });
});
