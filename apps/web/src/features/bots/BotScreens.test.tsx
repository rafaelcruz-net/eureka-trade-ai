import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import { tokenStore } from '@/lib/auth/token-store';
import type { GuestApi } from '@/lib/api/guest-api';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { BotCreate } from '@/features/bots/BotCreate';
import { BotList } from '@/features/bots/BotList';
import { BotWizard } from '@/features/bots/BotWizard';
import {
  BOT_ID,
  detail,
  LIMITS,
  MINT,
  RANGE_TEMPLATE,
  summary,
  TEMPLATE,
} from '@/features/bots/fixtures';
import { botMessages } from '@/features/bots/messages';
import BotsPage from '@/app/bots/page';
import NewBotPage from '@/app/bots/new/page';
import BotWizardPage from '@/app/bots/new/[botId]/page';

const push = jest.fn();
const replace = jest.fn();
let search = new URLSearchParams();

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push }),
  useSearchParams: () => search,
  useParams: () => ({ botId: '0b0e0000-0000-4000-8000-000000000001' }),
}));

const text = botMessages;

// One userEvent instance per test, with no pause between keystrokes: the S4
// flow types ~30 interactions and went past Jest's 5 s default when the whole
// suite ran in parallel (Build and Test loop 3, F11).
let user: UserEvent;

// The 4-step flow gets its own budget; Jest's global 5 s default is unchanged.
const WIZARD_FLOW_TIMEOUT_MS = 15_000;

function renderWith(node: React.ReactNode, api: jest.Mocked<GuestApi>) {
  tokenStore.set('access-token-value');
  render(<Providers api={api}>{node}</Providers>);
  return api;
}

beforeEach(() => {
  push.mockReset();
  replace.mockReset();
  search = new URLSearchParams();
  tokenStore.clear();
  user = userEvent.setup({ delay: null });
});

