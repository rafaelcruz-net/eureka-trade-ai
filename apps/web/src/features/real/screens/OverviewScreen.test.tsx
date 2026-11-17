import { act, fireEvent, render, screen, within } from '@testing-library/react';
import type { GuestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { settle } from '@/features/dashboard/test-support';
import type { WalletPort } from '@/features/auth/wallets';
import { OverviewScreen } from '@/features/real/screens/OverviewScreen';
import { realMessages as text } from '@/features/real/messages';
import { resetSessionReal } from '@/features/real/session-real';
import {
  MINT,
  pnlDto,
  problem,
  RealProviders,
  realApi,
  signingWallet,
  walletDto,
} from '@/features/real/test-support';
import RealOverviewPage from '@/app/real/page';
import BotWalletPage from '@/app/real/carteira/page';

/**
 * 261113-trading-real-bot U6, Step 10, written after the code (R2; US2.1, US2.2, US4.2, US8.2; BR1.1 to BR1.3, BR5,
 * BR7.2, BR8.1 to BR8.3; S1, S8 to S11, S30 to S32, S36, S43): the overview, the cap and the P&L REAL card.
 */
const replace = jest.fn();
jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: replace }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => '/real',
  redirect: (path: string) => {
    throw new Error(`redirect:${path}`);
  },
}));

