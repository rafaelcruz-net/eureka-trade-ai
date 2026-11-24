import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError, RequestCancelled } from '@/lib/api/client';
import { tokenStore } from '@/lib/auth/token-store';
import type { BotPage, GuestApi, PnlByMode } from '@/lib/api/guest-api';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { BotList } from '@/features/bots/BotList';
import { summary } from '@/features/bots/fixtures';
import {
  BY_MODE,
  PAPER_BLOCK,
  PAPER_BOT_ID,
  REAL_BLOCK,
  REAL_BOT_ID,
} from '@/features/pnl/fixtures';

const replace = jest.fn();
let search = new URLSearchParams();

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: replace }),
  useSearchParams: () => search,
  useParams: () => ({}),
}));

beforeEach(() => {
  replace.mockReset();
  search = new URLSearchParams();
});

const DRAFT_ID = 'b0700000-0000-4000-8000-0000000000d3';

function page() {
  return {
    items: [
      summary({
        botId: REAL_BOT_ID,
        name: 'Real B',
        market: 'REAL',
        mode: 'REAL',
        state: 'PAUSED',
        pauseReasons: ['PERSON'],
      }),
      summary({
        botId: PAPER_BOT_ID,
        name: 'Simulado A',
        market: 'PAPER',
        mode: 'PAPER',
        state: 'ACTIVE',
      }),
      summary({
        botId: DRAFT_ID,
        name: 'Rascunho',
        market: 'PAPER',
        mode: 'PAPER',
        state: 'DRAFT',
      }),
    ],
    nextCursor: null,
    activeCount: 1,
    activeLimit: 3,
    matchingCount: 3,
    totalCount: 3,
  };
}

function renderList(overrides: Partial<GuestApi> = {}) {
  tokenStore.set('access-token-value');
  const api = fakeApi({
    listBots: jest.fn(async () => page()),
    getPnlByMode: jest.fn(async () => BY_MODE),
    ...overrides,
  });
  render(
    <Providers api={api}>
      <BotList timeZone="America/Sao_Paulo" />
    </Providers>,
  );
  return api;
}

/**
 * 261121-paper-real-unico U1, Step 19 (US2.1, US2.2; BR4.2 to BR4.4, BR6.1, BR9.3; AC2.1.x, AC2.2.x): one list for both
 * modes, each line with its mode in text and its P&L in the currency of its mode, read from C4 without any arithmetic.
 */
