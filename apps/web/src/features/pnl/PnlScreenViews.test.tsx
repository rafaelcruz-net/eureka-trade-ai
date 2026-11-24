import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RequestCancelled } from '@/lib/api/client';
import { tokenStore } from '@/lib/auth/token-store';
import type { GuestApi, PnlByMode } from '@/lib/api/guest-api';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { summary } from '@/features/bots/fixtures';
import { PnlScreen } from '@/features/pnl/PnlScreen';
import {
  BY_MODE,
  COMPARISON,
  ONLY_REAL,
  PAPER_BOT_ID,
  REAL_BLOCK,
  REAL_BOT_ID,
  REPORT,
  TEMPLATE_ID,
} from '@/features/pnl/fixtures';

const replace = jest.fn();
let search = new URLSearchParams();

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: replace }),
  useSearchParams: () => search,
}));

const ZONE = 'America/Sao_Paulo';
// 2026-11-23T02:30Z is still the 22nd in São Paulo.
const NOW = new Date('2026-11-23T02:30:00Z');

function renderScreen(overrides: Partial<GuestApi> = {}, now = NOW) {
  tokenStore.set('access-token-value');
  const api = fakeApi({
    getPnlByMode: jest.fn(async () => BY_MODE),
    getPnl: jest.fn(async () => REPORT),
    listBots: jest.fn(async () => ({
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
      ],
      nextCursor: null,
      activeCount: 2,
      activeLimit: 3,
      matchingCount: 2,
      totalCount: 2,
    })),
    comparePnl: jest.fn(async () => COMPARISON),
    ...overrides,
  });
  const view = render(
    <Providers api={api}>
      <PnlScreen timeZone={ZONE} now={now} />
    </Providers>,
  );
  return { api, ...view };
}

beforeEach(() => {
  replace.mockReset();
  tokenStore.clear();
  search = new URLSearchParams();
});

