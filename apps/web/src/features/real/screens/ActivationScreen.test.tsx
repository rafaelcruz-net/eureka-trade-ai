import { act, fireEvent, render, screen } from '@testing-library/react';
import type { GuestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { LEGAL_CURRENT } from '@/features/auth/test-support';
import { settle } from '@/features/dashboard/test-support';
import type { WalletPort } from '@/features/auth/wallets';
import { ActivationScreen } from '@/features/real/screens/ActivationScreen';
import { realMessages as text } from '@/features/real/messages';
import { resetSessionReal } from '@/features/real/session-real';
import {
  BOT_ADDRESS,
  browserStorageText,
  problem,
  RealProviders,
  realApi,
  signingWallet,
  walletDto,
} from '@/features/real/test-support';
import RealActivationPage from '@/app/real/ativacao/page';

/**
 * 261113-trading-real-bot U6, Step 9, written after the code (R1; US1.1, US1.2; BR1.2, BR2.1 to BR2.5, BR6.1 to
 * BR6.3; S2 to S7, S43): the activation in three steps, or the state of the release instead of them.
 */
const replace = jest.fn();
jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: replace }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => '/real/ativacao',
}));

function noWalletApi(overrides: Partial<GuestApi> = {}) {
  return realApi({
    getBotWallet: jest.fn(async () => {
      throw problem(404, 'NOT_FOUND');
    }),
    requestRealChallenge: jest.fn(async () => ({
      challengeId: '0c0c0000-0000-4000-8000-000000000001',
      message: 'Eureka Trader - consentimento REAL v1\npurpose: ACTIVATE',
      nonce: 'n',
      expiresAt: '2026-11-16T12:05:00Z',
    })),
    activateBotWallet: jest.fn(async () => walletDto()),
    ...overrides,
  });
}

async function renderActivation(
  api: jest.Mocked<GuestApi>,
  wallet: WalletPort | null = signingWallet(),
) {
  tokenStore.set('access-token-value');
  render(
    <RealProviders api={api}>
      <ActivationScreen pickWallet={async () => wallet} timeZone="America/Sao_Paulo" />
    </RealProviders>,
  );
  await act(async () => settle(20));
}

async function click(testId: string) {
  await act(async () => {
    fireEvent.click(screen.getByTestId(testId));
  });
  await act(async () => settle(20));
}

beforeEach(() => {
  replace.mockReset();
  resetSessionReal();
});
afterEach(() => tokenStore.clear());

