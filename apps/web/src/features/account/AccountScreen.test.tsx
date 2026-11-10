import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import { tokenStore } from '@/lib/auth/token-store';
import { messages } from '@/lib/i18n/messages';
import { AccountScreen } from '@/features/account/AccountScreen';
import { ACCOUNT, fakeApi, Providers } from '@/features/auth/test-support';
import AccountPage from '@/app/conta/page';

const replace = jest.fn();

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: replace }),
  useSearchParams: () => new URLSearchParams(),
}));

const text = messages.account;

function renderAccount(api = fakeApi(), timeZone = 'America/Sao_Paulo') {
  tokenStore.set('access-token-value');
  render(
    <Providers api={api}>
      <AccountScreen timeZone={timeZone} />
    </Providers>,
  );
  return api;
}

describe('Account screen /conta', () => {
  beforeEach(() => {
    replace.mockReset();
    tokenStore.clear();
  });

  it('shows the loading state and then the abbreviated wallet, the provider and the session end', async () => {
    renderAccount();

    expect(screen.getByTestId('account-loading')).toHaveTextContent(text.loading);
    expect(await screen.findByTestId('account-wallet')).toHaveTextContent('TestW…1111');
    expect(screen.getByTestId('account-provider')).toHaveTextContent('Solflare');
    // 2026-10-29T00:00:00Z is 21:00 of the day before in São Paulo (UTC-3).
    expect(screen.getByTestId('account-session')).toHaveTextContent(text.sessionEnds('21:00'));
  });

  it('converts the session end to the browser time zone', async () => {
    renderAccount(fakeApi(), 'UTC');

    expect(await screen.findByTestId('account-session')).toHaveTextContent(
      text.sessionEnds('00:00'),
    );
  });

  it('the wallet is read-only text with no control to change or unlink it', async () => {
    renderAccount();

    await screen.findByTestId('account-wallet');
    // u4-data-requests: the form of the section "Meus dados (LGPD)" is the only control of the screen; the account
    // details themselves have none.
    expect(
      screen.getByTestId('account-details').querySelectorAll('input, textarea, select'),
    ).toHaveLength(0);
    expect(
      screen.getByTestId('data-requests-section').querySelectorAll('input, textarea, select'),
    ).toHaveLength(2);
    expect(screen.queryByRole('button', { name: /desvincular|desconectar|trocar/i })).toBeNull();
    expect(screen.getByText(text.walletHelp)).toBeInTheDocument();
  });

  it('"ver" shows the version, the acceptance date in the browser time zone and the text of that version', async () => {
    renderAccount();

    expect(await screen.findByTestId('account-terms-version')).toHaveTextContent(
      text.termsVersion('v1'),
    );
    await userEvent.click(screen.getByTestId('account-terms-toggle'));

    const detail = screen.getByTestId('account-terms-detail');
    expect(within(detail).getByTestId('account-terms-accepted-at')).toHaveTextContent(
      text.acceptedAt('28/10/2026, 09:00:00'),
    );
    expect(within(detail).getByTestId('account-terms-text').querySelectorAll('p')).toHaveLength(2);
    expect(within(detail).getByTestId('account-risk-text')).toHaveTextContent(
      'Texto do aviso de risco v1.',
    );
    expect(screen.getByTestId('account-terms-toggle')).toHaveAttribute('aria-expanded', 'true');
    await userEvent.click(screen.getByTestId('account-terms-toggle'));
    expect(screen.queryByTestId('account-terms-detail')).toBeNull();
  });

  it('shows "Modo REAL: indisponível no beta" as text and no control for REAL at all', async () => {
    renderAccount();

    expect(await screen.findByTestId('account-real-mode')).toHaveTextContent(text.realMode);
    const controls = [
      ...screen.queryAllByRole('button'),
      ...screen.queryAllByRole('link'),
      ...screen.queryAllByRole('checkbox'),
      ...screen.queryAllByRole('switch'),
    ];
    expect(controls.some((control) => /real/i.test(control.textContent ?? ''))).toBe(false);
  });

  /** u3-reacceptance (BR3.10, F5): the situation before the current version on the Account. */
  it('an account up to date shows the accepted version and no way to the new acceptance', async () => {
    renderAccount();

    expect(await screen.findByTestId('terms-status-up-to-date')).toBeInTheDocument();
    expect(screen.getByTestId('terms-status-accepted')).toHaveTextContent('versão v1');
    expect(screen.queryByTestId('terms-status-accept-link')).toBeNull();
  });

  it('an outdated account offers "Aceitar a nova versão" to /novo-aceite and back to /conta', async () => {
    renderAccount(
      fakeApi({
        getAccount: jest.fn(async () => ({
          ...ACCOUNT,
          terms: {
            acceptedVersion: 'v1',
            currentVersion: 'v2',
            acceptedAt: '2026-10-28T12:00:00Z',
            upToDate: false,
          },
        })),
      }),
    );

    const link = await screen.findByTestId('terms-status-accept-link');
    expect(link).toHaveAttribute('href', '/novo-aceite?voltar=%2Fconta');
    expect(screen.getByTestId('terms-status-outdated')).toHaveTextContent('v2');
  });

  it('an account without any acceptance also offers the new acceptance', async () => {
    renderAccount(
      fakeApi({
        getAccount: jest.fn(async () => ({
          ...ACCOUNT,
          acceptedTerms: undefined,
          terms: undefined,
        })),
      }),
    );

    expect(await screen.findByTestId('terms-status-accept-link')).toBeInTheDocument();
  });

  it('an account without accepted terms says so', async () => {
    renderAccount(
      fakeApi({ getAccount: jest.fn(async () => ({ ...ACCOUNT, acceptedTerms: undefined })) }),
    );

    expect(await screen.findByTestId('account-no-terms')).toHaveTextContent(text.noTerms);
    expect(screen.queryByTestId('account-terms-toggle')).toBeNull();
  });

  it('a version whose text is not available says so instead of showing nothing', async () => {
    renderAccount(
      fakeApi({
        getAccount: jest.fn(async () => ({
          ...ACCOUNT,
          acceptedTerms: {
            version: 'v0',
            acceptedAt: ACCOUNT.sessionExpiresAt,
            termsText: null,
            riskNoticeText: null,
          },
        })),
      }),
    );

    await userEvent.click(await screen.findByTestId('account-terms-toggle'));

    expect(screen.getAllByText(text.textUnavailable)).toHaveLength(2);
  });

  it('a technical error is a distinct state with a retry', async () => {
    // U9: the wallet of the shell reads the account too; both first reads fail.
    const getAccount = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
      .mockResolvedValue(ACCOUNT);
    renderAccount(fakeApi({ getAccount }));

    expect(await screen.findByTestId('account-error')).toHaveTextContent(text.errorTitle);
    expect(screen.getByTestId('app-wallet-badge')).toHaveTextContent(/^Carteira$/);
    await userEvent.click(screen.getByTestId('account-retry'));
    expect(await screen.findByTestId('account-wallet')).toBeInTheDocument();
  });

  it('a session that expired leads to the entry with the expired notice and the origin /conta', async () => {
    renderAccount(
      fakeApi({
        getAccount: jest.fn(async () => Promise.reject(new ApiError(401, 'UNAUTHENTICATED'))),
      }),
    );

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/entrar?origem=%2Fconta&expirada=1'));
    expect(tokenStore.get()).toBeNull();
  });

  it('without a session it goes to the entry keeping the origin', async () => {
    render(
      <Providers api={fakeApi()}>
        <AccountScreen />
      </Providers>,
    );

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/entrar?origem=%2Fconta'));
  });

  it('"Sair" ends the session, drops the token and goes back to the entry', async () => {
    const api = renderAccount();
    await screen.findByTestId('account-wallet');

    await userEvent.click(screen.getByTestId('app-sign-out-button'));

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/entrar'));
    expect(api.logout).toHaveBeenCalledTimes(1);
    expect(tokenStore.get()).toBeNull();
  });

  it('the main navigation links to /conta, marks it current and offers "Sair"; the page renders the screen', async () => {
    tokenStore.set('access-token-value');
    render(
      <Providers api={fakeApi()}>
        <AccountPage />
      </Providers>,
    );

    const menu = screen.getByTestId('app-primary-nav');
    expect(within(menu).getByTestId('app-nav-account-link')).toHaveAttribute('href', '/conta');
    expect(within(menu).getByTestId('app-nav-account-link')).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.getByTestId('app-shell')).toContainElement(
      screen.getByTestId('app-sign-out-button'),
    );
    expect(screen.getByTestId('app-sign-out-button')).toHaveTextContent(messages.app.signOut);
    expect(screen.getByRole('heading', { level: 1, name: text.title })).toHaveFocus();
    expect(await screen.findByTestId('account-details')).toBeInTheDocument();
  });
});
