import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CONFIGURED, CURRENT, LEGACY } from '@/features/legal/fixtures';
import { LegalDocument } from '@/features/legal/LegalDocument';
import { legalMessages } from '@/features/legal/messages';

/**
 * Passo 17 (BR1.4, BR1.13 to BR1.15; NFR4.1, NFR6.4): the document of /termos and /privacidade renders text as text,
 * the contents move the focus, and the version, publication, PAPER and legacy states are visible.
 */
describe('LegalDocument', () => {
  it('shows a <script> of the text literally, never as markup', () => {
    const { container } = render(<LegalDocument document={CURRENT} kind="terms" isCurrent />);

    expect(screen.getByText('Texto com <script>alert(1)</script> literal.')).toBeInTheDocument();
    expect(container.querySelector('script')).toBeNull();
  });

  it('has the h1 of the document, the version with the UTC date and h2 per section', () => {
    render(<LegalDocument document={CURRENT} kind="terms" isCurrent />);

    expect(screen.getByRole('heading', { level: 1, name: 'Termos de uso' })).toBeInTheDocument();
    expect(screen.getByTestId('legal-version')).toHaveTextContent(
      'Versão v2 · vigente desde 15/10/2026 (UTC)',
    );
    expect(screen.getByRole('heading', { level: 2, name: 'Uso do beta' })).toHaveAttribute(
      'id',
      'uso-do-beta',
    );
    expect(screen.getByRole('heading', { level: 2, name: 'Contas' })).toBeInTheDocument();
  });

  it('lists every section in "Nesta página" with its anchor', () => {
    render(<LegalDocument document={CURRENT} kind="terms" isCurrent />);

    const toc = screen.getByRole('navigation', { name: 'Nesta página' });
    const links = within(toc).getAllByRole('link');
    expect(links.map((link) => link.getAttribute('href'))).toEqual(['#uso-do-beta', '#contas']);
  });

  it('a link of the contents moves the focus to the heading of its section', async () => {
    render(<LegalDocument document={CURRENT} kind="terms" isCurrent />);

    await userEvent.click(screen.getByTestId('legal-toc-link-contas'));

    expect(screen.getByRole('heading', { level: 2, name: 'Contas' })).toHaveFocus();
    expect(window.location.hash).toBe('#contas');
  });

  it('the toggle of the contents opens and closes it by keyboard', async () => {
    render(<LegalDocument document={CURRENT} kind="terms" isCurrent />);
    const toggle = screen.getByTestId('legal-toc-toggle');

    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByTestId('legal-toc')).toHaveAttribute('data-open', 'false');
    toggle.focus();
    await userEvent.keyboard('{Enter}');
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(toggle).toHaveTextContent(legalMessages.tocToggleClose);
    await userEvent.keyboard(' ');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
  });

  it('an old version shows the banner with a link to the current one', () => {
    render(<LegalDocument document={LEGACY} kind="terms" isCurrent={false} />);

    const banner = screen.getByTestId('legal-old-version-banner');
    expect(banner).toHaveTextContent('Esta não é a versão vigente.');
    expect(within(banner).getByRole('link', { name: 'Ver a versão vigente' })).toHaveAttribute(
      'href',
      '/termos',
    );
  });

  it('the current version shows no banner', () => {
    render(<LegalDocument document={CURRENT} kind="terms" isCurrent />);

    expect(screen.queryByTestId('legal-old-version-banner')).not.toBeInTheDocument();
  });

  it('a version not publishable says it was not published for guests; a publishable one does not', () => {
    const { unmount } = render(<LegalDocument document={CURRENT} kind="privacy" isCurrent />);
    expect(screen.getByTestId('legal-not-publishable')).toHaveTextContent(
      legalMessages.notPublishable,
    );
    unmount();

    render(<LegalDocument document={CONFIGURED} kind="privacy" isCurrent />);
    expect(screen.queryByTestId('legal-not-publishable')).not.toBeInTheDocument();
  });

  it('always shows the PAPER notice and the summary of the document', () => {
    render(<LegalDocument document={CURRENT} kind="privacy" isCurrent />);

    expect(screen.getByTestId('legal-paper-notice')).toHaveTextContent('PAPER');
    expect(within(screen.getByTestId('legal-summary')).getAllByRole('listitem')).toHaveLength(6);
  });

  it('the terms page also shows the risk notice of the version', () => {
    render(<LegalDocument document={CURRENT} kind="terms" isCurrent />);

    expect(screen.getByTestId('legal-risk-notice')).toHaveTextContent('Tokens são voláteis.');
    expect(screen.queryByTestId('legal-retention')).not.toBeInTheDocument();
  });

  it('the privacy page shows the retention table, the fields "a definir" and the request slot', () => {
    render(
      <LegalDocument
        document={CURRENT}
        kind="privacy"
        isCurrent
        requestSlot={<p data-testid="slot">pedido</p>}
      />,
    );

    expect(screen.getAllByTestId('legal-retention-row')).toHaveLength(2);
    expect(screen.getByTestId('legal-deployment-storage')).toHaveTextContent('a definir');
    expect(screen.getByTestId('legal-deployment-dpo')).toHaveTextContent('a definir');
    expect(screen.getByTestId('slot')).toBeInTheDocument();
  });

  it('legacy terms show the text without summary and without contents', () => {
    render(<LegalDocument document={LEGACY} kind="terms" isCurrent />);

    expect(screen.getByText('Termos antigos.')).toBeInTheDocument();
    expect(screen.queryByTestId('legal-summary')).not.toBeInTheDocument();
    expect(screen.queryByTestId('legal-toc')).not.toBeInTheDocument();
    expect(screen.getByTestId('legal-version')).toHaveTextContent('(versão provisória)');
  });

  it('a legacy version without policy says so, with the e-mail "a definir" and the request slot', () => {
    render(
      <LegalDocument
        document={LEGACY}
        kind="privacy"
        isCurrent
        requestSlot={<p data-testid="slot">pedido</p>}
      />,
    );

    expect(screen.getByTestId('legal-privacy-missing')).toHaveTextContent(
      legalMessages.privacyMissing,
    );
    expect(screen.getByTestId('legal-missing-dpo')).toHaveTextContent('a definir');
    expect(screen.getByTestId('slot')).toBeInTheDocument();
    expect(screen.queryByTestId('legal-retention')).not.toBeInTheDocument();
    expect(screen.getByTestId('legal-paper-notice')).toBeInTheDocument();
  });

  it('a legacy policy with a configured e-mail shows it as mailto', () => {
    render(
      <LegalDocument
        document={{ ...LEGACY, deployment: CONFIGURED.deployment }}
        kind="privacy"
        isCurrent
      />,
    );

    expect(screen.getByTestId('legal-missing-dpo')).toHaveAttribute(
      'href',
      'mailto:encarregado@example.test',
    );
  });
});
