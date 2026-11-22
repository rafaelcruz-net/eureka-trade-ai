import { render, screen } from '@testing-library/react';
import { ApiError } from '@/lib/api/client';
import { tokenStore } from '@/lib/auth/token-store';
import type { GuestApi } from '@/lib/api/guest-api';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { BotList } from '@/features/bots/BotList';
import { summary } from '@/features/bots/fixtures';
import { BY_MODE, PAPER_BOT_ID, REAL_BOT_ID } from '@/features/pnl/fixtures';

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({}),
}));

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
    renderList({
      getPnlByMode: jest.fn(async () => ({
        ...BY_MODE,
        real: {
          ...BY_MODE.real,
          bots:
            BY_MODE.real.status === 'AVAILABLE'
              ? BY_MODE.real.bots.map((bot) => ({
                  ...bot,
                  totalLamports: { status: 'UNKNOWN' as const, reason: 'PRICE_UNKNOWN' as const },
                }))
              : [],
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