describe('bot list /bots', () => {
  it('shows loading, then the empty state with the way to create the first bot', async () => {
    renderWith(<BotsPage />, fakeApi());

    // 261121-paper-real-unico U1 (BR4.2): one list for both modes, titled "Meus bots" without a mode label.
    expect(screen.getByTestId('bots-title')).toHaveTextContent('Meus bots');
    expect(screen.getByTestId('bots-title')).not.toHaveTextContent(text.paperLabel);
    expect(screen.getByTestId('bots-loading')).toBeInTheDocument();
    expect(await screen.findByTestId('bots-empty')).toHaveTextContent(text.empty);
    expect(screen.getByTestId('bots-counter')).toHaveTextContent('0 de 3 bots ativos');
  });

  it('writes state, reasons and the restart time, and offers only the valid actions', async () => {
    const api = fakeApi({
      listBots: jest.fn(async () => ({
        items: [
          summary({
            state: 'PAUSED',
            pauseReasons: ['DAILY_LOSS'],
            resumesAt: '2026-10-29T00:00:00Z',
          }),
          summary({ botId: 'draft-1', state: 'DRAFT', name: 'Rascunho' }),
        ],
        nextCursor: null,
        activeCount: 2,
        activeLimit: 3,
        matchingCount: 2,
        totalCount: 2,
      })),
    });
    renderWith(<BotList timeZone="America/Sao_Paulo" />, api);

    expect(await screen.findByTestId(`bots-state-${BOT_ID}`)).toHaveTextContent('Pausado');
    expect(screen.getByTestId(`bots-reasons-${BOT_ID}`)).toHaveTextContent('perda diária atingida');
    expect(screen.getByTestId(`bots-restart-${BOT_ID}`)).toHaveTextContent(
      'volta a operar às 21:00, horário de Brasília',
    );
    expect(screen.getByTestId(`bots-resume-${BOT_ID}`)).toBeInTheDocument();
    expect(screen.queryByTestId(`bots-pause-${BOT_ID}`)).toBeNull();
    expect(screen.getByTestId('bots-open-draft-1')).toHaveAttribute('href', '/bots/new/draft-1');
    expect(screen.getByTestId('bots-open-draft-1')).toHaveTextContent(text.resumeDraft);
    expect(screen.getByTestId('bots-counter')).toHaveTextContent('2 de 3 bots ativos');
  });

  it('a refused resume says why and reloads the confirmed state', async () => {
    const api = fakeApi({
      listBots: jest.fn(async () => ({
        items: [summary({ state: 'PAUSED', pauseReasons: ['PERSON'] })],
        nextCursor: null,
        activeCount: 3,
        activeLimit: 3,
        matchingCount: 1,
        totalCount: 1,
      })),
      runBotCommand: jest.fn(async () => {
        throw new ApiError(409, 'ACTIVE_BOT_LIMIT');
      }),
    });
    renderWith(<BotList timeZone="UTC" />, api);

    await user.click(await screen.findByTestId(`bots-resume-${BOT_ID}`));

    expect(await screen.findByTestId('bots-notice')).toHaveTextContent(text.detail.resumeLimit);
    expect(api.runBotCommand).toHaveBeenCalledWith(BOT_ID, 'resume');
    await waitFor(() => expect(api.listBots).toHaveBeenCalledTimes(2));
  });

  it('filters by state and shows the error with a retry', async () => {
    const listBots = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
      .mockResolvedValue({ items: [], nextCursor: null, activeCount: 0, activeLimit: 3 });
    const api = renderWith(<BotList timeZone="UTC" />, fakeApi({ listBots }));

    expect(await screen.findByTestId('bots-error')).toHaveTextContent(text.error);
    await user.click(screen.getByTestId('bots-retry'));
    await screen.findByTestId('bots-empty');
    await user.selectOptions(screen.getByTestId('bots-filter'), 'ACTIVE');
    // 261121-paper-real-unico U3 (BR1.2, NFR1.8): the state filter goes with the market of the filter by mode (none in
    // "Todos") and the signal of the slot of the list.
    await waitFor(() =>
      expect(api.listBots).toHaveBeenLastCalledWith(
        { state: 'ACTIVE', market: undefined },
        expect.objectContaining({ signal: expect.any(AbortSignal) }),
      ),
    );
  });

  it('a 401 hands over to the session expiry', async () => {
    renderWith(
      <BotList timeZone="UTC" />,
      fakeApi({
        listBots: jest.fn(async () => {
          throw new ApiError(401, 'UNAUTHENTICATED');
        }),
      }),
    );

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/entrar?origem=%2Fbots&expirada=1'));
  });
});

describe('new bot /bots/new', () => {
  it('creates the draft with template and name and opens the wizard keeping the scanner token', async () => {
    search = new URLSearchParams({ chain: 'solana', mint: MINT });
    const api = fakeApi({
      listStrategyTemplates: jest.fn(async () => [TEMPLATE, RANGE_TEMPLATE]),
      createBot: jest.fn(async () => summary({ state: 'DRAFT' })),
    });
    renderWith(<NewBotPage />, api);

    await user.click(await screen.findByTestId('bot-create-RANGE_BREAKOUT'));
    await user.type(screen.getByTestId('bot-create-name'), '  Meu bot  ');
    await user.click(screen.getByTestId('bot-create-submit'));

    // 261121-paper-real-unico U1 (BR1.1, BR1.7): "Simulado" is the default and the market always travels.
    expect(api.createBot).toHaveBeenCalledWith({
      templateId: 'RANGE_BREAKOUT',
      name: 'Meu bot',
      market: 'PAPER',
    });
    expect(push).toHaveBeenCalledWith(`/bots/new/${BOT_ID}?chain=solana&mint=${MINT}`);
  });

  it('refuses an empty name and says when the draft could not be created', async () => {
    const api = fakeApi({ listStrategyTemplates: jest.fn(async () => [TEMPLATE]) });
    renderWith(<BotCreate />, api);

    await user.click(await screen.findByTestId('bot-create-submit'));
    expect(screen.getByTestId('bot-create-error')).toHaveTextContent(text.create_.nameRequired);
    expect(api.createBot).not.toHaveBeenCalled();

    await user.type(screen.getByTestId('bot-create-name'), 'Bot');
    await user.click(screen.getByTestId('bot-create-submit'));
    expect(await screen.findByTestId('bot-create-error')).toHaveTextContent(text.create_.error);
  });
});