describe('R1 activation', () => {
  it('the page renders the activation', async () => {
    tokenStore.set('access-token-value');
    render(
      <RealProviders api={noWalletApi()}>
        <RealActivationPage />
      </RealProviders>,
    );
    await act(async () => settle(20));

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Carteira do bot');
  });

  it('with the REAL off it says the REAL is not available (S1)', async () => {
    await renderActivation(
      realApi({
        getRealRelease: jest.fn(async () => {
          throw problem(403, 'REAL_DISABLED');
        }),
      }),
    );

    expect(screen.getByTestId('real-unavailable')).toHaveTextContent(text.unavailable);
  });

  it.each([
    ['NONE', undefined, undefined, text.notReleased],
    ['QUARANTINE', '2026-11-17T15:00:00Z', undefined, 'em quarentena até 17/11/2026, 12:00:00'],
    ['BLOCKED', undefined, 'CI_RED', 'bloqueada: a versão do sistema não passou nas verificações'],
  ])(
    'a release %s shows its state instead of the steps (S2, S3, BR2.5)',
    async (state, effectiveAt, reason, message) => {
      const api = noWalletApi({
        getRealRelease: jest.fn(async () => ({
          network: 'LOCALNET',
          release: { state: state as 'NONE', effectiveAt, blockedReason: reason },
          exitsEnabled: false,
        })),
      });
      await renderActivation(api);

      expect(screen.getByTestId('real-release-status')).toHaveTextContent(message);
      expect(screen.queryByTestId('real-activation-consent')).toBeNull();
      expect(screen.queryByTestId('real-activation-terms')).toBeNull();
    },
  );

  it('without the current terms it starts at step 1, "Continuar" disabled with the reason until the box (S4)', async () => {
    await renderActivation(
      noWalletApi({
        getRealRelease: jest.fn(async () => {
          throw problem(403, 'TERMS_REACCEPTANCE_REQUIRED');
        }),
      }),
    );

    expect(screen.getByText('Passo 1 de 3')).toBeInTheDocument();
    expect(screen.getByTestId('real-terms-text')).toHaveTextContent(
      LEGAL_CURRENT.terms.sections[0]?.title ?? '',
    );
    expect(screen.getByTestId('real-terms-continue')).toBeDisabled();
    expect(screen.getByTestId('real-terms-reason')).toHaveTextContent(
      text.activation.continueDisabled,
    );
    fireEvent.click(screen.getByLabelText(text.activation.termsBox));
    expect(screen.getByTestId('real-terms-continue')).toBeEnabled();
  });

  it('a failed acceptance shows the reason and "Tentar de novo"; the activation stays blocked (S5)', async () => {
    const acceptTerms = jest.fn(async () => {
      throw problem(503, 'SERVICE_UNAVAILABLE');
    });
    const api = noWalletApi({
      getRealRelease: jest.fn(async () => {
        throw problem(403, 'TERMS_REACCEPTANCE_REQUIRED');
      }),
      acceptTerms,
    });
    await renderActivation(api);
    fireEvent.click(screen.getByLabelText(text.activation.termsBox));
    await click('real-terms-continue');

    expect(screen.getByTestId('real-terms-error')).toHaveTextContent(text.activation.termsFailed);
    expect(screen.getByTestId('real-terms-error-retry')).toBeInTheDocument();
    expect(screen.queryByTestId('real-activation-consent')).toBeNull();
    expect(acceptTerms).toHaveBeenCalledWith(LEGAL_CURRENT.version, expect.any(String));
  });

  it('after the acceptance it reads the release again and goes to step 2 (S6)', async () => {
    let accepted = false;
    const getRealRelease = jest.fn(async () => {
      if (!accepted) {
        throw problem(403, 'TERMS_REACCEPTANCE_REQUIRED');
      }
      return { network: 'LOCALNET', release: { state: 'ACTIVE' as const }, exitsEnabled: false };
    });
    const acceptTerms = jest.fn(async () => {
      accepted = true;
      return {
        acceptedVersion: 'v3',
        currentVersion: 'v3',
        acceptedAt: '2026-11-16T12:00:00Z',
        upToDate: true,
      };
    });
    await renderActivation(noWalletApi({ getRealRelease, acceptTerms }));
    fireEvent.click(screen.getByLabelText(text.activation.termsBox));
    await click('real-terms-continue');

    expect(screen.getByText('Passo 2 de 3')).toBeInTheDocument();
  });

  it('with the terms accepted it starts at step 2 and says the signature is not the login (S6, BR2.3, BR2.4)', async () => {
    await renderActivation(noWalletApi());

    const step = screen.getByTestId('real-activation-consent');
    expect(step).toHaveTextContent('Passo 2 de 3');
    expect(step).toHaveTextContent(text.activation.consentNotLogin);
    expect(screen.getByLabelText(text.activation.capLabel)).toHaveValue('1');
  });

  it('confirms, signs the message of the server unchanged and activates with the key of that body (S7)', async () => {
    const api = noWalletApi();
    const wallet = signingWallet();
    await renderActivation(api, wallet);
    fireEvent.change(screen.getByLabelText(text.activation.capLabel), { target: { value: '2,5' } });
    await click('real-activation-sign');
    expect(screen.getByTestId('real-activation-confirm-amount')).toHaveTextContent(
      '2,500000000 SOL',
    );
    await click('real-activation-confirm-submit');

    expect(api.requestRealChallenge).toHaveBeenCalledWith({
      kind: 'ACTIVATE',
      capLamports: '2500000000',
    });
    expect(wallet.signMessage).toHaveBeenCalledWith(
      new TextEncoder().encode('Eureka Trader - consentimento REAL v1\npurpose: ACTIVATE'),
    );
    expect(api.activateBotWallet).toHaveBeenCalledWith(
      { challengeId: '0c0c0000-0000-4000-8000-000000000001', signature: expect.any(String) },
      expect.stringMatching(/[0-9a-f-]{36}/),
    );
    expect(screen.getByText('Passo 3 de 3')).toBeInTheDocument();
    expect(screen.getByTestId('real-activation-address')).toHaveTextContent(BOT_ADDRESS);
    expect(screen.getByTestId('real-activation-deposit-link')).toHaveAttribute(
      'href',
      '/real/deposito-saque',
    );
    expect(browserStorageText()).not.toContain(BOT_ADDRESS);
  });

  it('"Copiar" writes the address to the clipboard and says so', async () => {
    const writeText = jest.fn(async () => undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    await renderActivation(noWalletApi());
    await click('real-activation-sign');
    await click('real-activation-confirm-submit');

    await click('real-activation-copy');

    expect(writeText).toHaveBeenCalledWith(BOT_ADDRESS);
    expect(screen.getByText(text.copied)).toBeInTheDocument();
  });

  it('refuses a cap above 10 SOL or with 10 decimals before sending (S8, S10)', async () => {
    const api = noWalletApi();
    await renderActivation(api);

    fireEvent.change(screen.getByLabelText(text.activation.capLabel), { target: { value: '12' } });
    expect(screen.getByTestId('real-activation-cap-error')).toHaveTextContent(
      text.cap.invalid.ABOVE_MAX as string,
    );
    expect(screen.getByTestId('real-activation-sign')).toBeDisabled();
    fireEvent.change(screen.getByLabelText(text.activation.capLabel), {
      target: { value: '0,0000000001' },
    });
    expect(screen.getByTestId('real-activation-cap-error')).toHaveTextContent(
      text.cap.invalid.TOO_MANY_DECIMALS as string,
    );
    expect(api.requestRealChallenge).not.toHaveBeenCalled();
  });

  it('a refusal in the wallet activates nothing and says so inside the confirmation', async () => {
    const wallet = signingWallet({
      signMessage: jest.fn(async () => {
        throw new Error('User rejected');
      }),
    });
    const api = noWalletApi();
    await renderActivation(api, wallet);
    await click('real-activation-sign');
    await click('real-activation-confirm-submit');

    expect(screen.getByTestId('real-activation-confirm-notice')).toHaveTextContent(
      text.activation.walletRefused,
    );
    expect(api.activateBotWallet).not.toHaveBeenCalled();
  });

  it('a refusal of the server (REAL_NOT_RELEASED) shows its text (BR2.5)', async () => {
    const api = noWalletApi({
      activateBotWallet: jest.fn(async () => {
        throw problem(403, 'REAL_NOT_RELEASED');
      }),
    });
    await renderActivation(api);
    await click('real-activation-sign');
    await click('real-activation-confirm-submit');

    expect(screen.getByTestId('real-activation-confirm-error')).toHaveTextContent(text.notReleased);
  });

  it('with an active bot wallet it goes to /real (BR1.2)', async () => {
    await renderActivation(realApi());

    expect(replace).toHaveBeenCalledWith('/real');
  });

  it('without a wallet in the browser it says so and asks nothing', async () => {
    const api = noWalletApi();
    await renderActivation(api, null);
    await click('real-activation-sign');
    await click('real-activation-confirm-submit');

    expect(screen.getByTestId('real-activation-confirm-notice')).toHaveTextContent(
      text.activation.noWallet,
    );
    expect(api.requestRealChallenge).not.toHaveBeenCalled();
  });
});
