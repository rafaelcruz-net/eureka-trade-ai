import { act, render, screen } from '@testing-library/react';
import { tokenStore } from '@/lib/auth/token-store';
import type { GuestApi, PnlByMode } from '@/lib/api/guest-api';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { resetWalletCache } from '@/components/shell/WalletBadge';
import { DashboardScreen } from '@/features/dashboard/DashboardScreen';
import { LiveChannelProvider } from '@/features/live/LiveChannelProvider';
import {
  botPage,
  fakeChannel,
  fakeFetch,
  reconciliation,
  sseBlock,
  ZONE,
} from '@/features/dashboard/test-support';
import { BY_MODE, PAPER_BLOCK, REAL_BLOCK } from '@/features/pnl/fixtures';

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => '/',
}));

const NOW = new Date('2026-10-28T12:00:00Z');

/** C4 as the server answers it: with a market, only that block. */
function byMode(request: { market?: 'PAPER' | 'REAL' } = {}): PnlByMode {
  if (request.market === 'PAPER') {
    return { period: { kind: 'ALL' }, paper: PAPER_BLOCK };
  }
  if (request.market === 'REAL') {
    return { period: { kind: 'ALL' }, real: REAL_BLOCK };
  }
  return BY_MODE;
}

async function renderCards(overrides: Partial<GuestApi> = {}) {
  jest.useFakeTimers({ now: NOW });
  const channel = fakeChannel();
  const net = fakeFetch();
  net.respond(channel.response);
  const api = fakeApi({
    listBots: jest.fn(async () => botPage([])),
    getReconciliation: jest.fn(async () => reconciliation()),
    getPnlByMode: jest.fn(async (request: { market?: 'PAPER' | 'REAL' } = {}) =>
      byMode(request),
    ) as unknown as GuestApi['getPnlByMode'],
    ...overrides,
  });
  tokenStore.set('access-token-value');
  render(
    <Providers api={api}>
      <LiveChannelProvider fetchImpl={net.impl}>
        <DashboardScreen now={() => new Date(Date.now())} timeZone={ZONE} />
      </LiveChannelProvider>
    </Providers>,
  );
  await act(async () => {
    await jest.advanceTimersByTimeAsync(10);
  });
  return { api, channel };
}

function callsWith(api: jest.Mocked<GuestApi>, market?: 'PAPER' | 'REAL') {
  return api.getPnlByMode.mock.calls.filter(
    ([request]) => (request as { market?: string } | undefined)?.market === market,
  ).length;
}

async function push(channel: ReturnType<typeof fakeChannel>, block: string) {
  await act(async () => {
    channel.push(block);
    await jest.advanceTimersByTimeAsync(10);
  });
}

const orderState = (state: string) =>
  sseBlock('real.order.state', {
    type: 'REAL_ORDER_STATE',
    realOrderId: '7a1d5b60-0000-4000-8000-0000000000d1',
    state,
    at: NOW.toISOString(),
  });

beforeEach(() => {
  resetWalletCache();
});

afterEach(() => {
  jest.useRealTimers();
  tokenStore.clear();
  jest.restoreAllMocks();
});

/** 261121-paper-real-unico U3, Step 23 (BR7.1 to BR7.5, BR3.2, BR3.4, BR9.2; AC4.3.1 to AC4.3.4, AC4.2.2). */
describe('DashboardModeCards: the simulated card', () => {
  it('pnl.updated reads only the simulated card again, with its market, without going back to loading', async () => {
    const { api, channel } = await renderCards();
    expect(screen.getByTestId('pnl-compact-PAPER-total')).toHaveTextContent('+US$ 12,00');

    await push(channel, sseBlock('pnl.updated', { ledgerMark: 7 }));

    expect(callsWith(api, 'PAPER')).toBe(1);
    expect(callsWith(api, 'REAL')).toBe(0);
    expect(screen.queryByTestId('pnl-compact-PAPER-loading')).toBeNull();
  });

  it('two events within 30 s make one reading at once and one at the end of the window', async () => {
    const { api, channel } = await renderCards();

    await push(channel, sseBlock('pnl.updated', { ledgerMark: 7 }));
    await push(channel, sseBlock('pnl.updated', { ledgerMark: 8 }));
    expect(callsWith(api, 'PAPER')).toBe(1);

    await act(async () => {
      await jest.advanceTimersByTimeAsync(30_000);
    });
    expect(callsWith(api, 'PAPER')).toBe(2);
  });

  it('the first load is never announced; a reading by event is, at most once every 30 s', async () => {
    const { channel } = await renderCards();
    expect(screen.getByTestId('pnl-compact-PAPER-total-region')).toHaveAttribute(
      'aria-live',
      'off',
    );

    await push(channel, sseBlock('pnl.updated', { ledgerMark: 7 }));
    expect(screen.getByTestId('pnl-compact-PAPER-total-region')).toHaveAttribute(
      'aria-live',
      'polite',
    );
    expect(screen.getByTestId('pnl-compact-REAL-total-region')).toHaveAttribute('aria-live', 'off');
  });

  it('the disclaimer only on the simulated card with a value', async () => {
    await renderCards();

    expect(screen.getByTestId('pnl-compact-PAPER-disclaimer')).toBeInTheDocument();
    expect(screen.queryByTestId('pnl-compact-REAL-disclaimer')).toBeNull();
    expect(screen.getByTestId('dashboard-mode-cards')).not.toHaveTextContent(/total geral/i);
  });
});

