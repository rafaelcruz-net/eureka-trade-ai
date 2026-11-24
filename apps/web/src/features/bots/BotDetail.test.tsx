import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import { tokenStore } from '@/lib/auth/token-store';
import type { GuestApi, PnlReport } from '@/lib/api/guest-api';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { BotDetail } from '@/features/bots/BotDetail';
import {
  BOT_ID,
  decision,
  detail,
  LIMITS,
  MINT,
  OTHER_MINT,
  summary,
} from '@/features/bots/fixtures';
import { botMessages } from '@/features/bots/messages';
import { LINES_PAGE_2, PAPER_BLOCK, REAL_BLOCK } from '@/features/pnl/fixtures';
import BotPage from '@/app/bots/[botId]/page';

const replace = jest.fn();

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: replace }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ botId: '0b0e0000-0000-4000-8000-000000000001' }),
}));

const text = botMessages.detail;
const NOW = () => new Date('2026-10-28T15:00:00Z');

const ACTIVE = detail({
  state: 'ACTIVE',
  limits: LIMITS,
  limitsVersion: 2,
  virtualBalance: '1000.000000',
  activatedAt: '2026-10-28T10:00:00Z',
  tokenSelection: {
    tokens: [{ chain: 'solana', mint: MINT }],
    rules: [{ field: 'LIQUIDITY', operator: 'GTE', value: '10000' }],
  },
});

const WITH_POSITION: PnlReport = {
  from: '2026-10-28T10:00:00Z',
  to: '2026-10-28T15:01:00Z',
  realized: '0.000000',
  unrealized: { status: 'KNOWN', value: '5.000000' },
  fees: '0.100000',
  partial: false,
  empty: false,
  unrealizedAsOf: '2026-10-28T15:00:00Z',
  byToken: [
    {
      chain: 'solana',
      mint: MINT,
      realized: '0.000000',
      unrealized: { status: 'KNOWN', value: '5.000000' },
      fees: '0.100000',
      mark: {
        status: 'KNOWN',
        value: '1.2',
        source: { sourceId: 's', name: 'sim', simulated: true },
        observedAt: '2026-10-28T15:00:00Z',
      },
      noLiquidity: false,
    },
    {
      chain: 'solana',
      mint: OTHER_MINT,
      realized: '1.000000',
      unrealized: { status: 'KNOWN', value: '0.000000' },
      fees: '0.100000',
      mark: {
        status: 'KNOWN',
        value: '1.0',
        source: { sourceId: 's', name: 'sim', simulated: true },
        observedAt: '2026-10-28T15:00:00Z',
      },
      noLiquidity: false,
    },
  ],
} as PnlReport;

function renderBot(api: jest.Mocked<GuestApi>, timeZone = 'America/Sao_Paulo') {
  tokenStore.set('access-token-value');
  render(
    <Providers api={api}>
      <BotDetail timeZone={timeZone} now={NOW} />
    </Providers>,
  );
  return api;
}

beforeEach(() => {
  replace.mockReset();
  tokenStore.clear();
});

