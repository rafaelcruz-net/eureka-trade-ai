import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import { messages } from '@/lib/i18n/messages';
import { tokenStore } from '@/lib/auth/token-store';
import { encodeBase58 } from '@/lib/base58';
import { AdminSignIn } from '@/features/auth/AdminSignIn';
import { outcomeOf, signInAsAdministrator } from '@/features/auth/sign-in';
import { providerOf } from '@/features/auth/wallets';
import { AdminShell } from '@/features/layout/AdminShell';
import { Providers, SESSION, fakeAdminApi, fakeWallet, walletsOf } from '@/test-support';

const text = messages.signIn;

function anonymousApi(overrides = {}) {
  return fakeAdminApi({
    refreshSession: jest.fn(async () => Promise.reject(new Error('no'))),
    ...overrides,
  });
}

describe('administrator entry (F1, BR1.1, BR1.2, BR7.4)', () => {
  beforeEach(() => tokenStore.clear());

  it('shows the entry and no page when the cookie cannot be renewed', async () => {
    render(
      <Providers api={anonymousApi()}>
        <AdminShell>
          <p data-testid="protected-page">conteúdo</p>
        </AdminShell>
      </Providers>,
    );

    expect(await screen.findByTestId('admin-sign-in')).toHaveTextContent(text.neverAskSecret);
    expect(screen.queryByTestId('protected-page')).toBeNull();
  });

  it('shows the protected page with the masked wallet after a valid entry', async () => {
    const api = fakeAdminApi({
      refreshSession: jest.fn(async () => Promise.reject(new Error('no'))),
    });
    render(
      <Providers api={api}>
        <AdminSignIn walletsSource={walletsOf([fakeWallet()])} />
      </Providers>,
    );

    await userEvent.click(await screen.findByTestId('admin-sign-in-connect-phantom'));

    await waitFor(() => expect(tokenStore.get()).toBe(SESSION.accessToken));
    expect(api.verifyLogin).toHaveBeenCalledWith(
      expect.objectContaining({
        provider: 'PHANTOM',
        signature: encodeBase58(new Uint8Array([0, 1, 2, 3])),
      }),
    );
  });

  it('the entry of the shell reads the Wallet Standard registry, empty in this browser', async () => {
    render(
      <Providers api={anonymousApi()}>
        <AdminShell>
          <p data-testid="protected-page">conteúdo</p>
        </AdminShell>
      </Providers>,
    );

    expect(await screen.findByTestId('admin-sign-in-no-wallet')).toHaveTextContent(text.noWallet);
  });

  it.each([
    [new ApiError(401, 'UNAUTHENTICATED'), text.rejected.UNAUTHENTICATED],
    [new ApiError(429, 'RATE_LIMITED'), text.rejected.RATE_LIMITED],
    [new ApiError(0, 'NETWORK'), text.rejected.NETWORK],
    [new Error('User rejected the request'), text.rejected.USER_CANCELLED],
    [new ApiError(500, 'INTERNAL_ERROR'), text.rejected.GENERIC],
  ])('shows the reason of %s on the entry', async (failure, reason) => {
    const api = fakeAdminApi({
      refreshSession: jest.fn(async () => Promise.reject(new Error('no'))),
      verifyLogin: jest.fn(async () => {
        throw failure;
      }),
    });
    render(
      <Providers api={api}>
        <AdminSignIn walletsSource={walletsOf([fakeWallet()])} />
      </Providers>,
    );

    await userEvent.click(await screen.findByTestId('admin-sign-in-connect-phantom'));

    expect(await screen.findByTestId('admin-sign-in-error')).toHaveTextContent(reason as string);
  });

  it('moves to "Acesso restrito" on a 403 of the verification', async () => {
    const api = fakeAdminApi({
      refreshSession: jest.fn(async () => Promise.reject(new Error('no'))),
      verifyLogin: jest.fn(async () => {
        throw new ApiError(403, 'FORBIDDEN_ROLE');
      }),
    });
    const wallets = walletsOf([fakeWallet()]);
    function Entry() {
      return <AdminSignIn walletsSource={wallets} />;
    }
    render(
      <Providers api={api}>
        <Entry />
        <AdminShell>
          <p>conteúdo</p>
        </AdminShell>
      </Providers>,
    );
    const buttons = await screen.findAllByTestId('admin-sign-in-connect-phantom');

    await userEvent.click(buttons[0] as HTMLElement);

    expect(await screen.findByTestId('admin-restricted')).toHaveTextContent(
      messages.restricted.title,
    );
    expect(tokenStore.get()).toBeNull();
    await userEvent.click(screen.getByTestId('admin-restricted-sign-out'));
    await waitFor(() => expect(api.logout).toHaveBeenCalled());
  });

  it('says when no compatible wallet exists in the browser', async () => {
    render(
      <Providers
        api={fakeAdminApi({ refreshSession: jest.fn(async () => Promise.reject(new Error('no'))) })}
      >
        <AdminSignIn walletsSource={walletsOf([])} />
      </Providers>,
    );

    expect(await screen.findByTestId('admin-sign-in-no-wallet')).toHaveTextContent(text.noWallet);
  });

  it('signs exactly the message of the administrator challenge', async () => {
    const wallet = fakeWallet();
    const api = fakeAdminApi();

    await signInAsAdministrator(wallet, api);

    const signed = (wallet.signMessage as jest.Mock).mock.calls[0][0] as Uint8Array;
    expect(new TextDecoder().decode(signed)).toContain('localhost:3001 pede que você entre');
    expect(api.requestChallenge).toHaveBeenCalledWith(
      'AdmWa11et1111111111111111111111111111111111',
    );
  });

  it('maps the failures and the wallet names', () => {
    expect(outcomeOf(new ApiError(403, 'FORBIDDEN_ROLE'))).toBe('RESTRICTED');
    expect(outcomeOf('weird')).toBe('GENERIC');
    expect(providerOf('Solflare')).toBe('SOLFLARE');
    expect(providerOf('Phantom Wallet')).toBe('PHANTOM');
    expect(providerOf('Other')).toBeNull();
  });
});

