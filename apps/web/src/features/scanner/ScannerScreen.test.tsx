import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import type { ListRequest } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { messages } from '@/lib/i18n/messages';
import { fakeApi, Providers } from '@/features/auth/test-support';
import {
  assessment,
  known,
  MINT_A,
  MINT_B,
  page,
  row,
  SOURCE,
  SOURCE_B,
  sourceStatus,
  unknown,
} from '@/features/scanner/fixtures';
import { ScannerScreen } from '@/features/scanner/ScannerScreen';
import ScannerRoute from '@/app/scanner/page';

const replace = jest.fn();
let search = new URLSearchParams();

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: replace }),
  useSearchParams: () => search,
}));

const text = messages.scanner;

function renderScanner(listTokens: jest.Mock, refreshMs = 60_000, getAssessment?: jest.Mock) {
  tokenStore.set('token');
  const api = fakeApi(getAssessment ? { listTokens, getAssessment } : { listTokens });
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
    search = new URLSearchParams();
  });

  it('shows loading only in the list area, then the fields with sources, coverage and risk', async () => {
    renderScanner(
      jest.fn(async () =>
        page([row(MINT_A)], { sources: [sourceStatus('OK'), sourceStatus('OK', SOURCE_B)] }),
      ),
    );

    expect(screen.getByTestId('scanner-loading')).toHaveTextContent(text.loading);
    expect(screen.getByTestId('scanner-filters-apply')).toBeEnabled();
    const table = await screen.findByTestId('scanner-table');

    expect(within(table).getByTestId(`scanner-price-${MINT_A}`)).toHaveTextContent('0,000012345');
    expect(within(table).getByTestId(`scanner-volume-${MINT_A}`)).toHaveTextContent('1.234.567,89');
    expect(within(table).getByTestId(`scanner-age-${MINT_A}`)).toHaveTextContent('12 min');
    expect(within(table).getByTestId(`scanner-coverage-${MINT_A}`)).toHaveTextContent('5 de 5');
    expect(within(table).getByTestId(`scanner-risk-${MINT_A}`)).toHaveTextContent(
      '45 — Risco médio',
    );
    expect(screen.getByTestId('scanner-source')).toHaveTextContent(
      'Fonte simulada A, Fonte simulada B',
    );
    expect(screen.getByTestId('scanner-source')).toHaveTextContent(text.simulated);
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
    expect(holders).toHaveTextContent(text.unknown);
    expect(holders).toHaveAttribute('data-status', 'UNKNOWN');
    expect(screen.getByTestId(`scanner-liquidity-${MINT_A}`)).toHaveTextContent('0');
  });

  it('marks stale values with text and the time of the last update, not only color', async () => {
    renderScanner(jest.fn(async () => page([row(MINT_A, { price: known('1.5', true) })])));

    const stale = await screen.findByTestId(`scanner-price-${MINT_A}-stale`);
    expect(stale).toHaveTextContent(text.stale);
    expect(stale).toHaveTextContent('Atualizado às 12:00');
  });

  it('marks a divergent field in text and shows every origin on focus', async () => {
    const divergent = {
      ...known('1.00'),
      divergence: [{ source: SOURCE_B, value: '1.20', observedAt: '2026-10-28T11:59:00Z' }],
    };
    renderScanner(jest.fn(async () => page([row(MINT_A, { price: divergent })])));

    expect(await screen.findByTestId(`scanner-price-${MINT_A}-divergent`)).toHaveTextContent(
      text.divergent,
    );
    act(() => screen.getByTestId(`scanner-price-${MINT_A}`).focus());
    const origins = screen.getAllByTestId(`scanner-price-${MINT_A}-origin`);
    expect(origins).toHaveLength(2);
    expect(origins[1]).toHaveTextContent('Fonte simulada B');
    expect(origins[1]).toHaveTextContent('1,20');
  });

  it('reveals source, time and freshness on keyboard focus and hides them on blur', async () => {
    renderScanner(jest.fn(async () => page([row(MINT_A, { holders: unknown })])));
    const price = await screen.findByTestId(`scanner-price-${MINT_A}`);
    const detail = screen.getByTestId(`scanner-price-${MINT_A}-detail`);

    expect(detail).not.toBeVisible();
    act(() => price.focus());
    expect(detail).toBeVisible();
    expect(detail).toHaveTextContent('Fonte simulada A');
    expect(detail).toHaveTextContent('12:00:00');
    act(() => price.blur());
    expect(detail).not.toBeVisible();
    await userEvent.hover(screen.getByTestId(`scanner-holders-${MINT_A}`));
    expect(screen.getByTestId(`scanner-holders-${MINT_A}-detail`)).toHaveTextContent(
      text.unknownReason.NOT_PROVIDED as string,
    );
  });

  it('shows an unavailable risk as "--" with the reason on focus', async () => {
    renderScanner(
      jest.fn(async () =>
        page([row(MINT_A, { risk: { status: 'UNAVAILABLE', reason: 'INSUFFICIENT_COVERAGE' } })]),
      ),
    );

    const risk = await screen.findByTestId(`scanner-risk-${MINT_A}`);
    expect(risk).toHaveTextContent('--');
    expect(screen.getByTestId(`scanner-risk-${MINT_A}-reason`)).not.toBeVisible();
    act(() => risk.focus());
    expect(screen.getByTestId(`scanner-risk-${MINT_A}-reason`)).toHaveTextContent(
      'Dados insuficientes para avaliar',
    );
  });

  it('abbreviates the mint so repeated symbols stay distinguishable', async () => {
    renderScanner(jest.fn(async () => page([row(MINT_A), row(MINT_B)])));

    expect(await screen.findByTestId(`scanner-row-mint-${MINT_A}`)).toHaveTextContent('AAAA…AAAA');
    expect(screen.getByTestId(`scanner-row-mint-${MINT_B}`)).toHaveTextContent('BBBB…BBBB');
  });

  it('pages by cursor and shows "Exibindo X–Y de N"', async () => {
    const listTokens = jest.fn(async (request: ListRequest) =>
      request.cursor === null
        ? page([row(MINT_A)], { nextCursor: 'c-2', total: 51 })
        : page([row(MINT_B)], { nextCursor: null, total: 51 }),
    );
    renderScanner(listTokens);

    expect(await screen.findByTestId('scanner-showing')).toHaveTextContent('Exibindo 1–1 de 51');
    await userEvent.click(screen.getByTestId('scanner-next-page'));
    await waitFor(() =>
      expect(screen.getByTestId('scanner-showing')).toHaveTextContent('Exibindo 51–51 de 51'),
    );
    expect(listTokens).toHaveBeenLastCalledWith(
      expect.objectContaining({ cursor: 'c-2', limit: 50 }),
    );

    await userEvent.click(screen.getByTestId('scanner-previous-page'));
    await waitFor(() =>
      expect(listTokens).toHaveBeenLastCalledWith(expect.objectContaining({ cursor: null })),
    );
  });

  it('shows an error with "Tentar de novo" and recovers', async () => {
    const listTokens = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(500, 'INTERNAL_ERROR'))
      .mockResolvedValueOnce(page([row(MINT_A)]));
    renderScanner(listTokens);

    expect(await screen.findByTestId('scanner-error')).toHaveTextContent(text.errorTitle);
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

  it('warns about a failed source with its name and last update while the list goes on', async () => {
    renderScanner(
      jest.fn(async () =>
        page([row(MINT_A)], {
          sources: [
            sourceStatus('FAILED', SOURCE, '2026-10-28T11:50:00Z'),
            sourceStatus('OK', SOURCE_B),
          ],
        }),
      ),
    );

    const warning = await screen.findByTestId('scanner-source-failed');
    expect(warning).toHaveTextContent(text.sourcesFailed);
    expect(warning).toHaveTextContent('Fonte simulada A');
    expect(warning).toHaveTextContent('11:50:00');
    expect(screen.getByTestId('scanner-table')).toBeInTheDocument();
  });

  it('explains that every source failed with the state of each and offers "Tentar de novo"', async () => {
    const listTokens = jest
      .fn()
      .mockRejectedValueOnce(
        new ApiError(
          503,
          'SOURCE_UNAVAILABLE',
          undefined,
          'c',
          [],
          [sourceStatus('FAILED'), sourceStatus('FAILED', SOURCE_B, null)],
        ),
      )
      .mockResolvedValueOnce(page([row(MINT_A)]));
    renderScanner(listTokens);

    const failed = await screen.findByTestId('scanner-all-failed');
    expect(failed).toHaveTextContent(text.allSourcesFailedTitle);
    expect(failed).toHaveTextContent('Fonte simulada B: com falha');
    expect(failed).toHaveTextContent(text.neverUpdated);
    expect(screen.queryByTestId('scanner-table')).toBeNull();
    await userEvent.click(screen.getByTestId('scanner-retry-button'));
    expect(await screen.findByTestId('scanner-table')).toBeInTheDocument();
  });

  it('waits for the first collection while the sources are pending', async () => {
    renderScanner(
      jest.fn(async () => page([], { sources: [sourceStatus('PENDING', SOURCE, null)] })),
    );

    expect(await screen.findByTestId('scanner-waiting')).toHaveTextContent(
      text.waitingFirstCollection,
    );
    expect(screen.queryByTestId('scanner-empty')).toBeNull();
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
    renderScanner(
      listTokens,
      80,
      jest.fn(async () => assessment()),
    );
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

  it('new tokens never jump into the list: they wait for "Atualizar lista", announced politely', async () => {
    const listTokens = jest
      .fn()
      .mockResolvedValueOnce(page([row(MINT_A)]))
      .mockResolvedValue(page([row(MINT_B), row(MINT_A)], { total: 2 }));
    renderScanner(listTokens, 60);
    await screen.findByTestId('scanner-table');

    expect(
      await screen.findByTestId('scanner-pending-new', {}, { timeout: 2000 }),
    ).toHaveTextContent('1 novo token');
    expect(screen.queryByTestId(`scanner-row-${MINT_B}`)).toBeNull();
    expect(screen.getByTestId('scanner-updated')).toHaveTextContent('1 novo token');
    await userEvent.click(screen.getByTestId('scanner-refresh-list'));
    expect(await screen.findByTestId(`scanner-row-${MINT_B}`)).toBeInTheDocument();
  });

  it('is operable by keyboard: tab reaches the filters, the row buttons and the value cells', async () => {
    renderScanner(
      jest.fn(async () => page([row(MINT_A)])),
      60_000,
      jest.fn(async () => assessment()),
    );
    await screen.findByTestId('scanner-table');

    // U3: the account menu comes first in the header; U5 puts the Bots link and U7 the P&L link before Conta and Sair;
    // U8 puts the feedback button between Conta and Sair.
    await userEvent.tab();
    expect(screen.getByTestId('app-bots-link')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('app-pnl-link')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('app-account-link')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('app-feedback-button')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('app-sign-out-button')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('scanner-filter-age')).toHaveFocus();
    for (
      let i = 0;
      i < 20 && document.activeElement !== screen.getByTestId(`scanner-row-select-${MINT_A}`);
      i++
    ) {
      await userEvent.tab();
    }
    expect(screen.getByTestId(`scanner-row-select-${MINT_A}`)).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    expect(screen.getByTestId(`scanner-row-${MINT_A}`)).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByTestId('assessment-panel')).toBeInTheDocument();
    await userEvent.tab();
    expect(screen.getByTestId(`scanner-price-${MINT_A}`)).toHaveFocus();
  });

  it('opens the panel without reloading the list and Esc gives the focus back to the same row', async () => {
    const listTokens = jest.fn(async () => page([row(MINT_A), row(MINT_B)]));
    renderScanner(
      listTokens,
      60_000,
      jest.fn(async () => assessment()),
    );
    await userEvent.click(await screen.findByTestId(`scanner-row-select-${MINT_B}`));
    const calls = listTokens.mock.calls.length;

    expect(await screen.findByTestId('assessment-detail')).toBeInTheDocument();
    expect(listTokens.mock.calls.length).toBe(calls);
    await userEvent.click(screen.getByTestId('assessment-close'));
    await waitFor(() => expect(screen.getByTestId(`scanner-row-select-${MINT_B}`)).toHaveFocus());
    await userEvent.click(screen.getByTestId(`scanner-row-select-${MINT_A}`));
    await screen.findByTestId('assessment-detail');
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByTestId('assessment-panel')).toBeNull());
    expect(screen.getByTestId(`scanner-row-select-${MINT_A}`)).toHaveFocus();
  });

  it('applies the filters, keeps them in the address and asks the API for them', async () => {
    const listTokens = jest.fn(async () => page([row(MINT_A)]));
    renderScanner(listTokens);
    await screen.findByTestId('scanner-table');

    await userEvent.selectOptions(screen.getByTestId('scanner-filter-age'), '15');
    await userEvent.type(screen.getByTestId('scanner-filter-minLiquidity'), '1000,5');
    await userEvent.selectOptions(screen.getByTestId('scanner-sort'), 'LIQUIDITY');
    await userEvent.click(screen.getByTestId('scanner-filters-apply'));

    expect(replace).toHaveBeenCalledWith(
      '/scanner?maxAgeMinutes=15&minLiquidity=1000.5&sort=LIQUIDITY',
    );
    await waitFor(() =>
      expect(listTokens).toHaveBeenLastCalledWith(
        expect.objectContaining({
          cursor: null,
          sort: 'LIQUIDITY',
          filters: { maxAgeMinutes: '15', minLiquidity: '1000.5' },
        }),
      ),
    );
  });

  it('reads the filters from the address on the first load', async () => {
    search = new URLSearchParams('minHolders=200&maxRiskScore=69&sort=RISK_SCORE&order=DESC');
    const listTokens = jest.fn(async () => page([row(MINT_A)]));
    renderScanner(listTokens);

    await screen.findByTestId('scanner-table');
    expect(listTokens).toHaveBeenCalledWith(
      expect.objectContaining({
        sort: 'RISK_SCORE',
        order: 'DESC',
        filters: { minHolders: '200', maxRiskScore: '69' },
      }),
    );
    expect(screen.getByTestId('scanner-filter-minHolders')).toHaveValue('200');
  });

  it('shows the error of an invalid filter in text next to the field and sends nothing', async () => {
    const listTokens = jest.fn(async () => page([row(MINT_A)]));
    renderScanner(listTokens);
    await screen.findByTestId('scanner-table');
    const calls = listTokens.mock.calls.length;

    await userEvent.type(screen.getByTestId('scanner-filter-maxRiskScore'), '150');
    await userEvent.click(screen.getByTestId('scanner-filters-apply'));

    expect(screen.getByTestId('scanner-filter-maxRiskScore-error')).toHaveTextContent(
      text.filters.invalidRisk,
    );
    expect(screen.getByTestId('scanner-filter-maxRiskScore')).toHaveAttribute(
      'aria-invalid',
      'true',
    );
    expect(listTokens.mock.calls.length).toBe(calls);
  });

  it('shows the error of a filter refused by the API next to the field', async () => {
    search = new URLSearchParams('minVolume24h=5');
    renderScanner(
      jest
        .fn()
        .mockRejectedValue(
          new ApiError(400, 'VALIDATION_FAILED', undefined, 'c', [
            { field: 'minVolume24h', message: 'x' },
          ]),
        ),
    );

    expect(await screen.findByTestId('scanner-filter-minVolume24h-error')).toHaveTextContent(
      text.filters.rejected,
    );
  });

  it('with filters and no result explains it, counts the unknowns and offers "Limpar filtros"', async () => {
    search = new URLSearchParams('minHolders=999999');
    const listTokens = jest
      .fn()
      .mockResolvedValueOnce(page([], { excludedByUnknown: 3 }))
      .mockResolvedValue(page([row(MINT_A)]));
    renderScanner(listTokens);

    expect(await screen.findByTestId('scanner-empty-filtered')).toHaveTextContent(
      text.emptyFiltered,
    );
    expect(screen.getByTestId('scanner-excluded')).toHaveTextContent(
      '3 tokens ficaram fora por dado ausente',
    );
    await userEvent.click(screen.getByTestId('scanner-clear-filters'));

    expect(replace).toHaveBeenCalledWith('/scanner');
    expect(await screen.findByTestId('scanner-table')).toBeInTheDocument();
    expect(screen.getByTestId('scanner-filter-minHolders')).toHaveValue('');
  });

  it('shows the empty state when no token exists yet', async () => {
    renderScanner(jest.fn(async () => page([])));

    expect(await screen.findByTestId('scanner-empty')).toHaveTextContent(text.empty);
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
