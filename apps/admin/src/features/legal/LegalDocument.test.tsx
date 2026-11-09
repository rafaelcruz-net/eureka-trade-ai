import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CONFIGURED, CURRENT, LEGACY } from '@/features/legal/fixtures';
import { LegalDocument } from '@/features/legal/LegalDocument';
import { legalMessages } from '@/features/legal/messages';

/**
 * Passo 17 (BR1.4, BR1.13 to BR1.15; NFR4.1, NFR6.4): the document of the administrator app renders text as text,
 * the contents move the focus, and the version, publication, PAPER and legacy states are visible.
 */
describe('LegalDocument of the administrator app', () => {
  it('shows a <script> of the text literally, never as markup', () => {
    const { container } = render(<LegalDocument document={CURRENT} kind="terms" isCurrent />);

    expect(screen.getByText('Texto com <script>alert(1)</script> literal.')).toBeInTheDocument();
    expect(container.querySelector('script')).toBeNull();
  });

  it('has the h1, the version with the UTC date, the summary and h2 per section', () => {
    render(<LegalDocument document={CURRENT} kind="terms" isCurrent />);

    expect(screen.getByRole('heading', { level: 1, name: 'Termos de uso' })).toBeInTheDocument();
    expect(screen.getByTestId('legal-version')).toHaveTextContent('15/10/2026 (UTC)');
    expect(within(screen.getByTestId('legal-summary')).getAllByRole('listitem')).toHaveLength(5);
    expect(screen.getByRole('heading', { level: 2, name: 'Contas' })).toHaveAttribute(
      'id',
      'contas',
    );
  });

  it('a link of the contents moves the focus to its section', async () => {
    render(<LegalDocument document={CURRENT} kind="terms" isCurrent />);

    await userEvent.click(screen.getByTestId('legal-toc-link-uso-do-beta'));

    expect(screen.getByRole('heading', { level: 2, name: 'Uso do beta' })).toHaveFocus();
  });

  it('the toggle of the contents is a keyboard button with aria-expanded', async () => {
    render(<LegalDocument document={CURRENT} kind="terms" isCurrent />);
    const toggle = screen.getByTestId('legal-toc-toggle');

    toggle.focus();
    await userEvent.keyboard('{Enter}');

    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('legal-toc')).toHaveAttribute('data-open', 'true');
  });

  it('an old version shows the banner; a version not publishable says so', () => {
    render(<LegalDocument document={LEGACY} kind="terms" isCurrent={false} />);

    expect(screen.getByTestId('legal-old-version-banner')).toHaveTextContent(
      legalMessages.notCurrent,
    );
    expect(screen.getByTestId('legal-not-publishable')).toBeInTheDocument();
    expect(screen.getByTestId('legal-version')).toHaveTextContent('(versão provisória)');
  });

  it('a publishable current version has neither banner nor notice, but always the PAPER notice', () => {
    render(<LegalDocument document={CONFIGURED} kind="privacy" isCurrent />);

    expect(screen.queryByTestId('legal-old-version-banner')).not.toBeInTheDocument();
    expect(screen.queryByTestId('legal-not-publishable')).not.toBeInTheDocument();
    expect(screen.getByTestId('legal-paper-notice')).toHaveTextContent('PAPER');
  });

  it('the terms show the risk notice with one h3 per section when it has several', () => {
    render(<LegalDocument document={CURRENT} kind="terms" isCurrent />);

    const risk = screen.getByTestId('legal-risk-notice');
    expect(
      within(risk)
        .getAllByRole('heading', { level: 3 })
        .map((h) => h.textContent),
    ).toEqual(['Volatilidade', 'Simulação']);
  });

  it('the privacy page shows the table, the fields and the request slot', () => {
    render(
      <LegalDocument
        document={CONFIGURED}
        kind="privacy"
        isCurrent
        requestSlot={<p data-testid="slot">pedido</p>}
      />,
    );

    expect(screen.getAllByTestId('legal-retention-row')).toHaveLength(1);
    expect(screen.getByTestId('legal-deployment-storage')).toHaveTextContent('Brasil');
    expect(screen.getByTestId('slot')).toBeInTheDocument();
  });

  it('a legacy version without policy says so with the e-mail and the request slot', () => {
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
  });

  it('legacy terms have no summary and no contents', () => {
    render(<LegalDocument document={LEGACY} kind="terms" isCurrent />);

    expect(screen.queryByTestId('legal-summary')).not.toBeInTheDocument();
    expect(screen.queryByTestId('legal-toc')).not.toBeInTheDocument();
    expect(screen.getByText('Termos antigos.')).toBeInTheDocument();
  });
});
