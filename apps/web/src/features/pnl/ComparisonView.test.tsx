import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { ApiError } from '@/lib/api/client';
import { tokenStore } from '@/lib/auth/token-store';
import type { BotPage, GuestApi, PnlComparison } from '@/lib/api/guest-api';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { summary } from '@/features/bots/fixtures';
import {
  ComparisonView,
  comparisonAddress,
  parseComparison,
  type ComparisonSelection,
} from '@/features/pnl/ComparisonView';
import { COMPARISON, PAPER_BOT_ID, REAL_BOT_ID, TEMPLATE_ID } from '@/features/pnl/fixtures';
import { SINCE_START, applyPeriod } from '@/features/pnl/PeriodSelector';

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({}),
}));

const OTHER_REAL_ID = 'b0700000-0000-4000-8000-0000000000c3';
const ZONE = 'America/Sao_Paulo';

function bots(extra: BotPage['items'] = []): BotPage {
  return {
    items: [
      summary({
        botId: PAPER_BOT_ID,
        name: 'Simulado A',
        market: 'PAPER',
        mode: 'PAPER',
        state: 'ACTIVE',
        templateId: TEMPLATE_ID,
      }),
      summary({
        botId: REAL_BOT_ID,
        name: 'Real B',
        market: 'REAL',
        mode: 'REAL',
        state: 'ACTIVE',
        templateId: TEMPLATE_ID,
      }),
      summary({
        botId: OTHER_REAL_ID,
        name: 'Real C',
        market: 'REAL',
        mode: 'REAL',
        state: 'ACTIVE',
        templateId: 'RANGE_BREAKOUT',
      }),
      ...extra,
    ],
    nextCursor: null,
    activeCount: 3,
    activeLimit: 3,
    matchingCount: 3,
    totalCount: 3,
  };
}

const selections: (ComparisonSelection | null)[] = [];

function Harness({
  initial,
  period = SINCE_START,
}: {
  initial: ComparisonSelection | null;
  period?: typeof SINCE_START;
}) {
  const [selection, setSelection] = useState(initial);
  return (
    <ComparisonView
      selection={selection}
      period={period}
      timeZone={ZONE}
      onSelect={(next) => {
        selections.push(next);
        setSelection(next);
      }}
    />
  );
}

function renderView(
  overrides: Partial<GuestApi> = {},
  initial: ComparisonSelection | null = null,
  period = SINCE_START,
) {
  tokenStore.set('access-token-value');
  const api = fakeApi({
    listBots: jest.fn(async () => bots()),
    comparePnl: jest.fn(async () => COMPARISON),
    ...overrides,
  });
  render(
    <Providers api={api}>
      <Harness initial={initial} period={period} />
    </Providers>,
  );
  return api;
}

beforeEach(() => {
  selections.length = 0;
});

