import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DeploymentFields } from '@/features/legal/DeploymentFields';
import { clearLegalCache } from '@/features/legal/legal-api';
import { CONFIGURED, CURRENT, HISTORY, LEGACY, legalFetch } from '@/features/legal/fixtures';
import { LegalPage, LegalPageView, versionParam } from '@/features/legal/LegalPage';
import { formatEffectiveDate } from '@/features/legal/messages';
import { RequestButton } from '@/features/legal/RequestButton';
import { RetentionTable } from '@/features/legal/RetentionTable';
import { VersionHistory } from '@/features/legal/VersionHistory';
import { SiteFooter } from '@/components/SiteFooter';

/**
 * Passo 16 / 17 (FR2.1, FR2.2, FR2.4; BR1.11, BR1.12, BR1.14): the public pages of the administrator app, outside the
 * administrator entry, and their parts.
 */
const GUEST = 'http://localhost:3000/';

beforeEach(() => clearLegalCache());

describe('LegalPageView of the administrator app', () => {
  it('a ready privacy page has the header, the document, the request to the guest entry and the footer', () => {
    render(
      <LegalPageView
        kind="privacy"
        data={{ state: 'ready', document: CURRENT, currentVersion: 'v2', history: HISTORY }}
        guestAppOrigin={GUEST}
      />,
    );

    expect(screen.getByRole('main')).toHaveAttribute('id', 'conteudo');
    expect(screen.getByRole('main')).toHaveAccessibleName('Política de privacidade');
    expect(screen.getByTestId('legal-request-button')).toHaveAttribute(
      'href',
      'http://localhost:3000/entrar',
    );
    expect(screen.getByTestId('legal-history')).toBeInTheDocument();
    expect(screen.getByTestId('site-footer')).toBeInTheDocument();
  });

  it('a failure shows "Tentar de novo" for the same address', () => {
    render(
      <LegalPageView
        kind="terms"
        data={{ state: 'error' }}
        requestedVersion="v1"
        guestAppOrigin={GUEST}
      />,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('Não foi possível carregar o documento');
    expect(screen.getByTestId('legal-retry')).toHaveAttribute('href', '/termos?versao=v1');
  });

  it('the retry of the current page has no version', () => {
    render(<LegalPageView kind="privacy" data={{ state: 'error' }} guestAppOrigin={GUEST} />);

    expect(screen.getByTestId('legal-retry')).toHaveAttribute('href', '/privacidade');
  });

  it('a missing version shows "Versão não encontrada" with the link to the current one', () => {
    render(<LegalPageView kind="terms" data={{ state: 'not-found' }} guestAppOrigin={GUEST} />);

    expect(
      screen.getByRole('heading', { level: 1, name: 'Versão não encontrada' }),
    ).toBeInTheDocument();
    expect(screen.getByTestId('legal-current-link')).toHaveAttribute('href', '/termos');
  });

  it('an older version without history shows the banner and no history', () => {
    render(
      <LegalPageView
        kind="terms"
        data={{ state: 'ready', document: LEGACY, currentVersion: 'v2', history: null }}
        guestAppOrigin={GUEST}
      />,
    );

    expect(screen.getByTestId('legal-old-version-banner')).toBeInTheDocument();
    expect(screen.queryByTestId('legal-history')).not.toBeInTheDocument();
  });
});

describe('LegalPage of the administrator app', () => {
  it('reads L1 on the server and renders the terms', async () => {
    const fetchImpl = legalFetch({
      '/current': { status: 200, body: CURRENT },
      '/versions': { status: 200, body: HISTORY },
    });

    render(
      await LegalPage({
        kind: 'terms',
        guestAppOrigin: GUEST,
        settings: { origin: 'http://api:8080', fetchImpl },
      }),
    );

    expect(screen.getByTestId('legal-page-terms')).toBeInTheDocument();
    expect(fetchImpl).toHaveBeenCalledWith(
      'http://api:8080/api/v1/legal/current',
      expect.anything(),
    );
    expect(versionParam(['v1', 'v2'])).toBe('v1');
    expect(versionParam(undefined)).toBeUndefined();
  });
});

describe('parts of the administrator legal pages', () => {
  it('the footer has the two legal links and never an accepted version', async () => {
    render(<SiteFooter />);

    expect(screen.getByRole('link', { name: 'Termos de uso' })).toHaveAttribute('href', '/termos');
    expect(screen.getByRole('link', { name: 'Privacidade' })).toHaveAttribute(
      'href',
      '/privacidade',
    );
    expect(screen.getByRole('contentinfo')).not.toHaveTextContent('Versão aceita');
    await userEvent.tab();
    expect(screen.getByTestId('site-footer-terms-link')).toHaveFocus();
  });

  it('the request button always leads to the guest entry, with the e-mail or "a definir"', () => {
    const { rerender } = render(<RequestButton dpoEmail={null} guestAppOrigin={GUEST} />);
    expect(screen.getByTestId('legal-request-dpo')).toHaveTextContent('a definir');

    rerender(<RequestButton dpoEmail="encarregado@example.test" guestAppOrigin="http://g" />);
    expect(screen.getByTestId('legal-request-button')).toHaveAttribute('href', 'http://g/entrar');
    expect(screen.getByTestId('legal-request-dpo')).toHaveAttribute(
      'href',
      'mailto:encarregado@example.test',
    );
  });

  it('the retention table has a caption, headers and labelled cells; an empty one says so', () => {
    const { rerender } = render(<RetentionTable items={CURRENT.retention} />);
    expect(screen.getAllByRole('columnheader')).toHaveLength(4);
    expect(within(screen.getByRole('table')).getByText('12 meses')).toHaveAttribute(
      'data-label',
      'Prazo',
    );

    rerender(<RetentionTable items={[]} />);
    expect(screen.getByTestId('legal-retention-empty')).toBeInTheDocument();
  });

  it('the deployment fields read "a definir" when absent and the configured values otherwise', () => {
    const { rerender } = render(
      <DeploymentFields storageLocation={null} internationalTransfer={null} dpoEmail={null} />,
    );
    expect(screen.getAllByText('a definir')).toHaveLength(3);

    rerender(<DeploymentFields {...CONFIGURED.deployment} />);
    expect(screen.getByTestId('legal-deployment-transfer')).toHaveTextContent(
      'Não há transferência',
    );
  });

  it('the history links the older versions and marks the current and the provisional ones', () => {
    const { rerender, container } = render(
      <VersionHistory versions={HISTORY} currentVersion="v2" basePath="/termos" />,
    );
    expect(screen.getByTestId('legal-history-link-v1')).toHaveAttribute(
      'href',
      '/termos?versao=v1',
    );
    expect(screen.getAllByRole('listitem')[0]).toHaveTextContent('(vigente)');
    expect(screen.getAllByRole('listitem')[1]).toHaveTextContent('(provisória)');

    rerender(<VersionHistory versions={[]} currentVersion="v2" basePath="/termos" />);
    expect(container).toBeEmptyDOMElement();
    expect(formatEffectiveDate('x')).toBe('x');
  });
});
