import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import type { KnownValue, ScannerPage, ScannerRow, UnknownValue } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { messages } from '@/lib/i18n/messages';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { ScannerScreen } from '@/features/scanner/ScannerScreen';
import ScannerRoute from '@/app/scanner/page';

const replace = jest.fn();

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: replace }),
  useSearchParams: () => new URLSearchParams(),
}));

const SOURCE = { sourceId: 'simulated', name: 'Fonte simulada', simulated: true };
const known = (value: string, stale = false): KnownValue => ({
  status: 'KNOWN',
  value,
  source: SOURCE,
  observedAt: '2026-10-28T12:00:00Z',
  stale,
});
const unknown: UnknownValue = { status: 'UNKNOWN', reason: 'NOT_PROVIDED', source: SOURCE };

function row(mint: string, overrides: Partial<ScannerRow> = {}): ScannerRow {
  return {
    chain: 'solana',
    mint,
    symbol: 'PEPE',
    coverage: { informed: 5, total: 5 },
    price: known('0.000012345'),
    liquidity: known('25000.50'),
    volume24h: known('1234567.89'),
    holders: known('321'),
    ageMinutes: known('12'),
    ...overrides,
  };
}

function page(items: ScannerRow[], overrides: Partial<ScannerPage> = {}): ScannerPage {
  return {
    items,
    nextCursor: null,
    total: items.length,
    sources: [{ source: SOURCE, status: 'OK', lastUpdatedAt: '2026-10-28T12:00:00Z' }],
    excludedByUnknown: 0,
    pendingNewTokens: 0,
    ...overrides,
  };
}

const MINT_A = 'AAAAmint11111111111111111111111111111111AAAA';
const MINT_B = 'BBBBmint22222222222222222222222222222222BBBB';

function renderScanner(listTokens: jest.Mock, refreshMs = 60_000) {
  tokenStore.set('token');
  const api = fakeApi({ listTokens });
  render(
    <Providers api={api}>
      <ScannerScreen refreshMs={refreshMs} timeZone="UTC" />
    </Providers>,
  );
  return api;
}