describe('S5 bot screen /bots/{botId}', () => {
  it('shows state, limits with version, day usage and the empty trades sentence with the rules in use', async () => {
    const api = renderBot(fakeApi({ getBot: jest.fn(async () => ACTIVE) }));

    expect(await screen.findByTestId('bot-state')).toHaveTextContent('Ativo');
    // 261121-paper-real-unico U3 (BR9.3): the badge of the mode replaces "MODO PAPER (simulado)" in the title.
    expect(screen.getByTestId('bot-title')).toHaveTextContent('SIMULADO');
    expect(screen.getByTestId('bot-title')).not.toHaveTextContent(botMessages.paperLabel);
    expect(screen.getByText(text.limitsVersion(2))).toBeInTheDocument();
    expect(screen.getByTestId('bot-limit-maxOrderSize')).toHaveTextContent('100,000000 USDC');
    expect(await screen.findByTestId('bot-day-loss')).toHaveTextContent(
      'Perda do dia: 0,000000 de 50,000000 USDC',
    );
    expect(await screen.findByTestId('bot-no-trades')).toHaveTextContent(text.noTrades);
    expect(screen.getByTestId('bot-rules')).toHaveTextContent(
      'Liquidez (USDC) maior ou igual a 10.000',
    );
    expect(screen.getByTestId('bot-pause')).toBeInTheDocument();
    expect(screen.queryByTestId('bot-resume')).toBeNull();
    expect(api.getPnl).toHaveBeenCalledWith({
      from: '2026-10-28T10:00:00Z',
      to: '2026-10-28T15:01:00.000Z',
      botId: BOT_ID,
    });
  });

  it('pauses and shows the confirmed state; a refused command keeps the last confirmed state and says so', async () => {
    const getBot = jest
      .fn()
      .mockResolvedValueOnce(ACTIVE)
      .mockResolvedValue({ ...ACTIVE, state: 'PAUSED', pauseReasons: ['PERSON'] });
    const api = fakeApi({
      getBot,
      runBotCommand: jest
        .fn()
        .mockResolvedValueOnce(summary({ state: 'PAUSED' }))
        .mockRejectedValueOnce(new ApiError(409, 'DAILY_LOSS_LIMIT_REACHED', '62.5')),
    });
    renderBot(api);

    await userEvent.click(await screen.findByTestId('bot-pause'));
    await waitFor(() => expect(screen.getByTestId('bot-state')).toHaveTextContent('Pausado'));
    expect(screen.getByTestId('bot-reasons')).toHaveTextContent('pausado por você');

    await userEvent.click(screen.getByTestId('bot-resume'));
    expect(await screen.findByTestId('bot-notice')).toHaveTextContent(text.resumeLoss('62,5'));
    expect(screen.getByTestId('bot-state')).toHaveTextContent('Pausado');
  });

  it('warns that positions stay open while paused and shows the system restart in the person time zone', async () => {
    renderBot(
      fakeApi({
        getBot: jest.fn(async () => ({
          ...ACTIVE,
          state: 'PAUSED',
          pauseReasons: ['DAILY_LOSS'],
          resumesAt: '2026-10-29T00:00:00Z',
        })),
        getPnl: jest.fn(async () => WITH_POSITION),
      }),
    );

    expect(await screen.findByTestId('bot-paused-positions')).toHaveTextContent(
      text.pausedPositions,
    );
    expect(screen.getByTestId('bot-restart')).toHaveTextContent(
      'volta a operar às 21:00, horário de Brasília',
    );
  });

  it('the stop dialog lists the open positions, keeps the focus inside, closes on Esc and returns the focus', async () => {
    const api = fakeApi({
      getBot: jest.fn(async () => ACTIVE),
      getPnl: jest.fn(async () => WITH_POSITION),
      runBotCommand: jest.fn(async () => summary({ state: 'STOPPED' })),
    });
    renderBot(api);

    const stop = await screen.findByTestId('bot-stop');
    await userEvent.click(stop);
    const dialog = screen.getByTestId('bot-stop-dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(within(dialog).getByTestId('bot-stop-positions')).toHaveTextContent('Test…1111');
    expect(within(dialog).queryByText('Test…2222')).toBeNull();
    expect(screen.getByTestId('bot-stop-cancel')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('bot-stop-confirm')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('bot-stop-cancel')).toHaveFocus();
    await userEvent.tab({ shift: true });
    expect(screen.getByTestId('bot-stop-confirm')).toHaveFocus();

    await userEvent.keyboard('{Escape}');
    expect(screen.queryByTestId('bot-stop-dialog')).toBeNull();
    expect(stop).toHaveFocus();
    expect(api.runBotCommand).not.toHaveBeenCalled();

    await userEvent.click(stop);
    await userEvent.click(screen.getByTestId('bot-stop-confirm'));
    expect(api.runBotCommand).toHaveBeenCalledWith(BOT_ID, 'stop');
  });

  it('edits the limits as a new version, refusing incoherent values before sending', async () => {
    const api = fakeApi({
      getBot: jest.fn(async () => ACTIVE),
      changeBotLimits: jest.fn(async () => summary({ limitsVersion: 3 })),
    });
    renderBot(api);

    await userEvent.click(await screen.findByTestId('bot-edit-limits'));
    expect(screen.getByTestId('bot-limits-dialog')).toHaveTextContent(
      botMessages.limitsDialog.intro,
    );
    const order = screen.getByTestId('bot-limits-form-maxOrderSize');
    await userEvent.clear(order);
    await userEvent.type(order, '300');
    await userEvent.click(screen.getByTestId('bot-limits-save'));
    expect(screen.getByTestId('bot-limits-form-maxOrderSize-error')).toHaveTextContent(
      botMessages.validation.orderAboveToken,
    );
    expect(api.changeBotLimits).not.toHaveBeenCalled();

    await userEvent.clear(order);
    await userEvent.type(order, '150');
    await userEvent.click(screen.getByTestId('bot-limits-save'));
    expect(api.changeBotLimits).toHaveBeenCalledWith(BOT_ID, { ...LIMITS, maxOrderSize: '150' });
    await waitFor(() => expect(screen.queryByTestId('bot-limits-dialog')).toBeNull());
  });

  it('a refused limit change keeps the dialog with the sentence; Cancelar closes without effect', async () => {
    const api = fakeApi({
      getBot: jest.fn(async () => ACTIVE),
      changeBotLimits: jest.fn(async () => {
        throw new ApiError(409, 'INVALID_TRANSITION');
      }),
    });
    renderBot(api);

    await userEvent.click(await screen.findByTestId('bot-edit-limits'));
    await userEvent.click(screen.getByTestId('bot-limits-save'));
    expect(await screen.findByTestId('bot-limits-notice')).toHaveTextContent(
      text.invalidTransition,
    );
    await userEvent.click(screen.getByTestId('bot-limits-cancel'));
    expect(screen.queryByTestId('bot-limits-dialog')).toBeNull();
  });

  it('rejections: a 404 of C21 is "Nenhuma recusa."', async () => {
    const listBotRejections = jest.fn().mockRejectedValueOnce(new ApiError(404, 'NOT_FOUND'));
    const api = fakeApi({ getBot: jest.fn(async () => ACTIVE), listBotRejections });

    renderBot(api);
    await userEvent.click(await screen.findByTestId('bot-rejections-open'));
    expect(await screen.findByTestId('bot-rejections-empty')).toHaveTextContent(text.noRejections);
  });

  it('a duplicate rejection is written as already evaluated, and a failure says so', async () => {
    const listBotRejections = jest.fn().mockResolvedValueOnce({
      items: [
        {
          decisionId: 'd-9',
          evaluatedAt: '2026-10-28T12:00:00Z',
          reasons: ['DUPLICATE'],
          details: [],
          limitsVersion: 1,
          mode: 'PAPER',
          pauseTriggers: [],
        },
      ],
      nextCursor: null,
      total: 1,
    });
    renderBot(fakeApi({ getBot: jest.fn(async () => ACTIVE), listBotRejections }));

    await userEvent.click(await screen.findByTestId('bot-rejections-open'));
    expect(await screen.findByTestId('bot-rejection-d-9')).toHaveTextContent(text.duplicate);
  });

  it('a rejection failure shows its own sentence', async () => {
    renderBot(
      fakeApi({
        getBot: jest.fn(async () => ACTIVE),
        listBotRejections: jest.fn(async () => {
          throw new ApiError(503, 'SERVICE_UNAVAILABLE');
        }),
      }),
    );

    await userEvent.click(await screen.findByTestId('bot-rejections-open'));
    expect(await screen.findByTestId('bot-rejections-error')).toHaveTextContent(
      text.rejectionsError,
    );
  });

  it('shows the stale-data notice with the last observation time, and the explained decisions with unknown', async () => {
    const stale = decision({
      decisionId: 'd-2',
      action: 'NO_TRADE',
      noTradeReason: 'STALE_DATA',
      inputs: [
        { status: 'KNOWN', value: '1', observedAt: '2026-10-28T11:50:00Z', stale: true },
        { status: 'UNKNOWN', reason: 'SOURCE_UNAVAILABLE' },
      ],
    });
    const listBotDecisions = jest
      .fn()
      .mockImplementation(
        async (_bot: string, request: { cursor?: string | null; limit?: number }) => {
          if (request.limit === 1) {
            return { items: [stale], nextCursor: 'x' };
          }
          return request.cursor
            ? { items: [decision({ decisionId: 'd-old' })], nextCursor: null }
            : { items: [stale, decision()], nextCursor: 'next' };
        },
      );
    renderBot(fakeApi({ getBot: jest.fn(async () => ACTIVE), listBotDecisions }));

    expect(await screen.findByTestId('bot-stale')).toHaveTextContent('28/10/2026, 08:50:00');
    const buy = await screen.findByTestId('bot-decision-d-1');
    expect(buy).toHaveTextContent('Comprar');
    expect(buy).toHaveTextContent('Variação de preço: 3,5 (2)');
    expect(buy).toHaveTextContent('volume: unknown');
    expect(screen.getByTestId('bot-factor-d-1-1')).toHaveTextContent(text.notSatisfied);
    expect(screen.getByTestId('bot-decision-d-2')).toHaveTextContent('Não operar');

    await userEvent.click(screen.getByTestId('bot-decisions-more'));
    expect(await screen.findByTestId('bot-decision-d-old')).toBeInTheDocument();
    expect(screen.queryByTestId('bot-decisions-more')).toBeNull();
  });

  it('a stale decision without observation time still warns; decisions failure has its sentence', async () => {
    const listBotDecisions = jest
      .fn()
      .mockImplementation(async (_bot: string, request: { limit?: number }) => {
        if (request.limit === 1) {
          return {
            items: [decision({ noTradeReason: 'STALE_DATA', action: 'NO_TRADE' })],
            nextCursor: null,
          };
        }
        throw new ApiError(503, 'SERVICE_UNAVAILABLE');
      });
    renderBot(fakeApi({ getBot: jest.fn(async () => ACTIVE), listBotDecisions }));

    expect(await screen.findByTestId('bot-stale')).toHaveTextContent(text.staleUnknownTime);
    expect(await screen.findByTestId('bot-decisions-error')).toHaveTextContent(text.decisionsError);
  });

  it('with operations shows the trades table of U7; without day usage says when it appears', async () => {
    renderBot(
      fakeApi({
        getBot: jest.fn(async () => ACTIVE),
        listBotTrades: jest.fn(async () => {
          throw new ApiError(503, 'SERVICE_UNAVAILABLE');
        }),
        getDayUsage: jest.fn(async () => {
          throw new ApiError(404, 'NOT_FOUND');
        }),
      }),
    );

    expect(await screen.findByTestId('bot-trades')).toBeInTheDocument();
    expect(await screen.findByTestId('bot-day-usage-unavailable')).toHaveTextContent(
      text.dayUsageUnavailable,
    );
  });

  it('a validated bot links back to the wizard and offers no command here', async () => {
    renderBot(
      fakeApi({ getBot: jest.fn(async () => detail({ state: 'VALIDATED', draftStep: 3 })) }),
    );

    expect(await screen.findByTestId('bot-open-wizard')).toHaveAttribute(
      'href',
      `/bots/new/${BOT_ID}`,
    );
    expect(screen.queryByTestId('bot-start')).toBeNull();
    expect(screen.queryByTestId('bot-edit-limits')).toBeNull();
    expect(screen.queryByTestId('bot-day-usage')).toBeNull();
  });

  it('the page says the bot was not found on a 404 (another person bot is never shown)', async () => {
    tokenStore.set('access-token-value');
    render(
      <Providers
        api={fakeApi({
          getBot: jest.fn(async () => {
            throw new ApiError(404, 'NOT_FOUND');
          }),
        })}
      >
        <BotPage />
      </Providers>,
    );

    expect(await screen.findByTestId('bot-not-found')).toHaveTextContent(text.notFound);
  });

  it('a technical failure offers a retry that reloads the bot', async () => {
    const getBot = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
      .mockResolvedValue(ACTIVE);
    renderBot(fakeApi({ getBot }));

    await userEvent.click(await screen.findByTestId('bot-retry'));
    expect(await screen.findByTestId('bot-state')).toHaveTextContent('Ativo');
  });

  it('a 401 hands over to the session expiry', async () => {
    renderBot(
      fakeApi({
        getBot: jest.fn(async () => {
          throw new ApiError(401, 'UNAUTHENTICATED');
        }),
      }),
    );

    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith(`/entrar?origem=%2Fbots%2F${BOT_ID}&expirada=1`),
    );
  });
});

