import { act, fireEvent, render, screen } from '@testing-library/react';
import type { GuestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { settle } from '@/features/dashboard/test-support';
import {
  abbreviateAddress,
  DepositWithdrawScreen,
} from '@/features/real/screens/DepositWithdrawScreen';
import { realMessages as text } from '@/features/real/messages';
import { resetSessionReal } from '@/features/real/session-real';
import { TRACKING_INTERVAL_MS, TRACKING_LIMIT_MS } from '@/features/real/hooks/useTracking';
import {
  BOT_ADDRESS,
  browserStorageText,
  MAIN_ADDRESS,
  MINT,
  pnlDto,
  problem,
  RealProviders,
  realApi,
  walletDto,
  withdrawalDto,
} from '@/features/real/test-support';
import RealDepositWithdrawPage from '@/app/real/deposito-saque/page';

/**
 * 261113-trading-real-bot U6, Step 14, written after the code (R6; US2.2, US5.1, US5.2; BR1.4, BR6; S21 to S25, S42,
 * S44; U6-NFR3.4, U6-NFR4.4, U6-NFR4.6, U6-NFR7.4, U6-NFR7.6; team.md "saque só para a carteira principal" and "novo
 * aceite dos termos"): the deposit address and the withdrawal, always visible and enabled.
 */
jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => '/real/deposito-saque',
}));

const MAIN_SHORT = abbreviateAddress(MAIN_ADDRESS);

async function renderScreen(api: jest.Mocked<GuestApi> = realApi(), newKey?: () => string) {
  tokenStore.set('access-token-value');
  render(
    <RealProviders api={api}>
      <DepositWithdrawScreen newKey={newKey} />
    </RealProviders>,
  );
  await act(async () => settle(20));
  return api;
}

async function click(testId: string) {
  await act(async () => {
    fireEvent.click(screen.getByTestId(testId));
  });
  await act(async () => settle(20));
}

beforeEach(() => resetSessionReal());
afterEach(() => {
  jest.useRealTimers();
  tokenStore.clear();
});

describe('R6 deposit', () => {
  it('the page shows the deposit address with "Copiar" and the note of the cap', async () => {
    tokenStore.set('access-token-value');
    render(
      <RealProviders api={realApi()}>
        <RealDepositWithdrawPage />
      </RealProviders>,
    );
    await act(async () => settle(20));

    expect(screen.getByTestId('real-deposit-address')).toHaveTextContent(BOT_ADDRESS);
    expect(screen.getByTestId('real-deposit')).toHaveTextContent(text.depositWithdraw.depositNote);
    expect(screen.getByTestId('real-deposit-copy')).toHaveTextContent('Copiar');
  });

  it('works with the REAL off and without any release: the release is never needed here (BR6.4)', async () => {
    const api = await renderScreen(
      realApi({
        getRealRelease: jest.fn(async () => {
          throw problem(403, 'REAL_DISABLED');
        }),
      }),
    );

    expect(screen.queryByTestId('real-unavailable')).toBeNull();
    expect(screen.getByTestId('real-withdraw-submit')).toBeEnabled();
    expect(api.getBotWallet).toHaveBeenCalled();
  });
});