describe('BotList with the mode', () => {
  it('shows PAPER and REAL bots together, each with its mode label in text', async () => {
    renderList();

    expect(await screen.findByTestId(`bots-mode-badge-${REAL_BOT_ID}`)).toHaveTextContent('REAL');
    expect(screen.getByTestId(`bots-mode-badge-${PAPER_BOT_ID}`)).toHaveTextContent('SIMULADO');
    expect(screen.getByTestId('bots-title')).toHaveTextContent('Meus bots');
  });

  it('the P&L of each bot is in the currency of its mode: US$ for PAPER, SOL for REAL', async () => {
    renderList();

    expect(await screen.findByTestId(`bots-pnl-${PAPER_BOT_ID}`)).toHaveTextContent('+US$ 12,00');
    expect(await screen.findByTestId(`bots-pnl-${REAL_BOT_ID}`)).toHaveTextContent('+0,0312 SOL');
    expect(screen.getByTestId(`bots-pnl-${PAPER_BOT_ID}`)).not.toHaveTextContent('SOL');
    expect(screen.getByTestId(`bots-pnl-${REAL_BOT_ID}`)).not.toHaveTextContent('US$');
  });

  it('a draft shows a dash and the disclaimer appears above a list with a simulated bot', async () => {
    renderList();

    expect(await screen.findByTestId(`bots-pnl-${DRAFT_ID}`)).toHaveTextContent('—');
    expect(screen.getByTestId('bots-disclaimer')).toHaveTextContent(
      'O resultado simulado não garante o resultado real.',
    );
  });

  it('an unavailable REAL block leaves only the REAL lines "P&L indisponível agora"', async () => {
    renderList({
      getPnlByMode: jest.fn(async () => ({
        ...BY_MODE,
        real: {
          status: 'UNAVAILABLE' as const,
          market: 'REAL' as const,
          currency: 'SOL' as const,
          code: 'SERVICE_UNAVAILABLE',
          correlationId: 'corr-1',
        },
      })),
    });

    expect(await screen.findByTestId(`bots-pnl-${REAL_BOT_ID}`)).toHaveTextContent(
      'P&L indisponível agora',
    );
    expect(screen.getByTestId(`bots-pnl-${PAPER_BOT_ID}`)).toHaveTextContent('+US$ 12,00');
  });

  it('a failed P&L never blocks the list', async () => {
    renderList({
      getPnlByMode: jest.fn(async () => {
        throw new ApiError(503, 'SERVICE_UNAVAILABLE');
      }),
    });

    expect(await screen.findByTestId(`bots-row-${PAPER_BOT_ID}`)).toBeInTheDocument();
    expect(await screen.findByTestId(`bots-pnl-${PAPER_BOT_ID}`)).toHaveTextContent(
      'P&L indisponível agora',
    );
  });

  it('an unknown P&L says "desconhecido" with its reason, never zero', async () => {
    const real = BY_MODE.real;
    if (real === undefined || real.status !== 'AVAILABLE') {
      throw new Error('the fixture BY_MODE has an AVAILABLE REAL block');
    }
    renderList({
      getPnlByMode: jest.fn(async () => ({
        ...BY_MODE,
        real: {
          ...real,
          bots: real.bots.map((bot) => ({
            ...bot,
            totalLamports: { status: 'UNKNOWN' as const, reason: 'PRICE_UNKNOWN' as const },
          })),
        },
      })),
    });

    expect(await screen.findByTestId(`bots-pnl-${REAL_BOT_ID}`)).toHaveTextContent(
      'desconhecido (sem preço)',
    );
  });

  it('a list with only REAL bots shows no simulated disclaimer', async () => {
    renderList({
      listBots: jest.fn(async () => ({ ...page(), items: [page().items[0]!] })),
    });

    expect(await screen.findByTestId(`bots-row-${REAL_BOT_ID}`)).toBeInTheDocument();
    expect(screen.queryByTestId('bots-disclaimer')).toBeNull();
  });
});

/** The answer of listBots for a market, as the server filters it (the screen never filters). */
function pageOf(market?: 'PAPER' | 'REAL'): BotPage {
  const all = page();
  const items = market ? all.items.filter((bot) => bot.market === market) : all.items;
  return { ...all, items, matchingCount: items.length, totalCount: all.items.length };
}

/** C4 as the server answers it: with a market, the key of the other block is absent. */
function byModeOf(market?: 'PAPER' | 'REAL'): PnlByMode {
  if (market === 'PAPER') {
    return { period: { kind: 'ALL' }, paper: PAPER_BLOCK };
  }
  if (market === 'REAL') {
    return { period: { kind: 'ALL' }, real: REAL_BLOCK };
  }
  return BY_MODE;
}

function filteredApi(overrides: Partial<GuestApi> = {}) {
  return renderList({
    listBots: jest.fn(async (request: { market?: 'PAPER' | 'REAL' } = {}) =>
      pageOf(request.market),
    ) as unknown as GuestApi['listBots'],
    getPnlByMode: jest.fn(async (request: { market?: 'PAPER' | 'REAL' } = {}) =>
      byModeOf(request.market),
    ) as unknown as GuestApi['getPnlByMode'],
    ...overrides,
  });
}

/**
 * 261121-paper-real-unico U3, Step 17 (BR1.1 to BR1.7, BR3.2, BR6.3; AC2.3.1 to AC2.3.5): the filter by mode, applied
 * by the server, in the address, with one listBots and at most one C4 per change.
 */