/** 261121-paper-real-unico U3, Step 21 (BR4.5, BR4.6, BR8.2, BR9.3; AC3.10.1, AC3.10.3, AC4.2.4). */
describe('S5 bot screen with the P&L of its mode', () => {
  const REAL_DETAIL = detail({
    botId: BOT_ID,
    name: 'Real B',
    market: 'REAL',
    mode: 'REAL',
    state: 'ACTIVE',
    activatedAt: '2026-10-28T10:00:00Z',
  });
  const realBlock = {
    ...REAL_BLOCK,
    bots: REAL_BLOCK.bots.map((bot) => ({ ...bot, botId: BOT_ID, name: 'Real B' })),
  };
  const paperBlock = {
    ...PAPER_BLOCK,
    bots: PAPER_BLOCK.bots.map((bot) => ({ ...bot, botId: BOT_ID, name: 'Meu bot' })),
  };

  it('a REAL bot: one getBot, one C4 with market REAL and one C5; its line, no disclaimer', async () => {
    const api = renderBot(
      fakeApi({
        getBot: jest.fn(async () => REAL_DETAIL),
        getPnlByMode: jest.fn(async () => ({ period: { kind: 'ALL' as const }, real: realBlock })),
        listBotPnlLines: jest.fn(async () => LINES_PAGE_2),
      }),
    );

    expect(await screen.findByTestId('bot-pnl-total')).toHaveTextContent('+0,0312 SOL');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Real B');
    expect(screen.getByTestId('bot-title')).toHaveTextContent('REAL');
    expect(screen.getByTestId('bot-mode-line')).toHaveTextContent(
      'Modo: Real · não pode ser trocado',
    );
    expect(screen.getByRole('heading', { level: 2, name: /P&L do bot/ })).toBeInTheDocument();
    expect(
      await screen.findByRole('heading', { level: 2, name: 'Linhas do P&L' }),
    ).toBeInTheDocument();
    expect(await screen.findByTestId('bot-lines-row-0')).toBeInTheDocument();
    expect(api.getBot).toHaveBeenCalledTimes(1);
    expect(api.getPnlByMode).toHaveBeenCalledTimes(1);
    expect(api.getPnlByMode).toHaveBeenCalledWith({ market: 'REAL' });
    expect(api.listBotPnlLines).toHaveBeenCalledTimes(1);
    expect(api.getPnl).not.toHaveBeenCalled();
    expect(screen.queryByTestId('bot-pnl-disclaimer')).toBeNull();
    expect(screen.queryByText('O resultado simulado não garante o resultado real.')).toBeNull();
  });

  it('a PAPER bot: one C4 with market PAPER, the disclaimer and no request to C5', async () => {
    const api = renderBot(
      fakeApi({
        getBot: jest.fn(async () => ACTIVE),
        getPnlByMode: jest.fn(async () => ({
          period: { kind: 'ALL' as const },
          paper: paperBlock,
        })),
      }),
    );

    expect(await screen.findByTestId('bot-pnl-total')).toHaveTextContent('+US$ 12,00');
    expect(screen.getByTestId('bot-pnl-disclaimer')).toHaveTextContent(
      'O resultado simulado não garante o resultado real.',
    );
    expect(screen.getByTestId('bot-mode-line')).toHaveTextContent(
      'Modo: Simulado · não pode ser trocado',
    );
    expect(api.getPnlByMode).toHaveBeenCalledWith({ market: 'PAPER' });
    expect(api.listBotPnlLines).not.toHaveBeenCalled();
    expect(screen.queryByTestId('bot-lines')).toBeNull();
  });

  it('a bot without a line in C4 shows "—"', async () => {
    renderBot(fakeApi({ getBot: jest.fn(async () => ACTIVE) }));

    expect(await screen.findByTestId('bot-pnl-none')).toHaveTextContent('—');
  });

  it('a 404 of C5 shows only "Bot não encontrado." with the way back, no name, mode, P&L nor code', async () => {
    renderBot(
      fakeApi({
        getBot: jest.fn(async () => REAL_DETAIL),
        getPnlByMode: jest.fn(async () => ({ period: { kind: 'ALL' as const }, real: realBlock })),
        listBotPnlLines: jest.fn(async () => {
          throw new ApiError(404, 'NOT_FOUND', undefined, 'corr-404');
        }),
      }),
    );

    const notFound = await screen.findByTestId('bot-not-found');
    expect(notFound).toHaveAttribute('role', 'alert');
    expect(notFound).toHaveTextContent('Bot não encontrado.');
    expect(screen.getByTestId('bot-not-found-back')).toHaveTextContent('Voltar para Meus bots');
    expect(screen.getByTestId('bot-not-found-back')).toHaveAttribute('href', '/bots');
    const main = screen.getByRole('main');
    expect(main).not.toHaveTextContent('Real B');
    expect(main).not.toHaveTextContent('SOL');
    expect(main).not.toHaveTextContent('corr-404');
    expect(main).not.toHaveTextContent(/Modo:/);
  });

  it('an unavailable block of C4 is an error of the P&L of the bot with its code', async () => {
    renderBot(
      fakeApi({
        getBot: jest.fn(async () => ACTIVE),
        getPnlByMode: jest.fn(async () => ({
          period: { kind: 'ALL' as const },
          paper: {
            status: 'UNAVAILABLE' as const,
            market: 'PAPER' as const,
            currency: 'USD' as const,
            code: 'SERVICE_UNAVAILABLE',
            correlationId: 'corr-11',
          },
        })),
      }),
    );

    expect(await screen.findByTestId('bot-pnl-code')).toHaveTextContent('corr-11');
  });
});
