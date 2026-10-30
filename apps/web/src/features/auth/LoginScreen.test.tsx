import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import { tokenStore } from '@/lib/auth/token-store';
import { messages } from '@/lib/i18n/messages';
import { LoginScreen } from '@/features/auth/LoginScreen';
import { fakeApi, fakeWallet, Providers } from '@/features/auth/test-support';
import type { WalletPort } from '@/features/auth/wallets';
import EntrarPage from '@/app/entrar/page';

const replace = jest.fn();
let search = new URLSearchParams();

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: replace }),
  useSearchParams: () => search,
}));

function walletsSource(wallets: WalletPort[]) {
  return (onChange: (w: WalletPort[]) => void) => {
    onChange(wallets);
    return () => undefined;
  };
}

function renderLogin(api = fakeApi(), wallets: WalletPort[] = [fakeWallet()]) {
  render(
    <Providers api={api}>
      <LoginScreen walletSource={walletsSource(wallets)} />
    </Providers>,
  );
  return api;
}

describe('S1 login screen', () => {
  beforeEach(() => {
    replace.mockReset();
    search = new URLSearchParams();
    tokenStore.clear();
  });

  it('explains that no compatible wallet was found', () => {
    renderLogin(fakeApi(), []);

    expect(screen.getByTestId('login-no-wallet')).toHaveTextContent(messages.login.noWallet);
    expect(screen.queryByRole('button', { name: /Conectar/ })).toBeNull();
  });

  it('offers Phantom and Solflare through the same path', () => {
    renderLogin(fakeApi(), [fakeWallet(), fakeWallet({ name: 'Solflare', provider: 'SOLFLARE' })]);

    expect(screen.getByTestId('login-connect-phantom')).toHaveTextContent('Conectar Phantom');
    expect(screen.getByTestId('login-connect-solflare')).toHaveTextContent('Conectar Solflare');
  });

  it('signs in, keeps the token in memory and returns to the screen of origin', async () => {
    search = new URLSearchParams({ origem: '/scanner?pagina=2' });
    const api = renderLogin();

    await userEvent.click(screen.getByTestId('login-connect-phantom'));

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/scanner?pagina=2'));
    expect(tokenStore.get()).toBe('access-token-value');
    expect(api.verifyLogin).toHaveBeenCalledTimes(1);
  });

  it('shows the signing state while the wallet asks for confirmation', async () => {
    let finish: (value: Uint8Array) => void = () => undefined;
    const wallet = fakeWallet({
      signMessage: () => new Promise<Uint8Array>((resolve) => (finish = resolve)),
    });
    renderLogin(fakeApi(), [wallet]);

    await userEvent.click(screen.getByTestId('login-connect-phantom'));

    expect(await screen.findByTestId('login-signing')).toHaveTextContent(messages.login.signing);
    expect(screen.getByTestId('login-connect-phantom')).toBeDisabled();
    finish(new Uint8Array([1]));
    await waitFor(() => expect(replace).toHaveBeenCalled());
  });

  it.each([
    ['EXPIRED', messages.login.rejected.EXPIRED],
    ['NONCE_ALREADY_USED', messages.login.rejected.NONCE_ALREADY_USED],
    ['UNKNOWN_ACCOUNT', messages.login.rejected.UNKNOWN_ACCOUNT],
    ['ADMIN_WALLET', messages.login.rejected.ADMIN_WALLET],
  ])('shows the refusal %s as text', async (reason, text) => {
    const api = fakeApi({
      verifyLogin: jest.fn(async () => {
        throw new ApiError(401, 'LOGIN_CHALLENGE_REJECTED', reason);
      }),
    });
    renderLogin(api);

    await userEvent.click(screen.getByTestId('login-connect-phantom'));

    expect(await screen.findByTestId('login-rejected-reason')).toHaveTextContent(text as string);
    expect(tokenStore.get()).toBeNull();
  });

  it('distinguishes rate limiting, network errors and a cancelled signature', async () => {
    const api = fakeApi({
      requestChallenge: jest
        .fn()
        .mockRejectedValueOnce(new ApiError(429, 'RATE_LIMITED', 'IP_LIMIT'))
        .mockRejectedValueOnce(new ApiError(0, 'NETWORK')),
    });
    const wallet = fakeWallet();
    renderLogin(api, [wallet]);

    await userEvent.click(screen.getByTestId('login-connect-phantom'));
    expect(await screen.findByTestId('login-rejected-reason')).toHaveTextContent(
      messages.login.rejected.RATE_LIMITED as string,
    );

    await userEvent.click(screen.getByTestId('login-retry'));
    await userEvent.click(screen.getByTestId('login-connect-phantom'));
    expect(await screen.findByTestId('login-rejected-reason')).toHaveTextContent(
      messages.login.rejected.NETWORK as string,
    );
  });

  it('reports a signature the person cancelled in the wallet', async () => {
    const wallet = fakeWallet({
      signMessage: jest.fn(async () => {
        throw new Error('User rejected the request.');
      }),
    });
    renderLogin(fakeApi(), [wallet]);

    await userEvent.click(screen.getByTestId('login-connect-phantom'));

    expect(await screen.findByTestId('login-rejected-reason')).toHaveTextContent(
      messages.login.rejected.USER_CANCELLED as string,
    );
  });

  it('shows the expired-session notice when coming back from an expired session', () => {
    search = new URLSearchParams({ expirada: '1', origem: '/scanner' });
    renderLogin();

    expect(screen.getByTestId('login-session-expired')).toHaveTextContent(
      messages.login.sessionExpired,
    );
  });

  it('never asks for a secret phrase or private key', () => {
    renderLogin();

    expect(screen.queryAllByRole('textbox')).toHaveLength(0);
    expect(document.querySelectorAll('input, textarea')).toHaveLength(0);
    expect(screen.getByText(messages.login.neverAskSecret)).toBeInTheDocument();
  });

  it('is operable with the keyboard only, with the heading focused first', async () => {
    renderLogin();

    expect(screen.getByRole('heading', { level: 1, name: messages.login.title })).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('login-connect-phantom')).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/scanner'));
  });

  it('the /entrar page renders the login screen', async () => {
    render(
      <Providers api={fakeApi()}>
        <EntrarPage />
      </Providers>,
    );

    expect(
      await screen.findByRole('heading', { level: 1, name: messages.login.title }),
    ).toBeInTheDocument();
  });
});
