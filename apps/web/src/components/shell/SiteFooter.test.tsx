import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { tokenStore } from '@/lib/auth/token-store';
import { ApiError } from '@/lib/api/client';
import { ACCOUNT, fakeApi, Providers } from '@/features/auth/test-support';
import { resetSessionAccountCache } from '@/components/shell/session-account';
import { SessionSiteFooter, SiteFooter } from '@/components/shell/SiteFooter';

/**
 * Passo 17 (FR2.2, BR1.11; AC2.2.1, AC2.2.2): the footer of every screen has the two legal links; only with a session
 * it shows the accepted version, read once per session together with the masked wallet.
 */
beforeEach(() => resetSessionAccountCache());
afterEach(() => {
  tokenStore.clear();
  jest.restoreAllMocks();
});

describe('SiteFooter', () => {
  it('has the links to "Termos de uso" and "Privacidade" in a labelled navigation', () => {
    render(<SiteFooter />);

    const footer = screen.getByRole('contentinfo');
    expect(screen.getByRole('navigation', { name: 'Documentos legais' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Termos de uso' })).toHaveAttribute('href', '/termos');
    expect(screen.getByRole('link', { name: 'Privacidade' })).toHaveAttribute(
      'href',
      '/privacidade',
    );
    expect(footer).not.toHaveTextContent('Versão aceita');
  });

  it('shows the accepted version when it has one', () => {
    render(<SiteFooter acceptedVersion="v2" />);

    expect(screen.getByTestId('site-footer-accepted-version')).toHaveTextContent(
      'Versão aceita: v2',
    );
  });

  it('the links are reachable by keyboard in order', async () => {
    render(<SiteFooter />);

    await userEvent.tab();
    expect(screen.getByTestId('site-footer-terms-link')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('site-footer-privacy-link')).toHaveFocus();
  });
});

describe('SessionSiteFooter', () => {
  it('without a session shows only the links and reads no account', async () => {
    const api = fakeApi();
    render(
      <Providers api={api}>
        <SessionSiteFooter />
      </Providers>,
    );

    await waitFor(() => expect(api.refreshSession).toHaveBeenCalled());
    expect(screen.queryByTestId('site-footer-accepted-version')).not.toBeInTheDocument();
    expect(api.getAccount).not.toHaveBeenCalled();
  });

  it('with a session shows the accepted version of the account', async () => {
    tokenStore.set('access-token-value');
    render(
      <Providers api={fakeApi()}>
        <SessionSiteFooter />
      </Providers>,
    );

    expect(
      await screen.findByText(`Versão aceita: ${ACCOUNT.acceptedTerms?.version}`),
    ).toBeInTheDocument();
  });

  it('an account without an accepted version shows only the links', async () => {
    tokenStore.set('access-token-value');
    const api = fakeApi({
      getAccount: jest.fn(async () => ({ ...ACCOUNT, acceptedTerms: undefined })),
    });
    render(
      <Providers api={api}>
        <SessionSiteFooter />
      </Providers>,
    );

    await waitFor(() => expect(api.getAccount).toHaveBeenCalled());
    expect(screen.queryByTestId('site-footer-accepted-version')).not.toBeInTheDocument();
  });

  it('a failed read of the account shows only the links and warns without details', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    tokenStore.set('access-token-value');
    const api = fakeApi({
      getAccount: jest.fn(async () => {
        throw new ApiError(503, 'SERVICE_UNAVAILABLE');
      }),
    });
    render(
      <Providers api={api}>
        <SessionSiteFooter />
      </Providers>,
    );

    await waitFor(() =>
      expect(warn).toHaveBeenCalledWith('Account of the session could not be read', 'ApiError'),
    );
    expect(screen.queryByTestId('site-footer-accepted-version')).not.toBeInTheDocument();
  });
});