async function renderOverview(
  api: jest.Mocked<GuestApi> = realApi(),
  wallet: WalletPort | null = signingWallet(),
) {
  tokenStore.set('access-token-value');
  render(
    <RealProviders api={api}>
      <OverviewScreen pickWallet={async () => wallet} timeZone="America/Sao_Paulo" />
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

function typeCap(value: string) {
  fireEvent.change(screen.getByLabelText(text.cap.label), { target: { value } });
}

beforeEach(() => {
  replace.mockReset();
  resetSessionReal();
});
afterEach(() => tokenStore.clear());

describe('R2 overview', () => {
  it('the page renders the overview, and /real/carteira sends to /real (BR1.3)', async () => {
    tokenStore.set('access-token-value');
    render(
      <RealProviders api={realApi()}>
        <RealOverviewPage />
      </RealProviders>,
    );
    await act(async () => settle(20));

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Visão geral');
    expect(() => BotWalletPage()).toThrow('redirect:/real');
  });

  it('shows the parts of the balance with 9 decimals and the time of the balance (mockups R2)', async () => {
    await renderOverview();

    expect(screen.getByTestId('real-overview-free')).toHaveTextContent('1,250000000 SOL');
    expect(screen.getByTestId('real-overview-reserved')).toHaveTextContent('0,200000000 SOL');
    expect(screen.getByTestId('real-overview-fee-reserve')).toHaveTextContent('0,010000000 SOL');
    expect(screen.getByTestId('real-overview-above-cap')).toHaveTextContent('0,000000000 SOL');
    expect(screen.getByTestId('real-overview-as-of')).toHaveTextContent('16/11/2026, 08:56:00');
    expect(screen.getByTestId('real-overview-mode')).toHaveTextContent('Modo: Assinada');
  });

  it('with the REAL off it says so (S1, BR1.1)', async () => {
    await renderOverview(
      realApi({
        getRealRelease: jest.fn(async () => {
          throw problem(403, 'REAL_DISABLED');
        }),
      }),
    );

    expect(screen.getByTestId('real-unavailable')).toHaveTextContent(text.unavailable);
  });

  it('without an active wallet it goes to the activation (S43, BR1.2)', async () => {
    await renderOverview(
      realApi({
        getBotWallet: jest.fn(async () => {
          throw problem(404, 'NOT_FOUND');
        }),
      }),
    );

    expect(replace).toHaveBeenCalledWith('/real/ativacao');
  });

  it('a failing read of the wallet shows its error with the correlation id and "Tentar de novo"', async () => {
    const getBotWallet = jest.fn().mockRejectedValue(problem(503, 'SERVICE_UNAVAILABLE'));
    const api = await renderOverview(realApi({ getBotWallet }));

    expect(screen.getByTestId('real-wallet-error-correlation')).toBeInTheDocument();
    getBotWallet.mockResolvedValue(walletDto());
    await click('real-wallet-error-retry');
    expect(screen.getByTestId('real-overview-free')).toBeInTheDocument();
    expect(api.getBotWallet).toHaveBeenCalled();
  });

  it('a pause RESTORE shows the band with the text of the restore (S32, BR8.3)', async () => {
    await renderOverview(
      realApi({ getBotWallet: jest.fn(async () => walletDto({ pausedReason: 'RESTORE' })) }),
    );

    expect(screen.getByTestId('real-pause-banner')).toHaveTextContent(
      text.paused.reasons.RESTORE as string,
    );
  });

  it('a cap lowered by the administrator says "Ajustado pelo administrador" (S36, BR5.4)', async () => {
    await renderOverview(
      realApi({ getBotWallet: jest.fn(async () => walletDto({ capSetBy: 'ADMIN' })) }),
    );

    expect(screen.getByTestId('real-cap-admin')).toHaveTextContent(text.cap.setByAdmin);
    expect(screen.getByTestId('real-cap-current')).toHaveTextContent(
      'Teto: 3,000000000 SOL (máximo 10,000000000 SOL)',
    );
  });
});

describe('R2 cap', () => {
  it('refuses "12" with the explanation of the maximum, without any call (S8)', async () => {
    const api = await renderOverview();
    typeCap('12');
    await click('real-cap-save');

    expect(screen.getByTestId('real-cap-input-error')).toHaveTextContent(
      'O teto máximo é de 10 SOL.',
    );
    expect(api.changeRealPreferences).not.toHaveBeenCalled();
  });

  it('refuses 10 decimals and an empty value (S10, BR5.2)', async () => {
    const api = await renderOverview();
    typeCap('0,0000000001');
    await click('real-cap-save');
    expect(screen.getByTestId('real-cap-input-error')).toHaveTextContent(
      text.cap.invalid.TOO_MANY_DECIMALS as string,
    );
    typeCap('');
    await click('real-cap-save');

    expect(screen.getByTestId('real-cap-input-error')).toHaveTextContent(
      text.cap.invalid.EMPTY as string,
    );
    expect(api.changeRealPreferences).not.toHaveBeenCalled();
  });

  it('lowering "2,5" sends 2500000000 lamports straight with PATCH and explains the exposure (S9, BR5.3)', async () => {
    const changeRealPreferences = jest.fn(async () => walletDto({ capLamports: '2500000000' }));
    const api = await renderOverview(realApi({ changeRealPreferences }));
    typeCap('2,5');
    expect(screen.getByText(text.cap.lowerPath)).toBeInTheDocument();
    await click('real-cap-save');

    expect(changeRealPreferences).toHaveBeenCalledWith(
      { capLamports: '2500000000' },
      expect.any(String),
    );
    expect(api.requestRealChallenge).not.toHaveBeenCalled();
    expect(screen.getByTestId('real-cap-notice')).toHaveTextContent(text.cap.belowExposure);
    expect(screen.getByTestId('real-cap-current')).toHaveTextContent('2,500000000 SOL');
  });

  it('raising asks the consent RAISE_CAP with the new value and sends it signed (S11, BR5.2b)', async () => {
    const changeRealPreferences = jest.fn(async () => walletDto({ capLamports: '3500000000' }));
    const requestRealChallenge = jest.fn(async () => ({
      challengeId: '0c0c0000-0000-4000-8000-000000000002',
      message: 'RAISE_CAP 3500000000',
      nonce: 'n',
      expiresAt: '2026-11-16T12:05:00Z',
    }));
    const wallet = signingWallet();
    await renderOverview(realApi({ changeRealPreferences, requestRealChallenge }), wallet);
    typeCap('3,5');
    expect(screen.getByText(text.cap.raisePath)).toBeInTheDocument();
    await click('real-cap-save');
    expect(screen.getByRole('alertdialog')).toHaveTextContent('3,500000000 SOL');
    await click('real-cap-confirm-submit');

    expect(requestRealChallenge).toHaveBeenCalledWith({
      kind: 'RAISE_CAP',
      capLamports: '3500000000',
    });
    expect(wallet.signMessage).toHaveBeenCalled();
    expect(changeRealPreferences).toHaveBeenCalledWith(
      {
        capLamports: '3500000000',
        challengeId: '0c0c0000-0000-4000-8000-000000000002',
        signature: expect.any(String),
      },
      expect.any(String),
    );
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });

  it('a refusal of the server shows its text (CAP_ABOVE_MAXIMUM)', async () => {
    await renderOverview(
      realApi({
        changeRealPreferences: jest.fn(async () => {
          throw problem(422, 'CAP_ABOVE_MAXIMUM');
        }),
      }),
    );
    typeCap('1');
    await click('real-cap-save');

    expect(screen.getByTestId('real-cap-error')).toHaveTextContent(
      'O teto máximo é de 10,000000000 SOL',
    );
  });
});

describe('R2 P&L REAL', () => {
  it('shows realized, unrealized and fees with the sign written, in its own card with the REAL label (S31)', async () => {
    await renderOverview();

    const card = screen.getByTestId('real-pnl-card');
    expect(within(card).getByTestId('real-pnl-badge')).toHaveTextContent('REAL');
    expect(screen.getByTestId('real-pnl-realized')).toHaveTextContent('+0,084000000 SOL');
    expect(screen.getByTestId('real-pnl-unrealized')).toHaveTextContent('+0,012000000 SOL');
    expect(screen.getByTestId('real-pnl-fees')).toHaveTextContent('0,000015000 SOL');
    expect(card).toHaveTextContent(text.pnl.separated);
  });

  it('a position without a price shows its cost with "sem preço", never zero (S30, BR8.2)', async () => {
    await renderOverview(
      realApi({
        getRealPnl: jest.fn(async () =>
          pnlDto({
            unrealizedStatus: 'UNKNOWN',
            unrealizedLamports: undefined,
            positions: [
              { mint: MINT, baseUnits: '1000', costLamports: '50000000', priceStatus: 'UNKNOWN' },
            ],
          }),
        ),
      }),
    );

    expect(screen.getByTestId('real-pnl-unrealized')).toHaveTextContent('sem preço');
    expect(screen.getByTestId(`real-pnl-position-${MINT}`)).toHaveTextContent(
      'Custo de compra 0,050000000 SOL',
    );
    expect(screen.getByTestId(`real-pnl-position-value-${MINT}`)).toHaveTextContent('sem preço');
    expect(screen.getByTestId(`real-pnl-position-value-${MINT}`)).not.toHaveTextContent(
      '0,000000000',
    );
  });

  it('an error of the P&L shows only in its card; the balance stays (U6-NFR4.3)', async () => {
    const getRealPnl = jest.fn<ReturnType<GuestApi['getRealPnl']>, []>(async () => {
      throw problem(503, 'SERVICE_UNAVAILABLE');
    });
    await renderOverview(realApi({ getRealPnl }));

    expect(screen.getByTestId('real-pnl-error')).toHaveTextContent(text.pnl.error);
    expect(screen.getByTestId('real-overview-free')).toHaveTextContent('1,250000000 SOL');
    getRealPnl.mockImplementation(async () => pnlDto());
    await click('real-pnl-error-retry');
    expect(screen.getByTestId('real-pnl-realized')).toBeInTheDocument();
  });
});