function wizardApi(overrides: Partial<GuestApi> = {}) {
  return fakeApi({
    getBot: jest.fn(async () => detail()),
    listStrategyTemplates: jest.fn(async () => [TEMPLATE]),
    saveBotDraft: jest.fn(async () => summary({ state: 'DRAFT' })),
    ...overrides,
  });
}

describe('S4 wizard /bots/new/{botId}', () => {
  it('step 1 cannot continue without a token or a rule and says why', async () => {
    renderWith(<BotWizardPage />, wizardApi());

    expect(await screen.findByTestId('wizard-step')).toHaveTextContent(
      'Etapa 2 de 5: Estratégia e tokens',
    );
    expect(screen.getByTestId('wizard-next-1')).toBeDisabled();
    expect(screen.getByTestId('wizard-next-reason')).toHaveTextContent(text.wizard.nextUnavailable);
    expect(screen.getByTestId('wizard-param-takeProfitPct-term')).toHaveTextContent('Take profit');
  });

  it(
    'goes through the 4 steps, saving each accepted step, and starts the PAPER bot',
    async () => {
      const api = wizardApi();
      renderWith(<BotWizard />, api);

      await user.type(await screen.findByTestId('wizard-mint'), MINT);
      await user.click(screen.getByTestId('wizard-add-token'));
      await user.click(screen.getByTestId('wizard-add-rule'));
      await user.selectOptions(screen.getByTestId('wizard-rule-field-0'), 'HOLDERS');
      await user.selectOptions(screen.getByTestId('wizard-rule-operator-0'), 'LTE');
      await user.clear(screen.getByTestId('wizard-rule-value-0'));
      await user.type(screen.getByTestId('wizard-rule-value-0'), '500');
      await user.click(screen.getByTestId('wizard-next-1'));

      expect(api.saveBotDraft).toHaveBeenCalledWith(BOT_ID, {
        step: 1,
        parameters: { takeProfitPct: '10', stopLossPct: '5' },
        tokenSelection: {
          tokens: [{ chain: 'solana', mint: MINT }],
          rules: [{ field: 'HOLDERS', operator: 'LTE', value: '500' }],
        },
      });
      expect(await screen.findByTestId('wizard-step-2')).toBeInTheDocument();

      // The limits follow the balance until edited (BR1.6).
      await user.clear(screen.getByTestId('wizard-balance'));
      await user.type(screen.getByTestId('wizard-balance'), '2000');
      expect(screen.getByTestId('wizard-limits-maxOrderSize')).toHaveValue('200.000000');
      await user.clear(screen.getByTestId('wizard-limits-maxDailyLoss'));
      await user.type(screen.getByTestId('wizard-limits-maxDailyLoss'), '30');
      await user.clear(screen.getByTestId('wizard-balance'));
      await user.type(screen.getByTestId('wizard-balance'), '4000');
      expect(screen.getByTestId('wizard-limits-maxOrderSize')).toHaveValue('400.000000');
      expect(screen.getByTestId('wizard-limits-maxDailyLoss')).toHaveValue('30');
      await user.click(screen.getByTestId('wizard-next-2'));

      expect(api.saveBotDraft).toHaveBeenLastCalledWith(
        BOT_ID,
        expect.objectContaining({ step: 2, virtualBalance: '4000' }),
      );
      expect(await screen.findByTestId('wizard-step-3')).toHaveTextContent(text.wizard.hypotheses);
      await user.click(screen.getByTestId('wizard-back-3'));
      expect(screen.getByTestId('wizard-balance')).toHaveValue('4000');
      await user.click(screen.getByTestId('wizard-next-2'));
      await user.click(await screen.findByTestId('wizard-next-3'));
      expect(api.saveBotDraft).toHaveBeenLastCalledWith(BOT_ID, { step: 3 });

      let finish: (value: never) => void = () => undefined;
      api.runBotCommand.mockImplementation(
        () => new Promise((resolve) => (finish = resolve as never)),
      );
      await user.click(await screen.findByTestId('wizard-start'));
      expect(screen.getByTestId('wizard-start')).toHaveTextContent(text.wizard.starting);
      expect(screen.getByTestId('wizard-start')).toBeDisabled();
      finish(summary() as never);
      await waitFor(() => expect(push).toHaveBeenCalledWith(`/bots/${BOT_ID}`));
      expect(api.runBotCommand).toHaveBeenCalledTimes(1);
    },
    WIZARD_FLOW_TIMEOUT_MS,
  );

  it('shows local and server field errors next to the fields', async () => {
    const api = wizardApi({
      getBot: jest.fn(async () =>
        detail({ draftStep: 2, tokenSelection: { tokens: [{ chain: 'solana', mint: MINT }] } }),
      ),
      saveBotDraft: jest.fn(async () => {
        throw new ApiError(422, 'VALIDATION_FAILED', undefined, 'c', [
          { field: 'limits.maxOrderSize', message: 'Recusado pelo servidor.' },
        ]);
      }),
    });
    renderWith(<BotWizard />, api);

    await user.clear(await screen.findByTestId('wizard-balance'));
    await user.type(screen.getByTestId('wizard-balance'), '20000');
    await user.click(screen.getByTestId('wizard-next-2'));
    expect(screen.getByTestId('wizard-balance-error')).toHaveTextContent(
      text.validation.balanceCap,
    );
    expect(api.saveBotDraft).not.toHaveBeenCalled();

    await user.clear(screen.getByTestId('wizard-balance'));
    await user.type(screen.getByTestId('wizard-balance'), '1000');
    await user.click(screen.getByTestId('wizard-next-2'));
    expect(await screen.findByTestId('wizard-limits-maxOrderSize-error')).toHaveTextContent(
      'Recusado pelo servidor.',
    );
    expect(screen.getByTestId('wizard-limits-maxOrderSize')).toHaveAttribute(
      'aria-invalid',
      'true',
    );
  });

  // 261121-paper-real-unico U1 (BR1.6, BR1.8, BR1.9, BR3.4): a REAL draft shows its mode in text, the REAL limits of
  // the account read only instead of the virtual balance, saves step 2 without them and starts as "Iniciar bot real".
  it('a REAL draft shows the limits of the account read only and saves its step without balance nor limits', async () => {
    const getRealAccountLimits = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE', undefined, 'corr-1'))
      .mockResolvedValueOnce({
        currency: 'SOL' as const,
        cap: { status: 'KNOWN' as const, value: '5000000000' },
        orderMax: { status: 'KNOWN' as const, value: '500000000', percentOfCap: '10' },
        dailyBuys: { status: 'KNOWN' as const, count: 20 },
        dailyLoss: { status: 'KNOWN' as const, value: '1000000000', percentOfCap: '20' },
      });
    const api = wizardApi({
      getBot: jest.fn(async () =>
        detail({
          market: 'REAL',
          mode: 'REAL',
          draftStep: 2,
          tokenSelection: { tokens: [{ chain: 'solana', mint: MINT }], rules: [] },
        }),
      ),
      getRealAccountLimits,
    });
    renderWith(<BotWizard />, api);

    expect(await screen.findByTestId('wizard-mode')).toHaveTextContent(
      'Modo: REAL · não pode ser trocado depois da criação',
    );
    expect(screen.getByTestId('wizard-mode-badge')).toHaveTextContent('REAL');
    expect(await screen.findByTestId('real-limits-error')).toHaveTextContent('corr-1');
    expect(screen.getByTestId('real-limits-continue')).toBeDisabled();
    await user.click(screen.getByTestId('real-limits-retry'));
    expect(await screen.findByTestId('real-limits-cap')).toHaveTextContent('5,00 SOL');
    expect(screen.queryByTestId('wizard-balance')).toBeNull();
    await user.click(screen.getByTestId('real-limits-continue'));

    await waitFor(() => expect(api.saveBotDraft).toHaveBeenCalledWith(BOT_ID, { step: 2 }));
    expect(await screen.findByTestId('wizard-step-3')).toHaveTextContent('Limites REAL da conta');
    await user.click(screen.getByTestId('wizard-next-3'));
    expect(await screen.findByTestId('wizard-start')).toHaveTextContent('Iniciar bot real');
  });

  it('reopens a validated draft at the start step and says when the start was refused', async () => {
    const api = wizardApi({
      getBot: jest.fn(async () =>
        detail({ state: 'VALIDATED', draftStep: 3, limits: LIMITS, virtualBalance: '1000.000000' }),
      ),
      runBotCommand: jest
        .fn()
        .mockRejectedValueOnce(new ApiError(409, 'ACTIVE_BOT_LIMIT'))
        .mockRejectedValueOnce(new ApiError(409, 'PAUSED_BY_SYSTEM'))
        .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE')),
    });
    renderWith(<BotWizard />, api);

    await user.click(await screen.findByTestId('wizard-start'));
    expect(await screen.findByTestId('wizard-notice')).toHaveTextContent(text.wizard.startLimit);
    await user.click(screen.getByTestId('wizard-start'));
    await waitFor(() =>
      expect(screen.getByTestId('wizard-notice')).toHaveTextContent(text.wizard.startSystem),
    );
    await user.click(screen.getByTestId('wizard-start'));
    await waitFor(() =>
      expect(screen.getByTestId('wizard-notice')).toHaveTextContent(text.wizard.startError),
    );
    expect(push).not.toHaveBeenCalled();
  });

  it('adds the token coming from the scanner and can remove it; a load failure offers a retry', async () => {
    search = new URLSearchParams({ chain: 'solana', mint: MINT });
    const getBot = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
      .mockResolvedValue(detail());
    renderWith(<BotWizard />, wizardApi({ getBot }));

    await user.click(await screen.findByTestId('wizard-retry'));
    expect(await screen.findByTestId('wizard-notice')).toHaveTextContent(text.wizard.fromScanner);
    expect(screen.getByTestId('wizard-token-0')).toHaveTextContent(MINT);
    await user.click(screen.getByTestId('wizard-token-remove-0'));
    expect(screen.queryByTestId('wizard-token-0')).toBeNull();
    await user.click(screen.getByTestId('wizard-add-rule'));
    await user.click(screen.getByTestId('wizard-rule-remove-0'));
    expect(screen.queryByTestId('wizard-rule-0')).toBeNull();
  });

  it('refuses a parameter off its grid before sending', async () => {
    const api = wizardApi({
      getBot: jest.fn(async () =>
        detail({ tokenSelection: { tokens: [{ chain: 'solana', mint: MINT }] } }),
      ),
    });
    renderWith(<BotWizard />, api);

    const field = await screen.findByTestId('wizard-param-takeProfitPct');
    await user.clear(field);
    await user.type(field, '60');
    await user.click(screen.getByTestId('wizard-next-1'));

    expect(screen.getByTestId('wizard-param-takeProfitPct-error')).toHaveTextContent(
      text.validation.range('1', '50', '0.5'),
    );
    expect(api.saveBotDraft).not.toHaveBeenCalled();
    within(screen.getByTestId('wizard-selection')).getByText(MINT);
  });
});
