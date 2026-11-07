import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import { tokenStore } from '@/lib/auth/token-store';
import { messages } from '@/lib/i18n/messages';
import { ACCEPTANCE_STORAGE_KEY } from '@/features/auth/acceptance-store';
import { InviteScreen, PHANTOM_DOWNLOAD, SOLFLARE_DOWNLOAD } from '@/features/auth/InviteScreen';
import { fakeApi, fakeWallet, INVITE, Providers } from '@/features/auth/test-support';
import type { WalletPort } from '@/features/auth/wallets';
import InvitePage from '@/app/convite/[codigo]/page';
import InviteWithoutCodePage from '@/app/convite/page';

const replace = jest.fn();
const push = jest.fn();

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push }),
  useSearchParams: () => new URLSearchParams(),
}));

const CODE = 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8';
const text = messages.invite;

function walletsSource(wallets: WalletPort[]) {
  return (onChange: (w: WalletPort[]) => void) => {
    onChange(wallets);
    return () => undefined;
  };
}

function renderInvite(
  api = fakeApi(),
  wallets: WalletPort[] = [fakeWallet()],
  code: string | null = CODE,
) {
  const view = render(
    <Providers api={api}>
      <InviteScreen
        code={code ?? undefined}
        walletSource={walletsSource(wallets)}
        timeZone="America/Sao_Paulo"
      />
    </Providers>,
  );
  return { api, ...view };
}

async function acceptBoth() {
  await userEvent.click(await screen.findByTestId('invite-accept-terms'));
  await userEvent.click(screen.getByTestId('invite-accept-risk'));
}