/** 261121-paper-real-unico U3, Step 22 (BR5.1 to BR5.6, BR2.6, BR6.1 to BR6.3; AC8.1.1 to AC8.1.4). */
describe('ComparisonView', () => {
  it('opens with the focus on "Bot simulado"; the single pair comes chosen and C6 is read', async () => {
    const api = renderView();
    const toggle = screen.getByRole('button', { name: 'Comparar um bot simulado com um bot real' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');

    await userEvent.click(toggle);

    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('heading', { level: 2, name: 'Comparação' })).toBeInTheDocument();
    expect(screen.getByTestId('compare-paper-select')).toHaveFocus();
    expect(await screen.findByTestId('compare-PAPER-total')).toHaveTextContent('+US$ 12,00');
    expect(api.comparePnl).toHaveBeenCalledWith(
      { paperBotId: PAPER_BOT_ID, realBotId: REAL_BOT_ID, period: { kind: 'ALL' } },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
  });

  it('each side in its own currency, the disclaimer only on the simulated side, no difference nor ranking', async () => {
    renderView({}, { paperBotId: PAPER_BOT_ID, realBotId: REAL_BOT_ID });

    expect(await screen.findByTestId('compare-REAL-total')).toHaveTextContent('+0,0312 SOL');
    expect(
      screen.getByRole('heading', { level: 3, name: /Simulado A, simulado, em dólares/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { level: 3, name: /Real B, real, em SOL/ }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('Total do bot simulado, em dólares')).toBeInTheDocument();
    expect(screen.getByLabelText('Total do bot real, em SOL')).toBeInTheDocument();
    expect(screen.getByTestId('compare-PAPER-disclaimer')).toBeInTheDocument();
    expect(screen.queryByTestId('compare-REAL-disclaimer')).toBeNull();
    expect(screen.getByTestId('compare-PAPER-created')).toHaveTextContent('Criado em 20/10');
    expect(screen.getByTestId('compare-currencies')).toHaveTextContent(
      'Os valores ficam cada um na sua moeda e não são comparados por conta.',
    );
    const sides = screen.getByTestId('compare-sides');
    expect(sides).not.toHaveTextContent(/total geral|diferença|melhor|vencedor|ranking/i);
    expect(screen.getByTestId('compare-REAL')).not.toHaveTextContent('US$');
    expect(screen.getByTestId('compare-PAPER')).not.toHaveTextContent('SOL');
  });

  it('the real select lists only the REAL bots of the same templateId, and is disabled without a simulated bot', async () => {
    renderView();
    await userEvent.click(screen.getByTestId('compare-toggle'));

    const real = await screen.findByTestId('compare-real-select');
    await waitFor(() =>
      expect(screen.getByTestId('compare-paper-select')).toHaveValue(PAPER_BOT_ID),
    );
    const options = within(real)
      .getAllByRole('option')
      .map((option) => option.textContent);
    expect(options).toEqual(['Escolha um bot', 'Real B']);
    expect(options).not.toContain('Real C');
  });

  it('says there is no pair when no PAPER bot has a REAL bot of its strategy', async () => {
    renderView(
      {
        listBots: jest.fn(async () => ({
          ...bots(),
          items: bots().items.filter((bot) => bot.botId !== REAL_BOT_ID),
        })),
      },
      { paperBotId: null, realBotId: null },
    );

    expect(await screen.findByTestId('compare-no-pair')).toHaveTextContent(
      'Para comparar, você precisa de um bot simulado e de um bot real da mesma estratégia.',
    );
    expect(screen.getByTestId('compare-real-select')).toBeDisabled();
  });

  it('reads the next pages of listBots before building the options', async () => {
    const listBots = jest
      .fn()
      .mockResolvedValueOnce({ ...bots(), items: bots().items.slice(0, 1), nextCursor: 'next' })
      .mockResolvedValueOnce({ ...bots(), items: bots().items.slice(1), nextCursor: null });
    const api = renderView({ listBots });
    await userEvent.click(screen.getByTestId('compare-toggle'));

    await waitFor(() => expect(screen.getByTestId('compare-real-select')).toHaveValue(REAL_BOT_ID));
    expect(listBots).toHaveBeenCalledTimes(2);
    expect(listBots.mock.calls[1]?.[0]).toEqual({ cursor: 'next', limit: 50 });
    expect(api.comparePnl).toHaveBeenCalledTimes(1);
  });

  it('a side UNAVAILABLE keeps the other one, and its "Tentar de novo" makes one C6 that updates only it', async () => {
    const unavailable: PnlComparison = {
      ...COMPARISON,
      real: {
        status: 'UNAVAILABLE',
        market: 'REAL',
        currency: 'SOL',
        code: 'SERVICE_UNAVAILABLE',
        correlationId: 'corr-r',
      },
    };
    const comparePnl = jest
      .fn()
      .mockResolvedValueOnce(unavailable)
      .mockResolvedValueOnce({
        ...COMPARISON,
        paper: {
          status: 'UNAVAILABLE',
          market: 'PAPER',
          currency: 'USD',
          code: 'X',
          correlationId: 'must-not-show',
        },
      });
    renderView({ comparePnl }, { paperBotId: PAPER_BOT_ID, realBotId: REAL_BOT_ID });

    expect(await screen.findByTestId('compare-REAL-code')).toHaveTextContent('corr-r');
    expect(screen.getByTestId('compare-PAPER-total')).toHaveTextContent('+US$ 12,00');
    await userEvent.click(screen.getByTestId('compare-REAL-retry'));

    expect(await screen.findByTestId('compare-REAL-total')).toHaveTextContent('+0,0312 SOL');
    expect(screen.getByTestId('compare-PAPER-total')).toHaveTextContent('+US$ 12,00');
    expect(screen.queryByText(/must-not-show/)).toBeNull();
    expect(comparePnl).toHaveBeenCalledTimes(2);
  });

  it('a 422 explains the strategies, clears the real bot and puts the focus on it, with no value', async () => {
    renderView(
      {
        comparePnl: jest.fn(async () =>
          Promise.reject(new ApiError(422, 'VALIDATION_FAILED', 'TEMPLATE_MISMATCH')),
        ),
      },
      { paperBotId: PAPER_BOT_ID, realBotId: OTHER_REAL_ID },
    );

    expect(await screen.findByTestId('compare-template-mismatch')).toHaveTextContent(
      'Esses dois bots usam estratégias diferentes. Escolha um bot real da mesma estratégia do bot simulado.',
    );
    await waitFor(() => expect(screen.getByTestId('compare-real-select')).toHaveFocus());
    expect(screen.getByTestId('compare-real-select')).toHaveValue('');
    expect(screen.getByTestId('compare-sides')).not.toHaveTextContent(/US\$|SOL/);
  });

  it('a 404 of a side says "Bot não encontrado." there, clears its select and gives it the focus', async () => {
    renderView(
      {
        comparePnl: jest.fn(async () => Promise.reject(new ApiError(404, 'NOT_FOUND', 'REAL_BOT'))),
      },
      { paperBotId: PAPER_BOT_ID, realBotId: REAL_BOT_ID },
    );

    expect(await screen.findByTestId('compare-REAL-not-found')).toHaveTextContent(
      'Bot não encontrado.',
    );
    await waitFor(() => expect(screen.getByTestId('compare-real-select')).toHaveFocus());
    expect(screen.getByTestId('compare-real-select')).toHaveValue('');
    expect(screen.queryByTestId('compare-PAPER-total')).toBeNull();
  });

  it('closing takes the comparison out of the address and gives the focus back to the button', async () => {
    renderView({}, { paperBotId: PAPER_BOT_ID, realBotId: REAL_BOT_ID });
    await screen.findByTestId('compare-PAPER-total');

    await userEvent.click(screen.getByTestId('compare-toggle'));

    expect(selections.at(-1)).toBeNull();
    expect(screen.getByTestId('compare-toggle')).toHaveFocus();
    expect(screen.getByTestId('compare-toggle')).toHaveAttribute('aria-expanded', 'false');
  });

  it('reads C6 with the applied pair of the period', async () => {
    const applied = applyPeriod('LAST_7', ZONE, new Date('2026-11-23T02:30:00Z'));
    if (!applied.ok) {
      throw new Error('a preset is valid');
    }
    const api = renderView(
      {},
      { paperBotId: PAPER_BOT_ID, realBotId: REAL_BOT_ID },
      applied.period,
    );

    await screen.findByTestId('compare-PAPER-total');
    expect(api.comparePnl).toHaveBeenCalledWith(
      expect.objectContaining({
        period: { kind: 'RANGE', from: '2026-11-16T03:00:00Z', to: '2026-11-23T03:00:00Z' },
      }),
      expect.anything(),
    );
  });

  it('the address goes and comes back; an invalid one opens empty', () => {
    const pair = { paperBotId: PAPER_BOT_ID, realBotId: REAL_BOT_ID };
    expect(parseComparison(comparisonAddress(pair))).toEqual(pair);
    expect(parseComparison(`${PAPER_BOT_ID},`)).toEqual({
      paperBotId: PAPER_BOT_ID,
      realBotId: null,
    });
    expect(parseComparison('abc,def')).toEqual({ paperBotId: null, realBotId: null });
    expect(parseComparison(null)).toBeNull();
  });

  it('an invalid address opens the region empty without a request to C6', async () => {
    const api = renderView({}, parseComparison('not-a-bot,also-not'));

    await screen.findByTestId('compare-paper-select');
    expect(api.comparePnl).not.toHaveBeenCalled();
  });
});