describe('S3 scanner list', () => {
  beforeEach(() => {
    replace.mockReset();
    tokenStore.clear();
  });

  it('shows loading only in the list area, then the 7 fields with source and coverage', async () => {
    renderScanner(jest.fn(async () => page([row(MINT_A)])));

    expect(screen.getByTestId('scanner-loading')).toHaveTextContent(messages.scanner.loading);
    expect(
      screen.getByRole('heading', { level: 1, name: messages.scanner.title }),
    ).toBeInTheDocument();
    const table = await screen.findByTestId('scanner-table');

    expect(within(table).getByTestId(`scanner-price-${MINT_A}`)).toHaveTextContent('0,000012345');
    expect(within(table).getByTestId(`scanner-volume-${MINT_A}`)).toHaveTextContent('1.234.567,89');
    expect(within(table).getByTestId(`scanner-age-${MINT_A}`)).toHaveTextContent('12 min');
    expect(within(table).getByTestId(`scanner-coverage-${MINT_A}`)).toHaveTextContent('5 de 5');
    expect(screen.getByTestId('scanner-source')).toHaveTextContent('Fonte simulada');
    expect(screen.getByTestId('scanner-source')).toHaveTextContent(messages.scanner.simulated);
  });

  it('shows unknown as text, distinct from an informed zero', async () => {
    renderScanner(
      jest.fn(async () =>
        page([
          row(MINT_A, {
            holders: unknown,
            liquidity: known('0'),
            coverage: { informed: 4, total: 5 },
          }),
        ]),
      ),
    );

    const holders = await screen.findByTestId(`scanner-holders-${MINT_A}`);
    const liquidity = screen.getByTestId(`scanner-liquidity-${MINT_A}`);
    expect(holders).toHaveTextContent(messages.scanner.unknown);
    expect(holders).toHaveAttribute('data-status', 'UNKNOWN');
    expect(holders).not.toHaveTextContent(/^0$/);
    expect(liquidity).toHaveTextContent('0');
    expect(liquidity).toHaveAttribute('data-status', 'KNOWN');
  });

  it('marks stale values with text, not only color', async () => {
    renderScanner(jest.fn(async () => page([row(MINT_A, { price: known('1.5', true) })])));

    expect(await screen.findByTestId(`scanner-price-${MINT_A}-stale`)).toHaveTextContent(
      messages.scanner.stale,
    );
  });

  it('reveals source, time and freshness on keyboard focus and hides them on blur', async () => {
    renderScanner(jest.fn(async () => page([row(MINT_A, { holders: unknown })])));
    const price = await screen.findByTestId(`scanner-price-${MINT_A}`);
    const detail = screen.getByTestId(`scanner-price-${MINT_A}-detail`);

    expect(detail).not.toBeVisible();
    act(() => price.focus());
    expect(detail).toBeVisible();
    expect(detail).toHaveTextContent('Fonte simulada');
    expect(detail).toHaveTextContent('12:00:00');
    expect(detail).toHaveTextContent(messages.scanner.fresh);
    act(() => price.blur());
    expect(detail).not.toBeVisible();
    await userEvent.hover(screen.getByTestId(`scanner-holders-${MINT_A}`));
    expect(screen.getByTestId(`scanner-holders-${MINT_A}-detail`)).toHaveTextContent(
      messages.scanner.unknownReason.NOT_PROVIDED as string,
    );
  });

  it('abbreviates the mint so repeated symbols stay distinguishable', async () => {
    renderScanner(jest.fn(async () => page([row(MINT_A), row(MINT_B)])));

    expect(await screen.findByTestId(`scanner-row-mint-${MINT_A}`)).toHaveTextContent('AAAA…AAAA');
    expect(screen.getByTestId(`scanner-row-mint-${MINT_B}`)).toHaveTextContent('BBBB…BBBB');
    expect(screen.getAllByText('PEPE')).toHaveLength(2);
  });

  it('pages by cursor and shows "Exibindo X–Y de N"', async () => {
    const listTokens = jest.fn(async (cursor: string | null) =>
      cursor === null
        ? page([row(MINT_A)], { nextCursor: 'c-2', total: 51 })
        : page([row(MINT_B)], { nextCursor: null, total: 51 }),
    );
    renderScanner(listTokens);

    expect(await screen.findByTestId('scanner-showing')).toHaveTextContent('Exibindo 1–1 de 51');
    await userEvent.click(screen.getByTestId('scanner-next-page'));
    await waitFor(() =>
      expect(screen.getByTestId('scanner-showing')).toHaveTextContent('Exibindo 51–51 de 51'),
    );
    expect(listTokens).toHaveBeenLastCalledWith('c-2', 50);

    await userEvent.click(screen.getByTestId('scanner-previous-page'));
    await waitFor(() => expect(listTokens).toHaveBeenLastCalledWith(null, 50));
    expect(screen.queryByTestId('scanner-previous-page')).toBeNull();
  });

  it('shows an error with "Tentar de novo" and recovers', async () => {
    const listTokens = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
      .mockResolvedValueOnce(page([row(MINT_A)]));
    renderScanner(listTokens);

    expect(await screen.findByTestId('scanner-error')).toHaveTextContent(
      messages.scanner.errorTitle,
    );
    await userEvent.click(screen.getByTestId('scanner-retry-button'));
    expect(await screen.findByTestId('scanner-table')).toBeInTheDocument();
  });

  it('flags partial data separately from errors and from per-field unknown', async () => {
    renderScanner(
      jest.fn(async () =>
        page([row(MINT_A, { holders: unknown, coverage: { informed: 4, total: 5 } }), row(MINT_B)]),
      ),
    );

    expect(await screen.findByTestId('scanner-partial')).toHaveTextContent(
      '1 token tem campos unknown',
    );
    expect(screen.queryByTestId('scanner-error')).toBeNull();
  });

  it('warns when the source failed while still showing the last values', async () => {
    renderScanner(
      jest.fn(async () =>
        page([row(MINT_A)], {
          sources: [{ source: SOURCE, status: 'FAILED', lastUpdatedAt: '2026-10-28T11:50:00Z' }],
        }),
      ),
    );

    expect(await screen.findByTestId('scanner-source-failed')).toHaveTextContent(
      messages.scanner.sourceFailed,
    );
    expect(screen.getByTestId('scanner-table')).toBeInTheDocument();
  });

  it('an expired session sends the person to the entry keeping the origin', async () => {
    renderScanner(jest.fn().mockRejectedValue(new ApiError(401, 'UNAUTHENTICATED')));

    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith('/entrar?origem=%2Fscanner&expirada=1'),
    );
    expect(tokenStore.get()).toBeNull();
  });

  it('new data keeps the selected row and the focus, and announces the update', async () => {
    const listTokens = jest.fn(async () => page([row(MINT_A), row(MINT_B)]));
    renderScanner(listTokens, 80);
    const select = await screen.findByTestId(`scanner-row-select-${MINT_B}`);

    await userEvent.click(select);
    expect(select).toHaveFocus();
    await waitFor(() => expect(listTokens.mock.calls.length).toBeGreaterThanOrEqual(3), {
      timeout: 2000,
    });

    expect(screen.getByTestId(`scanner-row-select-${MINT_B}`)).toHaveFocus();
    expect(screen.getByTestId(`scanner-row-${MINT_B}`)).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('scanner-updated')).toHaveTextContent(/Atualizado às \d{2}:\d{2}/);
  });

  it('is operable by keyboard: tab reaches row buttons and value cells', async () => {
    renderScanner(jest.fn(async () => page([row(MINT_A)])));
    await screen.findByTestId('scanner-table');

    await userEvent.tab();
    expect(screen.getByTestId('app-sign-out-button')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId(`scanner-row-select-${MINT_A}`)).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    expect(screen.getByTestId(`scanner-row-${MINT_A}`)).toHaveAttribute('aria-selected', 'true');
    await userEvent.tab();
    expect(screen.getByTestId(`scanner-price-${MINT_A}`)).toHaveFocus();
  });

  it('shows the empty state while the source has not listed any token', async () => {
    renderScanner(jest.fn(async () => page([])));

    expect(await screen.findByTestId('scanner-empty')).toHaveTextContent(messages.scanner.empty);
  });

  it('a later refresh failure keeps the last data visible', async () => {
    const listTokens = jest
      .fn()
      .mockResolvedValueOnce(page([row(MINT_A)]))
      .mockRejectedValue(new ApiError(0, 'NETWORK'));
    renderScanner(listTokens, 50);

    await screen.findByTestId('scanner-table');
    await waitFor(() => expect(listTokens.mock.calls.length).toBeGreaterThanOrEqual(2));
    expect(screen.getByTestId('scanner-table')).toBeInTheDocument();
    expect(screen.queryByTestId('scanner-error')).toBeNull();
  });

  it('the /scanner route renders the scanner screen', async () => {
    tokenStore.set('token');
    render(
      <Providers api={fakeApi()}>
        <ScannerRoute />
      </Providers>,
    );

    expect(await screen.findByTestId('scanner-empty')).toBeInTheDocument();
  });
});