describe('invitation entry /convite/[codigo]', () => {
  beforeEach(() => {
    replace.mockReset();
    push.mockReset();
    tokenStore.clear();
    window.sessionStorage.clear();
  });

  it('shows the loading state, then the terms and the risk notice as plain-text paragraphs', async () => {
    renderInvite();

    expect(screen.getByTestId('invite-loading')).toHaveTextContent(text.loading);
    const terms = await screen.findByTestId('invite-terms');
    expect(terms.querySelectorAll('p')).toHaveLength(2);
    expect(terms).toHaveTextContent('Versão provisória, pendente de revisão jurídica.');
    expect(screen.getByTestId('invite-risk')).toHaveTextContent(
      'O modo REAL está indisponível no beta.',
    );
    expect(screen.getByText(text.termsHeading('v1'))).toBeInTheDocument();
    expect(screen.getByTestId('invite-valid-until')).toHaveTextContent('29/10/2026');
  });

  it('keeps "Conectar carteira" unavailable, with the reason written, until both marks are made', async () => {
    renderInvite();

    await userEvent.click(await screen.findByTestId('invite-accept-terms'));

    expect(screen.getByTestId('invite-connect-disabled')).toBeDisabled();
    expect(screen.getByTestId('invite-connect-disabled')).toHaveAccessibleDescription(
      text.connectUnavailable,
    );
    expect(screen.getByTestId('invite-connect-reason')).toHaveTextContent(text.connectUnavailable);
    await userEvent.click(screen.getByTestId('invite-accept-risk'));
    expect(screen.queryByTestId('invite-connect-disabled')).toBeNull();
    expect(screen.getByTestId('invite-connect-phantom')).toBeEnabled();
  });

  it('creates the account with the code and the accepted version, opens the session and goes home', async () => {
    const { api } = renderInvite();
    await acceptBoth();

    await userEvent.click(screen.getByTestId('invite-connect-phantom'));

    // U9 (BR1.1): the home page is the dashboard.
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/'));
    expect(api.verifyLogin).toHaveBeenCalledWith(
      expect.objectContaining({
        inviteCode: CODE,
        acceptedTermsVersion: 'v1',
        provider: 'PHANTOM',
      }),
    );
    expect(tokenStore.get()).toBe('access-token-value');
    expect(window.sessionStorage.getItem(ACCEPTANCE_STORAGE_KEY)).toBeNull();
  });

  it('keeps the marks in the browser session when the person leaves and comes back, never the code', async () => {
    const first = renderInvite();
    await acceptBoth();
    first.unmount();

    renderInvite();

    expect(await screen.findByTestId('invite-accept-terms')).toBeChecked();
    expect(screen.getByTestId('invite-accept-risk')).toBeChecked();
    expect(window.sessionStorage.getItem(ACCEPTANCE_STORAGE_KEY)).not.toContain(CODE);
  });

  it('does not reuse marks saved for another version of the terms', async () => {
    window.sessionStorage.setItem(
      ACCEPTANCE_STORAGE_KEY,
      JSON.stringify({ version: 'v0', terms: true, risk: true }),
    );

    renderInvite();

    expect(await screen.findByTestId('invite-accept-terms')).not.toBeChecked();
  });

  it('without a detected wallet links to install Phantom or Solflare and keeps the marks', async () => {
    renderInvite(fakeApi(), []);
    await acceptBoth();

    expect(screen.getByTestId('invite-no-wallet')).toHaveTextContent(text.noWallet);
    expect(screen.getByTestId('invite-install-phantom')).toHaveAttribute('href', PHANTOM_DOWNLOAD);
    expect(screen.getByTestId('invite-install-solflare')).toHaveAttribute(
      'href',
      SOLFLARE_DOWNLOAD,
    );
    expect(JSON.parse(window.sessionStorage.getItem(ACCEPTANCE_STORAGE_KEY) ?? '{}')).toEqual({
      version: 'v1',
      terms: true,
      risk: true,
    });
  });

  it('without a code shows the field to paste it and where to find it', async () => {
    renderInvite(fakeApi(), [fakeWallet()], null);

    expect(screen.getByLabelText(text.codeLabel)).toHaveAccessibleDescription(text.codeHelp);
    await userEvent.type(screen.getByTestId('invite-code-input'), `  ${CODE} `);
    await userEvent.click(screen.getByTestId('invite-code-submit'));

    expect(push).toHaveBeenCalledWith(`/convite/${CODE}`);
  });

  it.each([
    ['EXPIRED', text.status.EXPIRED],
    ['REVOKED', text.status.REVOKED],
    ['USED', text.status.USED],
  ])(
    'an invitation %s shows the state in text, asks for another and offers no connect button',
    async (status, message) => {
      renderInvite(
        fakeApi({ lookupInvite: jest.fn(async () => ({ ...INVITE, status: status as 'USED' })) }),
      );

      const notice = await screen.findByTestId('invite-status');
      expect(notice).toHaveTextContent(message as string);
      expect(notice).toHaveTextContent(text.askAnother);
      expect(screen.queryByRole('button', { name: /Conectar/ })).toBeNull();
    },
  );

  it('an unknown code, too many lookups and a technical error are distinct states', async () => {
    const lookupInvite = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(404, 'INVITE_NOT_FOUND'))
      .mockRejectedValueOnce(new ApiError(429, 'RATE_LIMITED', 'IP_LIMIT', 'c', [], [], 42))
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK'))
      .mockResolvedValueOnce(INVITE);
    const api = fakeApi({ lookupInvite });

    const first = renderInvite(api);
    expect(await screen.findByTestId('invite-not-found')).toHaveTextContent(text.notFound);
    first.unmount();
    const second = renderInvite(api);
    expect(await screen.findByTestId('invite-rate-limited')).toHaveTextContent(
      text.rateLimited(42),
    );
    second.unmount();
    renderInvite(api);
    expect(await screen.findByTestId('invite-error')).toHaveTextContent(text.errorTitle);

    await userEvent.click(screen.getByTestId('invite-retry'));
    expect(await screen.findByTestId('invite-terms')).toBeInTheDocument();
  });

  it('a wallet that already has an account is sent to the normal entry', async () => {
    renderInvite(
      fakeApi({
        verifyLogin: jest.fn(async () =>
          Promise.reject(new ApiError(409, 'WALLET_ALREADY_LINKED')),
        ),
      }),
    );
    await acceptBoth();

    await userEvent.click(screen.getByTestId('invite-connect-phantom'));

    expect(await screen.findByTestId('invite-wallet-linked')).toHaveTextContent(text.walletLinked);
    expect(screen.getByTestId('invite-go-to-entry')).toHaveAttribute('href', '/entrar');
    expect(tokenStore.get()).toBeNull();
  });

  it('an invitation revoked during the acceptance shows the state; a refused signature offers a new attempt', async () => {
    const verifyLogin = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(401, 'LOGIN_CHALLENGE_REJECTED', 'EXPIRED'))
      .mockRejectedValueOnce(new ApiError(422, 'INVITE_INVALID', 'REVOKED'));
    const { api } = renderInvite(fakeApi({ verifyLogin }));
    await acceptBoth();

    await userEvent.click(screen.getByTestId('invite-connect-phantom'));
    expect(await screen.findByTestId('invite-rejected-reason')).toHaveTextContent(
      text.rejected.EXPIRED as string,
    );
    await userEvent.click(screen.getByTestId('invite-try-again'));
    await userEvent.click(screen.getByTestId('invite-connect-phantom'));

    expect(await screen.findByTestId('invite-status')).toHaveTextContent(
      text.status.REVOKED as string,
    );
    expect(api.requestChallenge).toHaveBeenCalledTimes(2);
  });

  it('a tenant that is not active and an administrator wallet are refused in text', async () => {
    const verifyLogin = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(422, 'INVITE_INVALID', 'TENANT_INACTIVE'))
      .mockRejectedValueOnce(new ApiError(403, 'FORBIDDEN_ROLE', 'ADMIN_WALLET'));
    renderInvite(fakeApi({ verifyLogin }));
    await acceptBoth();

    await userEvent.click(screen.getByTestId('invite-connect-phantom'));
    expect(await screen.findByTestId('invite-rejected-reason')).toHaveTextContent(
      text.rejected.TENANT_INACTIVE as string,
    );
    await userEvent.click(screen.getByTestId('invite-try-again'));
    await userEvent.click(screen.getByTestId('invite-connect-phantom'));
    expect(await screen.findByTestId('invite-rejected-reason')).toHaveTextContent(
      text.rejected.ADMIN_WALLET as string,
    );
  });

  it('when the terms changed it reloads them and asks for the marks again', async () => {
    const lookupInvite = jest
      .fn()
      .mockResolvedValueOnce(INVITE)
      .mockResolvedValueOnce({ ...INVITE, termsVersion: 'v2', termsText: 'Termos novos.' });
    renderInvite(
      fakeApi({
        lookupInvite,
        verifyLogin: jest.fn(async () => Promise.reject(new ApiError(422, 'TERMS_NOT_ACCEPTED'))),
      }),
    );
    await acceptBoth();

    await userEvent.click(screen.getByTestId('invite-connect-phantom'));

    expect(await screen.findByTestId('invite-terms-changed')).toHaveTextContent(text.termsChanged);
    expect(await screen.findByText(text.termsHeading('v2'))).toBeInTheDocument();
    expect(screen.getByTestId('invite-accept-terms')).not.toBeChecked();
  });

  it('a code unknown at the verification shows "not found"', async () => {
    renderInvite(
      fakeApi({
        verifyLogin: jest.fn(async () => Promise.reject(new ApiError(404, 'INVITE_NOT_FOUND'))),
      }),
    );
    await acceptBoth();

    await userEvent.click(screen.getByTestId('invite-connect-phantom'));

    expect(await screen.findByTestId('invite-not-found')).toBeInTheDocument();
  });

  it('never interprets HTML in the terms and never asks for a secret phrase or key', async () => {
    renderInvite(
      fakeApi({ lookupInvite: jest.fn(async () => ({ ...INVITE, termsText: '<b>negrito</b>' })) }),
    );

    const terms = await screen.findByTestId('invite-terms');
    expect(terms).toHaveTextContent('<b>negrito</b>');
    expect(terms.querySelector('b')).toBeNull();
    expect(screen.getByText(text.neverAskSecret)).toBeInTheDocument();
    expect(screen.queryAllByRole('textbox')).toHaveLength(0);
  });

  it('is operable by keyboard with visible labels on both marks and the heading focused first', async () => {
    renderInvite();

    expect(screen.getByRole('heading', { level: 1, name: text.title })).toHaveFocus();
    expect(await screen.findByLabelText(text.acceptTerms)).toBeInTheDocument();
    expect(screen.getByLabelText(text.acceptRisk)).toBeInTheDocument();
    await userEvent.tab();
    expect(screen.getByTestId('invite-accept-terms')).toHaveFocus();
    await userEvent.keyboard(' ');
    await userEvent.tab();
    await userEvent.keyboard(' ');
    await userEvent.tab();
    expect(screen.getByTestId('invite-connect-phantom')).toHaveFocus();
  });

  it('the pages render the invitation entry with and without a code', async () => {
    render(
      <Providers api={fakeApi()}>
        {await InvitePage({ params: Promise.resolve({ codigo: CODE }) })}
      </Providers>,
    );
    expect(await screen.findByRole('heading', { level: 1, name: text.title })).toBeInTheDocument();
    render(
      <Providers api={fakeApi()}>
        <InviteWithoutCodePage />
      </Providers>,
    );
    expect(screen.getAllByTestId('invite-code-form')).toHaveLength(1);
  });
});