describe('R6 withdrawal', () => {
  it('the destination is the main wallet, abbreviated, without any field to edit it (BR6.5, U6-NFR1.8)', async () => {
    await renderScreen();

    expect(screen.getByTestId('real-withdraw-destination')).toHaveTextContent(
      `Sacar para: ${MAIN_SHORT}`,
    );
    expect(screen.getByTestId('real-withdraw')).not.toHaveTextContent(MAIN_ADDRESS);
    expect(screen.getAllByRole('textbox')).toHaveLength(1);
    expect(screen.getByTestId('real-withdraw-available')).toHaveTextContent('1,250000000 SOL');
    expect(screen.getByTestId('real-withdraw-reserved')).toHaveTextContent('0,200000000 SOL');
  });

  it('confirms with amount and destination, sends the lamports and follows the state (S24, BR6.1, U6-NFR3.4)', async () => {
    jest.useFakeTimers({ advanceTimers: true });
    const requestRealWithdrawal = jest.fn(async () => withdrawalDto());
    const getRealWithdrawal = jest
      .fn()
      .mockResolvedValueOnce(withdrawalDto({ state: 'SENT' }))
      .mockResolvedValue(withdrawalDto({ state: 'CONFIRMED' }));
    await renderScreen(realApi({ requestRealWithdrawal, getRealWithdrawal }));
    fireEvent.change(screen.getByLabelText(text.depositWithdraw.amountLabel), {
      target: { value: '0,5' },
    });
    await click('real-withdraw-submit');

    expect(screen.getByTestId('real-withdraw-confirm-amount')).toHaveTextContent('0,500000000 SOL');
    expect(screen.getByTestId('real-withdraw-confirm-destination')).toHaveTextContent(MAIN_SHORT);
    expect(screen.getByTestId('real-withdraw-confirm-cancel')).toHaveFocus();
    await click('real-withdraw-confirm-submit');
    expect(requestRealWithdrawal).toHaveBeenCalledWith(
      { amount: { lamports: '500000000' } },
      expect.any(String),
    );

    await act(async () => jest.advanceTimersByTimeAsync(TRACKING_INTERVAL_MS));
    expect(screen.getByTestId('real-withdraw-state')).toHaveTextContent('Saque: Enviado');
    await act(async () => jest.advanceTimersByTimeAsync(TRACKING_INTERVAL_MS));
    expect(screen.getByTestId('real-withdraw-state')).toHaveTextContent('Saque: Confirmado');
    expect(browserStorageText()).not.toMatch(/500000000|Main|Bot/);
  });

  it('two clicks on "Confirmar" send ONE request (S24, U6-NFR7.6)', async () => {
    let resolve: (value: ReturnType<typeof withdrawalDto>) => void = () => undefined;
    const requestRealWithdrawal = jest.fn(
      () => new Promise<ReturnType<typeof withdrawalDto>>((done) => (resolve = done)),
    );
    await renderScreen(realApi({ requestRealWithdrawal }));
    await click('real-withdraw-all');

    fireEvent.click(screen.getByTestId('real-withdraw-confirm-submit'));
    fireEvent.click(screen.getByTestId('real-withdraw-confirm-submit'));

    expect(requestRealWithdrawal).toHaveBeenCalledTimes(1);
    expect(requestRealWithdrawal).toHaveBeenCalledWith(
      { amount: { all: true } },
      expect.any(String),
    );
    await act(async () => resolve(withdrawalDto({ kind: 'TOTAL' })));
  });

  it('a network failure and "Tentar de novo" resend with the SAME Idempotency-Key (S25, U6-NFR7.4)', async () => {
    const requestRealWithdrawal = jest
      .fn()
      .mockRejectedValueOnce(problem(0, 'NETWORK'))
      .mockResolvedValue(withdrawalDto());
    await renderScreen(realApi({ requestRealWithdrawal }));
    fireEvent.change(screen.getByLabelText(text.depositWithdraw.amountLabel), {
      target: { value: '0,5' },
    });
    await click('real-withdraw-submit');
    await click('real-withdraw-confirm-submit');
    expect(screen.getByTestId('real-withdraw-confirm-error')).toHaveTextContent(
      text.errors.NETWORK,
    );

    await click('real-withdraw-confirm-error-retry');

    expect(requestRealWithdrawal).toHaveBeenCalledTimes(2);
    expect(requestRealWithdrawal.mock.calls[1]).toEqual(requestRealWithdrawal.mock.calls[0]);
  });

  it('refuses an amount above the free balance, zero or with 10 decimals before sending', async () => {
    const api = await renderScreen();
    for (const [value, reason] of [
      ['2', 'ABOVE_MAX'],
      ['0', 'ZERO'],
      ['0,0000000001', 'TOO_MANY_DECIMALS'],
    ]) {
      fireEvent.change(screen.getByLabelText(text.depositWithdraw.amountLabel), {
        target: { value },
      });
      await click('real-withdraw-submit');
      expect(screen.getByTestId('real-withdraw-amount-error')).toHaveTextContent(
        text.depositWithdraw.invalid[reason as string] as string,
      );
    }
    expect(api.requestRealWithdrawal).not.toHaveBeenCalled();
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });

  it('with the kill switch it shows the band and the withdrawal stays enabled (S21, BR6.4)', async () => {
    await renderScreen(
      realApi({ getBotWallet: jest.fn(async () => walletDto({ pausedReason: 'KILL_SWITCH' })) }),
    );

    expect(screen.getByTestId('real-kill-switch')).toHaveTextContent(
      text.depositWithdraw.killSwitch,
    );
    expect(screen.getByTestId('real-withdraw-submit')).toBeEnabled();
    expect(screen.getByTestId('real-withdraw-all')).toBeEnabled();
    expect(screen.getByTestId('real-sell-all')).toBeEnabled();
  });

  it('with a divergence it says "Disponível para saque agora" and the limit of the server in text (S22)', async () => {
    await renderScreen(
      realApi({
        getBotWallet: jest.fn(async () => walletDto({ pausedReason: 'DIVERGENCE' })),
        requestRealWithdrawal: jest.fn(async () => {
          throw problem(
            422,
            'WITHDRAWAL_LIMITED_BY_DIVERGENCE',
            'Maximum withdrawable: 750000000 lamports.',
          );
        }),
      }),
    );
    expect(screen.getByText(text.depositWithdraw.availableNow)).toBeInTheDocument();
    await click('real-withdraw-all');
    await click('real-withdraw-confirm-submit');

    expect(screen.getByTestId('real-withdraw-confirm-error')).toHaveTextContent(
      'Agora o saque está limitado a 0,750000000 SOL',
    );
  });

  it('without the new acceptance: the withdrawal opens, the band says so and nothing redirects (S23, S42, BR1.4)', async () => {
    const api = await renderScreen(
      realApi({
        getRealRelease: jest.fn(async () => {
          throw problem(403, 'TERMS_REACCEPTANCE_REQUIRED');
        }),
      }),
    );

    expect(screen.getByTestId('real-terms-pending')).toHaveTextContent(
      text.depositWithdraw.termsPending,
    );
    expect(screen.getByTestId('real-withdraw-submit')).toBeEnabled();
    expect(api.getRealPnl).not.toHaveBeenCalled();
    expect(api.listRealOrders).not.toHaveBeenCalledWith(expect.objectContaining({ limit: 20 }));
    expect(api.notifyTermsReacceptance).not.toHaveBeenCalled();
  });

  it('the tracking ends in 2 minutes with "Ainda processando; acompanhe em Ordens" (U6-NFR4.6)', async () => {
    jest.useFakeTimers({ advanceTimers: true });
    await renderScreen(
      realApi({
        requestRealWithdrawal: jest.fn(async () => withdrawalDto()),
        getRealWithdrawal: jest.fn(async () => withdrawalDto({ state: 'SENT' })),
      }),
    );
    await click('real-withdraw-all');
    await click('real-withdraw-confirm-submit');

    await act(async () => jest.advanceTimersByTimeAsync(TRACKING_LIMIT_MS + TRACKING_INTERVAL_MS));

    expect(screen.getByTestId('real-tracking-timeout')).toHaveTextContent(
      text.depositWithdraw.stillProcessing,
    );
  });
});

