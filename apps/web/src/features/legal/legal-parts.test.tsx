import { render, screen, within } from '@testing-library/react';
import { tokenStore } from '@/lib/auth/token-store';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { DeploymentFields } from '@/features/legal/DeploymentFields';
import { CONFIGURED, CURRENT, HISTORY } from '@/features/legal/fixtures';
import { formatEffectiveDate } from '@/features/legal/messages';
import { RequestButton } from '@/features/legal/RequestButton';
import { RetentionTable } from '@/features/legal/RetentionTable';
import { VersionHistory } from '@/features/legal/VersionHistory';

/**
 * Passo 17 (BR1.6, BR1.8, BR1.9, BR1.12; US2.4 to US2.6): the parts of the privacy page and the history.
 */
afterEach(() => tokenStore.clear());

describe('RetentionTable', () => {
  it('has a caption, column headers and one row per item with labels for the narrow layout', () => {
    render(<RetentionTable items={CURRENT.retention} />);

    const table = screen.getByRole('table');
    expect(within(table).getByText(/Tabela de retenção/)).toBeInTheDocument();
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((th) => th.textContent),
    ).toEqual(['Dado', 'Finalidade', 'Prazo', 'No fim do prazo']);
    const [firstRow] = screen.getAllByTestId('legal-retention-row') as [HTMLElement];
    expect(within(firstRow).getByText('12 meses')).toHaveAttribute('data-label', 'Prazo');
  });

  it('an empty table says it was not published', () => {
    render(<RetentionTable items={[]} />);

    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.getByTestId('legal-retention-empty')).toBeInTheDocument();
  });
});

describe('DeploymentFields', () => {
  it('shows "a definir" for every absent field', () => {
    render(
      <DeploymentFields storageLocation={null} internationalTransfer={null} dpoEmail={null} />,
    );

    expect(screen.getAllByText('a definir')).toHaveLength(3);
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('shows the configured fields and the e-mail as mailto, never a form', () => {
    const { container } = render(<DeploymentFields {...CONFIGURED.deployment} />);

    expect(screen.getByTestId('legal-deployment-storage')).toHaveTextContent(
      'Brasil (região de teste)',
    );
    expect(screen.getByRole('link', { name: 'encarregado@example.test' })).toHaveAttribute(
      'href',
      'mailto:encarregado@example.test',
    );
    expect(container.querySelector('form')).toBeNull();
  });
});

describe('RequestButton', () => {
  it('without a session leads to the entry and shows the e-mail "a definir"', () => {
    render(
      <Providers api={fakeApi()}>
        <RequestButton dpoEmail={null} />
      </Providers>,
    );

    expect(screen.getByTestId('legal-request-button')).toHaveAttribute('href', '/entrar');
    expect(screen.getByTestId('legal-request-email')).toHaveTextContent('carteira perdida');
    expect(screen.getByTestId('legal-request-dpo')).toHaveTextContent('a definir');
  });

  it('with a session leads to the Account', () => {
    tokenStore.set('access-token-value');
    render(
      <Providers api={fakeApi()}>
        <RequestButton dpoEmail="encarregado@example.test" />
      </Providers>,
    );

    expect(screen.getByRole('link', { name: 'Fazer um pedido' })).toHaveAttribute('href', '/conta');
    expect(screen.getByTestId('legal-request-dpo')).toHaveAttribute(
      'href',
      'mailto:encarregado@example.test',
    );
  });
});

describe('VersionHistory', () => {
  it('lists the versions newest first, the current marked and the legacy as provisional', () => {
    render(<VersionHistory versions={HISTORY} currentVersion="v2" basePath="/privacidade" />);

    const items = screen.getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('v2 15/10/2026 (UTC) (vigente)');
    expect(items[1]).toHaveTextContent('(provisória)');
    expect(screen.getByTestId('legal-history-link-v2')).toHaveAttribute('href', '/privacidade');
    expect(screen.getByTestId('legal-history-link-v1')).toHaveAttribute(
      'href',
      '/privacidade?versao=v1',
    );
  });

  it('renders nothing without versions', () => {
    const { container } = render(
      <VersionHistory versions={[]} currentVersion="v2" basePath="/termos" />,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('an invalid instant is shown as received', () => {
    expect(formatEffectiveDate('not-a-date')).toBe('not-a-date');
  });
});
