import { act, fireEvent, render, screen } from '@testing-library/react';
import type { GuestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { settle } from '@/features/dashboard/test-support';
import type { WalletPort } from '@/features/auth/wallets';
import { isRaise, ModeScreen } from '@/features/real/screens/ModeScreen';
import { realMessages as text } from '@/features/real/messages';
import { readSessionReal, resetSessionReal } from '@/features/real/session-real';
import {
  problem,
  RealProviders,
  realApi,
  signingWallet,
  walletDto,
} from '@/features/real/test-support';
import RealModePage from '@/app/real/modo/page';

/**
 * 261113-trading-real-bot U6, Step 11, written after the code (R3; US3.1; BR4.1 to BR4.4, BR6.2; S17 to S20, S40): the
 * choice of the mode, the consent to raise it and the confirmation to go back to SIGNED.
 */
jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => '/real/modo',
}));

const CHALLENGE = {
  challengeId: '0c0c0000-0000-4000-8000-000000000003',
  message: 'RAISE_MODE AUTO_FULL',
  nonce: 'n',
  expiresAt: '2026-11-16T12:05:00Z',
};

async function renderMode(
  api: jest.Mocked<GuestApi>,
  wallet: WalletPort | null = signingWallet(),
  newKey?: () => string,
) {
  tokenStore.set('access-token-value');
  render(
    <RealProviders api={api}>
      <ModeScreen pickWallet={async () => wallet} newKey={newKey} />
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

beforeEach(() => resetSessionReal());
afterEach(() => tokenStore.clear());

describe('R3 mode', () => {
  it('the page renders the three options with the current one marked', async () => {
    tokenStore.set('access-token-value');
    render(
      <RealProviders api={realApi()}>
        <RealModePage />
      </RealProviders>,
    );
    await act(async () => settle(20));

    expect(screen.getByRole('group', { name: text.mode.title })).toBeInTheDocument();
    expect(screen.getByLabelText('Assinada (atual)')).toBeChecked();
    expect(screen.getByTestId('real-mode-save')).toBeDisabled();
  });

  it('"Automática nas saídas" is disabled with "Em breve" (S20, BR4.1)', async () => {
    await renderMode(realApi());

    expect(screen.getByTestId('real-mode-option-AUTO_EXITS')).toBeDisabled();
    expect(screen.getByTestId('real-mode-selector')).toHaveTextContent('Em breve');
  });

  it('raising to AUTO_FULL confirms, asks RAISE_MODE, signs and sends the PUT (S17, BR4.2)', async () => {
    const changeRealMode = jest.fn(async () => ({ mode: 'AUTO_FULL' as const }));
    const requestRealChallenge = jest.fn(async () => CHALLENGE);
    const wallet = signingWallet();
    await renderMode(realApi({ changeRealMode, requestRealChallenge }), wallet);
    fireEvent.click(screen.getByLabelText('Automática total'));
    await click('real-mode-save');
    expect(screen.getByRole('alertdialog')).toHaveTextContent(text.mode.raiseTitle);
    await click('real-mode-confirm-submit');

    expect(requestRealChallenge).toHaveBeenCalledWith({ kind: 'RAISE_MODE', mode: 'AUTO_FULL' });
    expect(wallet.signMessage).toHaveBeenCalledWith(new TextEncoder().encode(CHALLENGE.message));
    expect(changeRealMode).toHaveBeenCalledWith(
      { mode: 'AUTO_FULL', challengeId: CHALLENGE.challengeId, signature: expect.any(String) },
      expect.any(String),
    );
    expect(screen.getByTestId('real-mode-notice')).toHaveTextContent(
      'Modo Automática total salvo.',
    );
    expect((await readSessionReal(realApi())).mode).toBe('AUTO_FULL');
  });

  it('going back to SIGNED asks only the confirmation with the cancellation notice (S18, BR4.3)', async () => {
    const changeRealMode = jest.fn(async () => ({ mode: 'SIGNED' as const }));
    const api = realApi({
      getBotWallet: jest.fn(async () => walletDto({ mode: 'AUTO_FULL' })),
      changeRealMode,
    });
    await renderMode(api);
    fireEvent.click(screen.getByLabelText('Assinada'));
    await click('real-mode-save');

    expect(screen.getByRole('alertdialog')).toHaveTextContent(text.mode.lowerEffect);
    await click('real-mode-confirm-submit');
    expect(changeRealMode).toHaveBeenCalledWith({ mode: 'SIGNED' }, expect.any(String));
    expect(api.requestRealChallenge).not.toHaveBeenCalled();
  });

  it('an order in conference shows "Espere a ordem em conferência terminar" (S19, BR4.4)', async () => {
    const api = realApi({
      getBotWallet: jest.fn(async () => walletDto({ mode: 'AUTO_FULL' })),
      changeRealMode: jest.fn(async () => {
        throw problem(409, 'ORDER_AMBIGUOUS');
      }),
    });
    await renderMode(api);
    fireEvent.click(screen.getByLabelText('Assinada'));
    await click('real-mode-save');
    await click('real-mode-confirm-submit');

    expect(screen.getByTestId('real-mode-confirm-error')).toHaveTextContent(
      'Espere a ordem em conferência terminar',
    );
  });

  it('after CONSENT_EXPIRED a new confirmation asks a new challenge and sends a NEW key (S40, BR6.2)', async () => {
    let keys = 0;
    const changeRealMode = jest
      .fn()
      .mockRejectedValueOnce(problem(422, 'CONSENT_EXPIRED'))
      .mockResolvedValue({ mode: 'AUTO_FULL' });
    const requestRealChallenge = jest
      .fn()
      .mockResolvedValueOnce(CHALLENGE)
      .mockResolvedValueOnce({ ...CHALLENGE, challengeId: '0c0c0000-0000-4000-8000-000000000004' });
    await renderMode(
      realApi({ changeRealMode, requestRealChallenge }),
      signingWallet({
        signMessage: jest
          .fn()
          .mockResolvedValueOnce(new Uint8Array([1]))
          .mockResolvedValueOnce(new Uint8Array([2])),
      }),
      () => `key-${++keys}`,
    );
    fireEvent.click(screen.getByLabelText('Automática total'));
    await click('real-mode-save');
    await click('real-mode-confirm-submit');
    expect(screen.getByTestId('real-mode-confirm-error')).toHaveTextContent(
      text.errors.CONSENT_EXPIRED,
    );

    await click('real-mode-confirm-submit');

    expect(requestRealChallenge).toHaveBeenCalledTimes(2);
    expect(changeRealMode.mock.calls[0]?.[1]).toBe('key-1');
    expect(changeRealMode.mock.calls[1]?.[1]).toBe('key-2');
    expect(changeRealMode.mock.calls[1]?.[0]).toMatchObject({
      challengeId: '0c0c0000-0000-4000-8000-000000000004',
    });
  });

  it('a refusal in the wallet sends nothing and says so in the dialog', async () => {
    const api = realApi({ requestRealChallenge: jest.fn(async () => CHALLENGE) });
    await renderMode(
      api,
      signingWallet({
        signMessage: jest.fn(async () => {
          throw new Error('rejected');
        }),
      }),
    );
    fireEvent.click(screen.getByLabelText('Automática total'));
    await click('real-mode-save');
    await click('real-mode-confirm-submit');

    expect(screen.getByTestId('real-mode-confirm-notice')).toHaveTextContent(text.walletRefused);
    expect(api.changeRealMode).not.toHaveBeenCalled();
  });

  it('a refusal without release is in text (BR4.4)', async () => {
    const api = realApi({
      requestRealChallenge: jest.fn(async () => {
        throw problem(403, 'REAL_NOT_RELEASED');
      }),
    });
    await renderMode(api);
    fireEvent.click(screen.getByLabelText('Automática total'));
    await click('real-mode-save');
    await click('real-mode-confirm-submit');

    expect(screen.getByTestId('real-mode-confirm-notice')).toHaveTextContent(text.notReleased);
  });

  it('Esc closes the confirmation without any call, and the focus returns to "Salvar"', async () => {
    const api = realApi();
    await renderMode(api);
    fireEvent.click(screen.getByLabelText('Automática total'));
    screen.getByTestId('real-mode-save').focus();
    await click('real-mode-save');

    fireEvent.keyDown(screen.getByRole('alertdialog'), { key: 'Escape' });

    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(screen.getByTestId('real-mode-save')).toHaveFocus();
    expect(api.requestRealChallenge).not.toHaveBeenCalled();
  });

  it('a pause shows its reason above the choice', async () => {
    await renderMode(
      realApi({ getBotWallet: jest.fn(async () => walletDto({ pausedReason: 'DAILY_LOSS' })) }),
    );

    expect(screen.getByTestId('real-pause-banner')).toHaveTextContent('perda do dia');
  });

  it('isRaise orders the modes', () => {
    expect(isRaise('SIGNED', 'AUTO_FULL')).toBe(true);
    expect(isRaise('AUTO_FULL', 'SIGNED')).toBe(false);
    expect(isRaise('SIGNED', 'AUTO_EXITS')).toBe(true);
    expect(isRaise('SIGNED', 'SIGNED')).toBe(false);
  });
});