describe('R6 sell everything and withdraw', () => {
  it('confirms, starts the liquidation and ends DONE when the withdrawal of everything appears without positions', async () => {
    jest.useFakeTimers({ advanceTimers: true });
    const listRealWithdrawals = jest
      .fn()
      .mockResolvedValueOnce({ items: [withdrawalDto()], total: 1 })
      .mockResolvedValueOnce({ items: [withdrawalDto()], total: 1 })
      .mockResolvedValue({
        items: [withdrawalDto(), withdrawalDto({ withdrawalId: 'w-total', kind: 'TOTAL' })],
        total: 2,
      });
    const startRealLiquidation = jest.fn(async () => ({
      liquidationId: '0dde0000-0000-4000-8000-0000000000c1',
    }));
    await renderScreen(
      realApi({
        listRealWithdrawals,
        startRealLiquidation,
        getRealPnl: jest.fn(async () => pnlDto()),
      }),
    );
    await click('real-sell-all');
    expect(screen.getByRole('alertdialog')).toHaveTextContent('vende todas as posições');
    await click('real-withdraw-confirm-submit');
    expect(startRealLiquidation).toHaveBeenCalledWith(expect.any(String));
    expect(screen.getByTestId('real-sell-all-progress')).toBeInTheDocument();

    await act(async () => jest.advanceTimersByTimeAsync(TRACKING_INTERVAL_MS * 2));

    expect(screen.getByTestId('real-sell-all-done')).toHaveTextContent(
      text.depositWithdraw.liquidationDone,
    );
  });

  it('a PARTIAL liquidation lists the positions that stayed (S44, AC5.2.2)', async () => {
    jest.useFakeTimers({ advanceTimers: true });
    const listRealWithdrawals = jest
      .fn()
      .mockResolvedValueOnce({ items: [], total: 0 })
      .mockResolvedValue({
        items: [withdrawalDto({ withdrawalId: 'w-total', kind: 'TOTAL' })],
        total: 1,
      });
    await renderScreen(
      realApi({
        listRealWithdrawals,
        startRealLiquidation: jest.fn(async () => ({
          liquidationId: '0dde0000-0000-4000-8000-0000000000c1',
        })),
        getRealPnl: jest.fn(async () =>
          pnlDto({
            positions: [
              { mint: MINT, baseUnits: '1000', costLamports: '5', priceStatus: 'UNKNOWN' },
            ],
          }),
        ),
      }),
    );
    await click('real-sell-all');
    await click('real-withdraw-confirm-submit');

    await act(async () => jest.advanceTimersByTimeAsync(TRACKING_INTERVAL_MS));

    const partial = screen.getByTestId('real-sell-all-partial');
    expect(partial).toHaveTextContent(text.depositWithdraw.liquidationPartial);
    expect(partial).toHaveTextContent('Test…1111');
  });

  it('when the withdrawals before the liquidation cannot be read, an older TOTAL withdrawal never ends it (regression)', async () => {
    jest.useFakeTimers({ advanceTimers: true });
    let started = false;
    const listRealWithdrawals = jest.fn(async () => {
      if (!started) {
        throw new Error('network');
      }
      return { items: [withdrawalDto({ withdrawalId: 'w-old-total', kind: 'TOTAL' })], total: 1 };
    });
    await renderScreen(
      realApi({
        listRealWithdrawals,
        startRealLiquidation: jest.fn(async () => {
          started = true;
          return { liquidationId: '0dde0000-0000-4000-8000-0000000000c1' };
        }),
        getRealPnl: jest.fn(async () => pnlDto()),
      }),
    );
    await click('real-sell-all');
    await click('real-withdraw-confirm-submit');

    await act(async () => jest.advanceTimersByTimeAsync(TRACKING_INTERVAL_MS * 3));
    expect(screen.queryByTestId('real-sell-all-done')).not.toBeInTheDocument();
    expect(screen.queryByTestId('real-sell-all-partial')).not.toBeInTheDocument();

    await act(async () => jest.advanceTimersByTimeAsync(TRACKING_LIMIT_MS));
    expect(screen.getByTestId('real-tracking-timeout')).toHaveTextContent(
      text.depositWithdraw.stillProcessing,
    );
  });

  it('a liquidation already in progress is refused in text', async () => {
    await renderScreen(
      realApi({
        startRealLiquidation: jest.fn(async () => {
          throw problem(409, 'ORDER_ALREADY_IN_PROGRESS');
        }),
      }),
    );
    await click('real-sell-all');
    await click('real-withdraw-confirm-submit');

    expect(screen.getByTestId('real-withdraw-confirm-error')).toHaveTextContent(
      'Já há uma venda total em andamento',
    );
  });

  it('abbreviates an address to 4 + 4 characters', () => {
    expect(abbreviateAddress('AbCdEfGhIjKlMnOpWxYz')).toBe('AbCd…WxYz');
    expect(abbreviateAddress('short')).toBe('short');
  });
});