describe('administrator session (BR1.3, BR1.6)', () => {
  beforeEach(() => {
    tokenStore.clear();
    jest.useRealTimers();
  });

  it('renews the access token before it ends and goes back to the entry when it cannot', async () => {
    jest.useFakeTimers();
    jest.setSystemTime(new Date('2026-11-05T12:00:00Z'));
    const refresh = jest
      .fn()
      .mockResolvedValueOnce(SESSION)
      .mockRejectedValueOnce(new ApiError(401, 'UNAUTHENTICATED'));
    const api = fakeAdminApi({ refreshSession: refresh });
    render(
      <Providers api={api}>
        <AdminShell>
          <p data-testid="protected-page">conteúdo</p>
        </AdminShell>
      </Providers>,
    );
    await act(async () => {
      await Promise.resolve();
    });
    expect(screen.getByTestId('protected-page')).toBeInTheDocument();

    await act(async () => {
      jest.advanceTimersByTime(14 * 60_000 + 1_000);
      await Promise.resolve();
    });

    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getByTestId('admin-sign-in')).toBeInTheDocument());
    jest.useRealTimers();
  });

  it('"Sair" revokes the session and clears the token even when the server fails', async () => {
    const api = fakeAdminApi({
      logout: jest.fn(async () => {
        throw new ApiError(500, 'INTERNAL_ERROR');
      }),
    });
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    render(
      <Providers api={api}>
        <AdminShell>
          <p data-testid="protected-page">conteúdo</p>
        </AdminShell>
      </Providers>,
    );

    await userEvent.click(await screen.findByTestId('admin-sign-out'));

    expect(await screen.findByTestId('admin-sign-in')).toBeInTheDocument();
    expect(tokenStore.get()).toBeNull();
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('shows the loading state while the cookie is checked', () => {
    const api = fakeAdminApi({ refreshSession: jest.fn(() => new Promise(() => undefined)) });
    render(
      <Providers api={api}>
        <AdminShell>
          <p>conteúdo</p>
        </AdminShell>
      </Providers>,
    );

    expect(screen.getByTestId('admin-loading')).toHaveTextContent(messages.app.loading);
  });
});