/** 261121-paper-real-unico U3, Step 19 (BR2.1, BR2.4, BR2.6, BR2.9, BR3.2 to BR3.4, BR6.2; AC7.1.x, AC7.2.3). */
describe('PnlScreen with the period, the retry by block and the comparison', () => {
  it('opens on "Desde o início": one C4 without market, no request to GET /pnl and the note in its place', async () => {
    const { api } = renderScreen();

    expect(await screen.findByTestId('pnl-block-PAPER-total')).toHaveTextContent('+US$ 12,00');
    expect(api.getPnlByMode).toHaveBeenCalledTimes(1);
    expect(api.getPnlByMode).toHaveBeenCalledWith(
      { period: { kind: 'ALL' } },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
    expect(api.getPnl).not.toHaveBeenCalled();
    expect(screen.getByTestId('pnl-by-token-note')).toHaveTextContent(
      'Escolha um período para ver o resultado por token',
    );
    expect(screen.getByRole('radio', { name: 'Desde o início' })).toBeChecked();
  });

  it('a change of period makes one C4 without market with RANGE and one GET /pnl with the same pair', async () => {
    const { api } = renderScreen();
    await screen.findByTestId('pnl-block-PAPER-total');

    await userEvent.click(screen.getByRole('radio', { name: 'Últimos 7 dias' }));

    const pair = { from: '2026-11-16T03:00:00Z', to: '2026-11-23T03:00:00Z' };
    await waitFor(() => expect(api.getPnlByMode).toHaveBeenCalledTimes(2));
    expect(api.getPnlByMode).toHaveBeenLastCalledWith(
      { period: { kind: 'RANGE', ...pair } },
      expect.anything(),
    );
    await waitFor(() => expect(api.getPnl).toHaveBeenCalledTimes(1));
    expect(api.getPnl).toHaveBeenCalledWith(pair, expect.objectContaining({ retry: false }));
    expect(replace).toHaveBeenLastCalledWith('/pnl?periodo=7d');
    expect(await screen.findByTestId('pnl-total-realized')).toBeInTheDocument();
  });

  it('with the comparison open a period change also reads C6 once with the same pair', async () => {
    search = new URLSearchParams(`comparar=${PAPER_BOT_ID},${REAL_BOT_ID}`);
    const { api } = renderScreen();
    await screen.findByTestId('compare-PAPER-total');
    expect(api.comparePnl).toHaveBeenCalledTimes(1);

    await userEvent.click(screen.getByRole('radio', { name: 'Hoje' }));

    await waitFor(() => expect(api.comparePnl).toHaveBeenCalledTimes(2));
    expect(api.comparePnl).toHaveBeenLastCalledWith(
      {
        paperBotId: PAPER_BOT_ID,
        realBotId: REAL_BOT_ID,
        period: { kind: 'RANGE', from: '2026-11-22T03:00:00Z', to: '2026-11-23T03:00:00Z' },
      },
      expect.anything(),
    );
    expect(replace).toHaveBeenLastCalledWith(
      `/pnl?periodo=hoje&comparar=${PAPER_BOT_ID}%2C${REAL_BOT_ID}`,
    );
  });

  it('"Tentar de novo" makes one request with the market; the other block keeps its text; the focus goes to the block', async () => {
    const getPnlByMode = jest
      .fn()
      .mockResolvedValueOnce({
        ...BY_MODE,
        real: {
          status: 'UNAVAILABLE',
          market: 'REAL',
          currency: 'SOL',
          code: 'SERVICE_UNAVAILABLE',
          correlationId: 'corr-r',
        },
      } satisfies PnlByMode)
      .mockResolvedValueOnce(ONLY_REAL);
    renderScreen({ getPnlByMode });
    await screen.findByTestId('pnl-block-REAL-error');
    const paperText = screen.getByTestId('pnl-block-PAPER').textContent;

    await userEvent.click(screen.getByTestId('pnl-block-REAL-retry'));

    expect(await screen.findByTestId('pnl-block-REAL-total')).toHaveTextContent('+0,0312 SOL');
    expect(getPnlByMode).toHaveBeenCalledTimes(2);
    expect(getPnlByMode).toHaveBeenLastCalledWith(
      { market: 'REAL', period: { kind: 'ALL' } },
      expect.anything(),
    );
    expect(screen.getByTestId('pnl-block-PAPER').textContent).toBe(paperText);
    await waitFor(() => expect(screen.getByTestId('pnl-block-REAL-title')).toHaveFocus());
  });

  it('an address with a future interval opens on "Desde o início" without any request with RANGE', async () => {
    search = new URLSearchParams('periodo=intervalo&de=2026-12-01&ate=2026-12-05');
    const { api } = renderScreen();

    await screen.findByTestId('pnl-block-PAPER-total');
    expect(screen.getByRole('radio', { name: 'Desde o início' })).toBeChecked();
    expect(api.getPnlByMode).toHaveBeenCalledWith({ period: { kind: 'ALL' } }, expect.anything());
    expect(api.getPnl).not.toHaveBeenCalled();
  });

  it('a pair changed after the local midnight reads C6 with the same from and to as the blocks', async () => {
    search = new URLSearchParams('periodo=hoje');
    // The screen opened on the 22nd; the selection changes after midnight, the applied pair stays the same.
    const { api } = renderScreen({}, NOW);
    await screen.findByTestId('pnl-block-PAPER-total');
    const blocks = (api.getPnlByMode.mock.calls[0]?.[0] as { period: unknown }).period;

    await userEvent.click(screen.getByTestId('compare-toggle'));

    await waitFor(() => expect(api.comparePnl).toHaveBeenCalledTimes(1));
    expect((api.comparePnl.mock.calls[0]?.[0] as { period: unknown }).period).toEqual(blocks);
    expect(blocks).toEqual({
      kind: 'RANGE',
      from: '2026-11-22T03:00:00Z',
      to: '2026-11-23T03:00:00Z',
    });
  });

  it('an older answer is discarded and a cancelled request is ignored', async () => {
    let releaseFirst: (value: PnlByMode) => void = () => undefined;
    const getPnlByMode = jest
      .fn()
      .mockImplementationOnce(
        (_request: unknown, options: { signal?: AbortSignal }) =>
          new Promise<PnlByMode>((resolve, reject) => {
            releaseFirst = resolve;
            options.signal?.addEventListener('abort', () => reject(new RequestCancelled()));
          }),
      )
      .mockResolvedValue({
        ...BY_MODE,
        real: { ...REAL_BLOCK, totalLamports: { status: 'KNOWN' as const, value: '1000000000' } },
      });
    renderScreen({ getPnlByMode });

    await userEvent.click(screen.getByRole('radio', { name: 'Últimos 30 dias' }));
    releaseFirst(BY_MODE);

    expect(await screen.findByTestId('pnl-block-REAL-total')).toHaveTextContent('+1,00 SOL');
    expect(screen.queryByTestId('pnl-block-REAL-error')).toBeNull();
  });

  it('never shows a general total', async () => {
    renderScreen();

    await screen.findByTestId('pnl-block-REAL-total');
    expect(screen.getByRole('main')).not.toHaveTextContent(/total geral/i);
  });
});
