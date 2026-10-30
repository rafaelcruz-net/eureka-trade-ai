import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import { tokenStore } from '@/lib/auth/token-store';
import { SignOutButton } from '@/features/auth/SignOutButton';
import { useSession } from '@/features/auth/SessionProvider';
import { useRequireSession } from '@/features/auth/useRequireSession';
import { fakeApi, Providers, SESSION } from '@/features/auth/test-support';

const replace = jest.fn();

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: replace }),
  useSearchParams: () => new URLSearchParams(),
}));

function Probe() {
  const { status, expire } = useSession();
  useRequireSession('/scanner');
  return (
    <div>
      <span data-testid="status">{status}</span>
      <button type="button" onClick={expire}>
        expire
      </button>
      <SignOutButton />
    </div>
  );
}

describe('session in memory', () => {
  beforeEach(() => {
    replace.mockReset();
    tokenStore.clear();
  });

  it('renews the session once on load through the refresh cookie', async () => {
    const api = fakeApi({ refreshSession: jest.fn(async () => SESSION) });
    render(
      <Providers api={api}>
        <Probe />
      </Providers>,
    );

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('authenticated'));
    expect(api.refreshSession).toHaveBeenCalledTimes(1);
    expect(tokenStore.get()).toBe(SESSION.accessToken);
  });

  it('without a valid refresh cookie the person is sent to the entry keeping the origin', async () => {
    render(
      <Providers api={fakeApi()}>
        <Probe />
      </Providers>,
    );

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/entrar?origem=%2Fscanner'));
  });

  it('an expired session drops the token and asks to sign in again with the expired notice', async () => {
    tokenStore.set('old-token');
    render(
      <Providers api={fakeApi()}>
        <Probe />
      </Providers>,
    );

    await userEvent.click(screen.getByRole('button', { name: 'expire' }));

    expect(tokenStore.get()).toBeNull();
    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith('/entrar?origem=%2Fscanner&expirada=1'),
    );
  });

  it('"Sair" revokes the session, clears the token and returns to the entry', async () => {
    tokenStore.set('token');
    const api = fakeApi();
    render(
      <Providers api={api}>
        <Probe />
      </Providers>,
    );

    await userEvent.click(screen.getByTestId('app-sign-out-button'));

    expect(api.logout).toHaveBeenCalledTimes(1);
    expect(tokenStore.get()).toBeNull();
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/entrar'));
  });

  it('a failing logout still clears the local token', async () => {
    tokenStore.set('token');
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    const api = fakeApi({
      logout: jest.fn(async () => {
        throw new ApiError(0, 'NETWORK');
      }),
    });
    render(
      <Providers api={api}>
        <Probe />
      </Providers>,
    );

    await act(async () => {
      await userEvent.click(screen.getByTestId('app-sign-out-button'));
    });

    expect(tokenStore.get()).toBeNull();
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('hooks fail loudly outside their providers', () => {
    const error = jest.spyOn(console, 'error').mockImplementation(() => undefined);
    function UsesApi() {
      useApi();
      return null;
    }
    function UsesSession() {
      useSession();
      return null;
    }
    expect(() => render(<UsesApi />)).toThrow('useApi must be used inside ApiProvider');
    expect(() => render(<UsesSession />)).toThrow('useSession must be used inside SessionProvider');
    error.mockRestore();
  });
});
