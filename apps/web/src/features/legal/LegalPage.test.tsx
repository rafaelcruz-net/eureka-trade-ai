import { render, screen } from '@testing-library/react';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { clearLegalCache } from '@/features/legal/legal-api';
import { CURRENT, HISTORY, LEGACY } from '@/features/legal/fixtures';
import { LegalPage, LegalPageView, versionParam } from '@/features/legal/LegalPage';
import { isLegalPath } from '@/components/DesktopOnlyNotice';

/**
 * Passo 16 / 17 (FR2.1, BR1.14, BR1.15; US2.1, US2.2): the server page of /termos and /privacidade, without a
 * session, with the header, the footer, the error with "Tentar de novo" and "Versão não encontrada".
 */
function renderView(node: React.ReactElement) {
  return render(<Providers api={fakeApi()}>{node}</Providers>);
}

beforeEach(() => clearLegalCache());

describe('LegalPageView', () => {
  it('a ready page has the skip target, the header with "Entrar", the document and the footer', () => {
    renderView(
      <LegalPageView
        kind="privacy"
        data={{ state: 'ready', document: CURRENT, currentVersion: 'v2', history: HISTORY }}
      />,
    );

    expect(screen.getByRole('main')).toHaveAttribute('id', 'conteudo');
    expect(screen.getByRole('main')).toHaveAccessibleName('Política de privacidade');
    expect(screen.getByTestId('legal-sign-in-link')).toHaveAttribute('href', '/entrar');
    expect(screen.getByTestId('legal-request-button')).toHaveAttribute('href', '/entrar');
    expect(screen.getByTestId('legal-history')).toBeInTheDocument();
    expect(screen.getByTestId('site-footer')).toBeInTheDocument();
    expect(screen.queryByTestId('site-footer-accepted-version')).not.toBeInTheDocument();
  });

  it('a failure shows the error with "Tentar de novo" for the same address', () => {
    renderView(<LegalPageView kind="terms" data={{ state: 'error' }} requestedVersion="v1" />);

    expect(screen.getByRole('alert')).toHaveTextContent('Não foi possível carregar o documento');
    expect(screen.getByTestId('legal-retry')).toHaveAttribute('href', '/termos?versao=v1');
    expect(screen.getByRole('main')).toHaveAccessibleName('Não foi possível carregar o documento');
  });

  it('the retry of the current page has no version', () => {
    renderView(<LegalPageView kind="privacy" data={{ state: 'error' }} />);

    expect(screen.getByTestId('legal-retry')).toHaveAttribute('href', '/privacidade');
  });

  it('a version that does not exist shows "Versão não encontrada" with the current one', () => {
    renderView(
      <LegalPageView kind="privacy" data={{ state: 'not-found' }} requestedVersion="v9" />,
    );

    expect(
      screen.getByRole('heading', { level: 1, name: 'Versão não encontrada' }),
    ).toBeInTheDocument();
    expect(screen.getByTestId('legal-current-link')).toHaveAttribute('href', '/privacidade');
  });

  it('an older version keeps the history and shows the banner', () => {
    renderView(
      <LegalPageView
        kind="terms"
        data={{ state: 'ready', document: LEGACY, currentVersion: 'v2', history: null }}
        requestedVersion="v1"
      />,
    );

    expect(screen.getByTestId('legal-old-version-banner')).toBeInTheDocument();
    expect(screen.queryByTestId('legal-history')).not.toBeInTheDocument();
  });
});

describe('LegalPage', () => {
  it('reads L1 on the server and renders the document', async () => {
    jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    const fetchImpl = jest.fn(async (input: string | URL | Request) => {
      const url = String(input);
      const body = url.endsWith('/versions') ? HISTORY : CURRENT;
      return { ok: true, status: 200, json: async () => body } as Response;
    }) as unknown as typeof fetch;

    const page = await LegalPage({
      kind: 'terms',
      settings: { origin: 'http://api:8080', fetchImpl },
    });
    renderView(page);

    expect(screen.getByTestId('legal-page-terms')).toBeInTheDocument();
    expect(screen.getByTestId('legal-version')).toHaveTextContent('Versão v2');
    expect(fetchImpl).toHaveBeenCalledWith(
      'http://api:8080/api/v1/legal/current',
      expect.anything(),
    );
  });

  it('takes the first value of a repeated ?versao= and ignores none', () => {
    expect(versionParam(['v1', 'v2'])).toBe('v1');
    expect(versionParam('v2')).toBe('v2');
    expect(versionParam(undefined)).toBeUndefined();
  });

  it('the legal pages never show the desktop-only notice', () => {
    expect(isLegalPath('/termos')).toBe(true);
    expect(isLegalPath('/privacidade')).toBe(true);
    expect(isLegalPath('/privacidade/x')).toBe(true);
    expect(isLegalPath('/conta')).toBe(false);
  });
});
