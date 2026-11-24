import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import { tokenStore } from '@/lib/auth/token-store';
import { messages } from '@/lib/i18n/messages';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { PnlScreen } from '@/features/pnl/PnlScreen';
import {
  BY_MODE,
  DIVERGENT,
  MINT_A,
  MINT_B,
  PARTIAL_REPORT,
  REPORT,
} from '@/features/pnl/fixtures';
import PnlPage from '@/app/pnl/page';

const replace = jest.fn();
let search = new URLSearchParams();

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: replace }),
  useSearchParams: () => search,
}));

const text = messages.pnl;
const NOW = new Date('2026-10-28T15:00:00Z');

/**
 * 261121-paper-real-unico U3 (conflict 2 of the plan, decision of the founder on BR2.1 and BR2.9): /pnl opens on
 * "Desde o início" and reads the table by token only with a concrete period. The cases of the U1 about that table now
 * open with the last 7 days in the address, which was the old default; none was deleted.
 */
const LAST_7 = 'periodo=7d';

function renderPnl(api = fakeApi(), timeZone = 'America/Sao_Paulo') {
  tokenStore.set('access-token-value');
  render(
    <Providers api={api}>
      <PnlScreen timeZone={timeZone} now={NOW} />
    </Providers>,
  );
  return api;
}