describe('DashboardModeCards: the real card', () => {
  it.each(['CONFIRMED', 'FAILED'])(
    'real.order.state %s reads only the real card again',
    async (state) => {
      const { api, channel } = await renderCards();

      await push(channel, orderState(state));

      expect(callsWith(api, 'REAL')).toBe(1);
      expect(callsWith(api, 'PAPER')).toBe(0);
    },
  );

  it('real.conditional.state reads only the real card again', async () => {
    const { api, channel } = await renderCards();

    await push(
      channel,
      sseBlock('real.conditional.state', {
        type: 'REAL_CONDITIONAL_STATE',
        conditionalOrderId: '7a1d5b60-0000-4000-8000-0000000000e1',
        positionMint: 'MintCccc3333333333333333333333333333333333',
        state: 'FILLED',
        filledSide: 'STOP',
        at: NOW.toISOString(),
      }),
    );

    expect(callsWith(api, 'REAL')).toBe(1);
    expect(callsWith(api, 'PAPER')).toBe(0);
  });

  it('SENT and real.signing.requested never read a card again', async () => {
    const { api, channel } = await renderCards();

    await push(channel, orderState('SENT'));
    await push(
      channel,
      sseBlock('real.signing.requested', {
        type: 'REAL_SIGNING_REQUESTED',
        realOrderId: '7a1d5b60-0000-4000-8000-0000000000d2',
        deadline: NOW.toISOString(),
      }),
    );
    await act(async () => {
      await jest.advanceTimersByTimeAsync(31_000);
    });

    expect(callsWith(api, 'REAL')).toBe(0);
    expect(callsWith(api, 'PAPER')).toBe(0);
  });

  it.each([
    [{ available: true }, 'pnl-compact-REAL-empty-create'],
    [{ available: false, reason: 'WALLET_INACTIVE' as const }, 'pnl-compact-REAL-empty-activate'],
    [{ available: false, reason: 'NOT_RELEASED' as const }, 'pnl-compact-REAL-empty-reason'],
  ])('the empty real card with C2 %o shows its way', async (eligibility, testId) => {
    await renderCards({
      getRealEligibility: jest.fn(async () => eligibility),
      getPnlByMode: fakeApi().getPnlByMode,
    });

    expect(screen.getByTestId('pnl-compact-REAL-empty')).toHaveTextContent('Sem bots reais.');
    expect(screen.getByTestId(testId)).toBeInTheDocument();
  });

  it('the empty real card without C2 has no link', async () => {
    await renderCards({
      getRealEligibility: jest.fn(async () => Promise.reject(new Error('503'))),
      getPnlByMode: fakeApi().getPnlByMode,
    });

    const empty = screen.getByTestId('pnl-compact-REAL-empty');
    expect(empty.querySelector('a')).toBeNull();
  });

  it('a failed card has its code and its own "Tentar de novo"; the other card keeps its value', async () => {
    const getPnlByMode = jest
      .fn()
      .mockResolvedValueOnce({
        ...BY_MODE,
        real: {
          status: 'UNAVAILABLE',
          market: 'REAL',
          currency: 'SOL',
          code: 'SERVICE_UNAVAILABLE',
          correlationId: 'corr-d',
        },
      })
      .mockResolvedValueOnce({ period: { kind: 'ALL' }, real: REAL_BLOCK });
    await renderCards({ getPnlByMode });

    expect(screen.getByTestId('pnl-compact-REAL-code')).toHaveTextContent('corr-d');
    expect(screen.getByTestId('pnl-compact-PAPER-total')).toHaveTextContent('+US$ 12,00');
    await act(async () => {
      screen.getByTestId('pnl-compact-REAL-retry').click();
      await jest.advanceTimersByTimeAsync(10);
    });

    expect(screen.getByTestId('pnl-compact-REAL-total')).toHaveTextContent('+0,0312 SOL');
    expect(getPnlByMode).toHaveBeenLastCalledWith(
      { market: 'REAL', period: { kind: 'ALL' } },
      expect.anything(),
    );
    expect(screen.getByTestId('pnl-compact-REAL-title')).toHaveFocus();
  });
});
