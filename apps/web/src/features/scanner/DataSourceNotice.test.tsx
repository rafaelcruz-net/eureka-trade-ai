import { render, screen } from '@testing-library/react';
import { tokenStore } from '@/lib/auth/token-store';
import { messages } from '@/lib/i18n/messages';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { AssessmentPanel } from '@/features/scanner/AssessmentPanel';
import { DataSourceNotice } from '@/features/scanner/DataSourceNotice';
import { assessment, MINT_A, page, REAL_DATA_SOURCE, row } from '@/features/scanner/fixtures';
import { ScannerScreen } from '@/features/scanner/ScannerScreen';

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));

const text = messages.scanner.dataSource;

function renderScanner(listTokens: jest.Mock) {
  tokenStore.set('token');
  render(
    <Providers api={fakeApi({ listTokens })}>
      <ScannerScreen refreshMs={60_000} timeZone="UTC" />
    </Providers>,
  );
}

/** 261113-trading-real-bot U2, Step 11 (BR1.8, NFR7.1): the attribution and the simulated-source warning. */
describe('data source of the market values', () => {
  beforeEach(() => {
    tokenStore.clear();
  });

  it('shows the attribution of the real source as a link that opens in a new tab', () => {
    render(<DataSourceNotice dataSource={REAL_DATA_SOURCE} testIdPrefix="scanner" />);

    const attribution = screen.getByTestId('scanner-attribution');
    expect(attribution).toHaveTextContent('Dados: CoinGecko');
    const link = screen.getByRole('link', { name: /Dados: CoinGecko/ });
    expect(link).toHaveAttribute('href', 'https://www.coingecko.com/');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.queryByTestId('scanner-simulated-active')).not.toBeInTheDocument();
  });

  it('warns in text and icon, not only color, when a simulated source is active', () => {
    render(<DataSourceNotice dataSource={{ kind: 'SIMULATED' }} testIdPrefix="scanner" />);

    const warning = screen.getByTestId('scanner-simulated-active');
    expect(warning).toHaveTextContent(text.simulatedActive);
    expect(warning).toHaveTextContent(/fonte simulada ativa/);
    expect(warning).toHaveTextContent('⚠');
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('shows nothing without a data source or for a real source without attribution', () => {
    const { container } = render(
      <>
        <DataSourceNotice dataSource={undefined} testIdPrefix="a" />
        <DataSourceNotice dataSource={{ kind: 'REAL' }} testIdPrefix="b" />
      </>,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('keeps a non-HTTPS attribution link as plain text', () => {
    render(
      <DataSourceNotice
        dataSource={{
          kind: 'REAL',
          attribution: { text: 'Dados: CoinGecko', url: 'javascript:alert(1)' },
        }}
        testIdPrefix="scanner"
      />,
    );

    expect(screen.getByTestId('scanner-attribution')).toHaveTextContent('Dados: CoinGecko');
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('the scanner page with the real source shows the attribution and no simulated warning', async () => {
    renderScanner(jest.fn(async () => page([row(MINT_A)], { dataSource: REAL_DATA_SOURCE })));

    expect(await screen.findByTestId('scanner-attribution')).toHaveTextContent('Dados: CoinGecko');
    expect(screen.queryByTestId('scanner-simulated-active')).not.toBeInTheDocument();
  });

  it('the scanner page with a simulated source shows the warning', async () => {
    renderScanner(jest.fn(async () => page([row(MINT_A)])));

    expect(await screen.findByTestId('scanner-simulated-active')).toHaveTextContent(
      text.simulatedActive,
    );
    expect(screen.queryByTestId('scanner-attribution')).not.toBeInTheDocument();
  });

  it('while the list loads or fails no data source is claimed', async () => {
    let fail: (reason: Error) => void = () => undefined;
    renderScanner(
      jest.fn(
        () =>
          new Promise((_, reject) => {
            fail = reject;
          }),
      ),
    );

    expect(screen.getByTestId('scanner-loading')).toBeInTheDocument();
    expect(screen.queryByTestId('scanner-attribution')).not.toBeInTheDocument();
    expect(screen.queryByTestId('scanner-simulated-active')).not.toBeInTheDocument();
    fail(new Error('network'));
    expect(await screen.findByTestId('scanner-error')).toBeInTheDocument();
    expect(screen.queryByTestId('scanner-attribution')).not.toBeInTheDocument();
  });

  it('the assessment detail shows the attribution of the real source', async () => {
    render(
      <Providers
        api={fakeApi({
          getAssessment: jest.fn(async () => assessment({ dataSource: REAL_DATA_SOURCE })),
        })}
      >
        <AssessmentPanel
          chain="solana"
          mint={MINT_A}
          symbol="PEPE"
          timeZone="UTC"
          onClose={jest.fn()}
          onUnauthenticated={jest.fn()}
        />
      </Providers>,
    );

    expect(await screen.findByTestId('assessment-attribution')).toHaveTextContent(
      'Dados: CoinGecko',
    );
  });
});