describe('BotList with the filter by mode', () => {
  it('starts on Todos with the focus on its radio and reads both without market', async () => {
    const api = filteredApi();

    expect(await screen.findByTestId('bots-mode-filter-ALL')).toHaveFocus();
    expect(api.listBots).toHaveBeenCalledWith(
      { state: undefined, market: undefined },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
    expect(api.getPnlByMode).toHaveBeenCalledWith(
      { market: undefined },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
  });

  it('a change makes one listBots and one C4 with the same market, and replaces the address', async () => {
    const api = filteredApi();
    await screen.findByTestId(`bots-row-${PAPER_BOT_ID}`);
    const lists = api.listBots.mock.calls.length;
    const pnls = api.getPnlByMode.mock.calls.length;

    await userEvent.click(screen.getByTestId('bots-mode-filter-REAL'));

    await screen.findByText('Mostrando 1 de 3 bots.');
    expect(api.listBots).toHaveBeenCalledTimes(lists + 1);
    expect(api.getPnlByMode).toHaveBeenCalledTimes(pnls + 1);
    expect(api.listBots).toHaveBeenLastCalledWith(
      { state: undefined, market: 'REAL' },
      expect.anything(),
    );
    expect(api.getPnlByMode).toHaveBeenLastCalledWith({ market: 'REAL' }, expect.anything());
    expect(replace).toHaveBeenCalledWith('/bots?mode=REAL');
    // No bot of another mode with the filter checked, no disclaimer with Real, the P&L from the REAL block only.
    expect(screen.queryByTestId(`bots-row-${PAPER_BOT_ID}`)).toBeNull();
    expect(screen.queryByTestId('bots-disclaimer')).toBeNull();
    expect(screen.getByTestId(`bots-pnl-${REAL_BOT_ID}`)).toHaveTextContent('+0,0312 SOL');
    expect(screen.getByRole('main')).not.toHaveTextContent(/total geral/i);
  });

  it('Todos takes the mode out of the address', async () => {
    search = new URLSearchParams('mode=PAPER');
    filteredApi();
    await screen.findByText('Mostrando 2 de 3 bots.');

    await userEvent.click(screen.getByTestId('bots-mode-filter-ALL'));

    expect(replace).toHaveBeenLastCalledWith('/bots');
  });

  it('?mode=PAPER opens on Simulado with the disclaimer and only PAPER bots', async () => {
    search = new URLSearchParams('mode=PAPER');
    const api = filteredApi();

    expect(await screen.findByTestId(`bots-row-${PAPER_BOT_ID}`)).toBeInTheDocument();
    expect(screen.getByTestId('bots-mode-filter-PAPER')).toBeChecked();
    expect(screen.queryByTestId(`bots-row-${REAL_BOT_ID}`)).toBeNull();
    expect(screen.getByTestId('bots-disclaimer')).toBeInTheDocument();
    expect(api.listBots).toHaveBeenCalledTimes(1);
  });

  it('an invalid ?mode is Todos, leaves the address and makes no request of its own', async () => {
    search = new URLSearchParams('mode=simulado');
    const api = filteredApi();

    expect(await screen.findByTestId(`bots-row-${REAL_BOT_ID}`)).toBeInTheDocument();
    expect(screen.getByTestId('bots-mode-filter-ALL')).toBeChecked();
    expect(replace).toHaveBeenCalledWith('/bots');
    expect(api.listBots).toHaveBeenCalledTimes(1);
    expect(api.listBots).toHaveBeenCalledWith(
      { state: undefined, market: undefined },
      expect.anything(),
    );
  });

  it('the two empty texts by filter differ, and "Ver todos os bots" goes back to Todos with the focus', async () => {
    const empty = (market?: 'PAPER' | 'REAL'): BotPage => ({
      ...page(),
      items: market ? [] : page().items,
      matchingCount: market ? 0 : 3,
      totalCount: 3,
    });
    search = new URLSearchParams('mode=REAL');
    filteredApi({
      listBots: jest.fn(async (request: { market?: 'PAPER' | 'REAL' } = {}) =>
        empty(request.market),
      ) as unknown as GuestApi['listBots'],
    });

    const real = await screen.findByTestId('bots-empty-mode');
    expect(real).toHaveTextContent('Você não tem bots reais.');
    await userEvent.click(screen.getByTestId('bots-mode-filter-PAPER'));
    await waitFor(() =>
      expect(screen.getByTestId('bots-empty-mode')).toHaveTextContent(
        'Você não tem bots simulados.',
      ),
    );
    expect(screen.queryByTestId('bots-empty')).toBeNull();

    await userEvent.click(screen.getByTestId('bots-show-all'));

    expect(await screen.findByTestId(`bots-row-${REAL_BOT_ID}`)).toBeInTheDocument();
    expect(screen.getByTestId('bots-mode-filter-ALL')).toBeChecked();
    expect(screen.getByTestId('bots-mode-filter-ALL')).toHaveFocus();
    expect(replace).toHaveBeenLastCalledWith('/bots');
  });

  it('a person without any bot sees "Você ainda não tem bots." and no filter', async () => {
    renderList({
      listBots: jest.fn(async () => ({
        ...page(),
        items: [],
        matchingCount: 0,
        totalCount: 0,
      })),
    });

    expect(await screen.findByTestId('bots-empty')).toHaveTextContent('Você ainda não tem bots.');
    expect(screen.queryByTestId('bots-mode-filter')).toBeNull();
    expect(screen.queryByTestId('bots-disclaimer')).toBeNull();
  });

  it('an older answer is discarded: only the answer of the last request reaches the screen', async () => {
    let releaseFirst: (value: BotPage) => void = () => undefined;
    const listBots = jest.fn((request: { market?: 'PAPER' | 'REAL' } = {}, options = {}) => {
      if (request.market === undefined && listBots.mock.calls.length === 1) {
        return new Promise<BotPage>((resolve, reject) => {
          releaseFirst = resolve;
          (options as { signal?: AbortSignal }).signal?.addEventListener('abort', () =>
            reject(new RequestCancelled()),
          );
        });
      }
      return Promise.resolve(pageOf(request.market));
    });
    filteredApi({ listBots: listBots as unknown as GuestApi['listBots'] });
    await waitFor(() => expect(listBots).toHaveBeenCalledTimes(1));
    // The filter is not mounted before the first answer; the address carries the change instead.
    releaseFirst(pageOf());
    await screen.findByTestId(`bots-row-${PAPER_BOT_ID}`);

    await userEvent.click(screen.getByTestId('bots-mode-filter-PAPER'));

    await screen.findByText('Mostrando 2 de 3 bots.');
    expect(screen.queryByTestId(`bots-row-${REAL_BOT_ID}`)).toBeNull();
  });

  it('"Tentar de novo" of a failed P&L reads only C4 again', async () => {
    const getPnlByMode = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
      .mockResolvedValue(BY_MODE);
    const api = filteredApi({ getPnlByMode });
    await screen.findByTestId('bots-pnl-failed');
    const lists = api.listBots.mock.calls.length;

    await userEvent.click(screen.getByTestId('bots-pnl-retry'));

    expect(await screen.findByTestId(`bots-pnl-${PAPER_BOT_ID}`)).toHaveTextContent('+US$ 12,00');
    expect(getPnlByMode).toHaveBeenCalledTimes(2);
    expect(api.listBots).toHaveBeenCalledTimes(lists);
  });

  it('keeps the order of the API in every filter and shows no total', async () => {
    filteredApi();

    await screen.findByTestId(`bots-row-${REAL_BOT_ID}`);
    const rows = screen.getAllByTestId(/^bots-row-/).map((row) => row.getAttribute('data-testid'));
    expect(rows).toEqual(page().items.map((bot) => `bots-row-${bot.botId}`));
    expect(screen.getByRole('main')).not.toHaveTextContent(/total/i);
  });
});