describe('S6 P&L screen /pnl', () => {
  beforeEach(() => {
    replace.mockReset();
    tokenStore.clear();
    search = new URLSearchParams(LAST_7);
  });

  it('the PAPER table by token sits in the block Simulado and the period of the last 7 local days goes in UTC', async () => {
    const api = renderPnl();

    // 261121-paper-real-unico U1 (BR9.2): the h1 is "Resultado". U3 (BR2.9): the table by token is "Ver por token"
    // inside the block Simulado, read with the applied pair and without the automatic repetition.
    expect(screen.getByTestId('pnl-title')).toHaveTextContent('Resultado');
    expect(screen.getByTestId('pnl-paper-section')).toHaveTextContent('Ver por token');
    await waitFor(() =>
      expect(api.getPnl).toHaveBeenCalledWith(
        { from: '2026-10-22T03:00:00Z', to: '2026-10-29T03:00:00Z' },
        expect.objectContaining({ retry: false }),
      ),
    );
    expect(screen.getByTestId('pnl-period-showing')).toHaveTextContent(
      'Mostrando: 22/10/2026 a 28/10/2026',
    );
  });

  it('shows loading, then the empty state with its sentence and no number', async () => {
    renderPnl();

    expect(screen.getByTestId('pnl-loading')).toHaveTextContent(text.loading);
    expect(await screen.findByTestId('pnl-empty')).toHaveTextContent(text.empty);
    expect(screen.queryByTestId('pnl-report')).toBeNull();
  });

  it('shows realized, unrealized and fees separated, each with sign and word', async () => {
    renderPnl(fakeApi({ getPnl: jest.fn(async () => REPORT) }));

    expect(await screen.findByTestId('pnl-total-realized')).toHaveTextContent('+48,425000 (ganho)');
    expect(screen.getByTestId('pnl-total-unrealized')).toHaveTextContent('+18,298232 (ganho)');
    expect(screen.getByTestId('pnl-total-fees')).toHaveTextContent('1,050675 (pagas)');
    expect(screen.getByTestId('pnl-unrealized-now')).toHaveTextContent(text.unrealizedNow('09:00'));
    expect(screen.queryByTestId('pnl-partial')).toBeNull();
    const line = screen.getByTestId(`pnl-line-${MINT_B}`);
    expect(within(line).getByTestId(`pnl-line-unrealized-${MINT_B}`)).toHaveTextContent(
      '+18,298232',
    );
    expect(
      within(screen.getByTestId(`pnl-line-${MINT_A}`)).getByText('Mint…1111'),
    ).toBeInTheDocument();
  });

  it('the table by token scrolls inside its own named, focusable region, never the page (NFR6.1)', async () => {
    renderPnl(fakeApi({ getPnl: jest.fn(async () => REPORT) }));

    const table = await screen.findByTestId('pnl-lines');
    const region = table.closest('.table-scroll');
    expect(region).not.toBeNull();
    expect(region).toHaveAttribute('tabindex', '0');
    expect(screen.getByRole('region', { name: text.byTokenCaption })).toBe(region);
    // The full mint stays reachable behind its abbreviation.
    expect(within(screen.getByTestId(`pnl-line-${MINT_A}`)).getByText('Mint…1111')).toHaveAttribute(
      'title',
      MINT_A,
    );
  });

  it('never shows a partial total as complete: unknown with reason, "parcial" and the notice', async () => {
    renderPnl(fakeApi({ getPnl: jest.fn(async () => PARTIAL_REPORT) }));

    expect(await screen.findByTestId('pnl-partial')).toHaveTextContent(text.partial);
    expect(screen.getByTestId('pnl-total-unrealized')).toHaveTextContent(
      `${text.unknown} (sem liquidez)`,
    );
    expect(screen.getByTestId('pnl-total-unrealized')).not.toHaveTextContent('0,000000');
    expect(screen.getByTestId('pnl-partial-tag')).toHaveTextContent(text.partialTag);
    expect(screen.getByTestId('pnl-total-realized')).toHaveTextContent('−21,064000 (perda)');
    const mark = screen.getByTestId(`pnl-line-mark-${MINT_A}`);
    expect(mark).toHaveTextContent(text.noLiquidity);
    expect(mark).toHaveTextContent('0,800000 (Simulado A), 08:58');
  });

  it('a failure shows the error sentence without numbers and retries on request', async () => {
    const getPnl = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
      .mockResolvedValueOnce(REPORT);
    renderPnl(fakeApi({ getPnl }));

    expect(await screen.findByTestId('pnl-error')).toHaveTextContent(text.error);
    expect(screen.queryByTestId('pnl-total-realized')).toBeNull();
    await userEvent.click(screen.getByTestId('pnl-retry'));
    expect(await screen.findByTestId('pnl-total-realized')).toBeInTheDocument();
  });

  it('applies a chosen period converted from the person time zone', async () => {
    const api = renderPnl(fakeApi(), 'Asia/Tokyo');
    await screen.findByTestId('pnl-empty');

    // U3 (BR2.1): the interval is the fifth option of the PeriodSelector, applied with "Aplicar".
    await userEvent.click(screen.getByTestId('pnl-period-RANGE'));
    await userEvent.type(screen.getByTestId('pnl-from'), '2026-10-01');
    await userEvent.type(screen.getByTestId('pnl-to'), '2026-10-01');
    await userEvent.click(screen.getByTestId('pnl-apply'));

    await waitFor(() =>
      expect(api.getPnl).toHaveBeenLastCalledWith(
        { from: '2026-09-30T15:00:00Z', to: '2026-10-01T15:00:00Z' },
        expect.anything(),
      ),
    );
  });

  it('a reversed period is explained and not sent', async () => {
    const api = renderPnl();
    await screen.findByTestId('pnl-empty');
    const calls = api.getPnl.mock.calls.length;

    await userEvent.click(screen.getByTestId('pnl-period-RANGE'));
    await userEvent.type(screen.getByTestId('pnl-from'), '2026-10-30');
    await userEvent.type(screen.getByTestId('pnl-to'), '2026-10-28');
    await userEvent.click(screen.getByTestId('pnl-apply'));

    // U3 (BR2.3): the message sits on its field "Até" and nothing is sent.
    expect(await screen.findByTestId('pnl-to-error')).toHaveTextContent(
      'A data final vem antes da inicial. Corrija o campo "Até".',
    );
    expect(api.getPnl).toHaveBeenCalledTimes(calls);
  });

  it('an expired session goes to the entry with the screen of origin', async () => {
    renderPnl(
      fakeApi({
        getPnl: jest.fn(async () => Promise.reject(new ApiError(401, 'UNAUTHENTICATED'))),
      }),
    );

    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith(expect.stringContaining('origem=%2Fpnl')),
    );
  });

  it('shows "Reconciliado" with the time, and "verificar agora" runs the check', async () => {
    const api = renderPnl();

    expect(await screen.findByTestId('reconciliation-ok')).toHaveTextContent(
      text.reconciledNoAccount,
    );
    await userEvent.click(screen.getByTestId('reconciliation-check-now'));
    expect(api.runReconciliation).toHaveBeenCalledTimes(1);
    expect(await screen.findByTestId('reconciliation-ok')).toHaveTextContent(
      text.reconciled('09:00'),
    );
  });

  it('never hides a divergence: fixed warning with the count and the list', async () => {
    renderPnl(fakeApi({ getReconciliation: jest.fn(async () => DIVERGENT) }));

    const warning = await screen.findByTestId('reconciliation-divergent');
    expect(warning).toHaveAttribute('role', 'alert');
    expect(warning).toHaveTextContent(text.divergent(2));
    expect(within(warning).getAllByRole('listitem')).toHaveLength(2);
    expect(warning).toHaveTextContent(
      'Valor diferente: esperado -100,901800, encontrado -100,901801',
    );
  });

  it('the check-now limit tells when to try again, and the pending state is explained', async () => {
    renderPnl(
      fakeApi({
        getReconciliation: jest.fn(async () => ({
          status: 'PENDING' as const,
          divergenceCount: 0,
          divergences: [],
        })),
        runReconciliation: jest.fn(async () =>
          Promise.reject(new ApiError(429, 'RATE_LIMITED', undefined, undefined, [], [], 42)),
        ),
      }),
    );

    expect(await screen.findByTestId('reconciliation-pending')).toHaveTextContent(
      text.reconciliationPending,
    );
    await userEvent.click(screen.getByTestId('reconciliation-check-now'));
    expect(await screen.findByTestId('reconciliation-check-message')).toHaveTextContent(
      text.checkLimited(42),
    );
  });

  it('basic accessibility: labelled date fields, a focusable title and keyboard operable buttons', async () => {
    // U3: the date fields belong to the option "Intervalo".
    search = new URLSearchParams('periodo=intervalo&de=2026-10-22&ate=2026-10-28');
    renderPnl(fakeApi({ getPnl: jest.fn(async () => REPORT) }));
    await screen.findByTestId('pnl-report');

    expect(screen.getByLabelText(text.from)).toHaveAttribute('type', 'date');
    expect(screen.getByLabelText(text.to)).toHaveAttribute('type', 'date');
    // 261121-paper-real-unico U1 (BR9.2): the first focus is on the h2 of the block Simulado.
    expect(screen.getByRole('heading', { level: 2, name: /Simulado, em dólares/ })).toHaveFocus();
    expect(screen.getByRole('table', { name: text.byTokenCaption })).toBeInTheDocument();
    screen.getAllByRole('button').forEach((button) => expect(button.tagName).toBe('BUTTON'));
    await userEvent.tab();
    expect(document.activeElement).not.toBe(document.body);
  });

  it('the page renders the screen', async () => {
    tokenStore.set('access-token-value');
    render(
      <Providers api={fakeApi()}>
        <PnlPage />
      </Providers>,
    );

    expect(await screen.findByTestId('pnl-title')).toHaveTextContent('Resultado');
    expect(screen.getByTestId('pnl-paper-section')).toHaveTextContent('Ver por token');
  });

  // 261121-paper-real-unico U1 (BR5.1, BR5.3, BR6.1, BR9.2; AC4.1.x, AC4.2.1): the two blocks by mode, never added.
  it('shows the two blocks by mode side by side, each in its currency, and no general total', async () => {
    renderPnl(fakeApi({ getPnlByMode: jest.fn(async () => BY_MODE) }));

    const paper = await screen.findByTestId('pnl-block-PAPER-total');
    const real = screen.getByTestId('pnl-block-REAL-total');
    expect(paper).toHaveTextContent('+US$ 12,00');
    expect(real).toHaveTextContent('+0,0312 SOL');
    expect(screen.getByTestId('pnl-block-PAPER-disclaimer')).toHaveTextContent(
      'O resultado simulado não garante o resultado real.',
    );
    expect(screen.queryByTestId('pnl-block-REAL-disclaimer')).toBeNull();
    // 261121-paper-real-unico U2 (BR10.4): the fixed note of the U1 left the REAL block.
    expect(screen.queryByTestId('pnl-block-REAL-note')).toBeNull();
    expect(screen.getByTestId('pnl-modes')).not.toHaveTextContent(/total geral/i);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Resultado');
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(
      expect.arrayContaining([
        expect.stringContaining('Simulado, em dólares'),
        expect.stringContaining('Real, em SOL'),
      ]),
    );
  });

  it('"Tentar de novo" of an unavailable block reads the route again and updates only that block', async () => {
    const getPnlByMode = jest
      .fn()
      .mockResolvedValueOnce({
        ...BY_MODE,
        real: {
          status: 'UNAVAILABLE',
          market: 'REAL',
          currency: 'SOL',
          code: 'SERVICE_UNAVAILABLE',
          correlationId: 'corr-1',
        },
      })
      .mockResolvedValueOnce({
        ...BY_MODE,
        paper: {
          status: 'UNAVAILABLE',
          market: 'PAPER',
          currency: 'USD',
          code: 'SERVICE_UNAVAILABLE',
          correlationId: 'corr-2',
        },
      });
    renderPnl(fakeApi({ getPnlByMode }));

    expect(await screen.findByTestId('pnl-block-REAL-error')).toHaveTextContent('corr-1');
    expect(screen.getByTestId('pnl-block-PAPER-total')).toHaveTextContent('+US$ 12,00');
    await userEvent.click(screen.getByTestId('pnl-block-REAL-retry'));

    expect(await screen.findByTestId('pnl-block-REAL-total')).toHaveTextContent('+0,0312 SOL');
    expect(screen.getByTestId('pnl-block-PAPER-total')).toHaveTextContent('+US$ 12,00');
    expect(getPnlByMode).toHaveBeenCalledTimes(2);
  });

  it('a failed route leaves both blocks in error with the code for the support', async () => {
    renderPnl(
      fakeApi({
        getPnlByMode: jest.fn(async () => {
          throw new ApiError(503, 'SERVICE_UNAVAILABLE', undefined, 'corr-9');
        }),
      }),
    );

    expect(await screen.findByTestId('pnl-block-PAPER-error')).toHaveTextContent('corr-9');
    expect(screen.getByTestId('pnl-block-REAL-error')).toHaveTextContent('corr-9');
  });
});
