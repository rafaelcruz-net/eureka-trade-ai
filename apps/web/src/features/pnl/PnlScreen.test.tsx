import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import { tokenStore } from '@/lib/auth/token-store';
import { messages } from '@/lib/i18n/messages';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { PnlScreen } from '@/features/pnl/PnlScreen';
import { DIVERGENT, MINT_A, MINT_B, PARTIAL_REPORT, REPORT } from '@/features/pnl/fixtures';
import PnlPage from '@/app/pnl/page';

const replace = jest.fn();

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: replace }),
  useSearchParams: () => new URLSearchParams(),
}));

const text = messages.pnl;
const NOW = new Date('2026-10-28T15:00:00Z');

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
  });

  it('the title always says MODO PAPER (simulado) and the period of the last 7 local days goes in UTC', async () => {
    const api = renderPnl();

    expect(screen.getByTestId('pnl-title')).toHaveTextContent(text.paperLabel);
    await waitFor(() =>
      expect(api.getPnl).toHaveBeenCalledWith({
        from: '2026-10-22T03:00:00Z',
        to: '2026-10-29T03:00:00Z',
      }),
    );
    expect(screen.getByText(text.periodHelp('America/Sao_Paulo'))).toBeInTheDocument();
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

    await userEvent.clear(screen.getByTestId('pnl-from'));
    await userEvent.type(screen.getByTestId('pnl-from'), '2026-10-01');
    await userEvent.clear(screen.getByTestId('pnl-to'));
    await userEvent.type(screen.getByTestId('pnl-to'), '2026-10-01');
    await userEvent.click(screen.getByTestId('pnl-apply'));

    await waitFor(() =>
      expect(api.getPnl).toHaveBeenLastCalledWith({
        from: '2026-09-30T15:00:00Z',
        to: '2026-10-01T15:00:00Z',
      }),
    );
  });

  it('a reversed period is explained and not sent', async () => {
    const api = renderPnl();
    await screen.findByTestId('pnl-empty');
    const calls = api.getPnl.mock.calls.length;

    await userEvent.clear(screen.getByTestId('pnl-from'));
    await userEvent.type(screen.getByTestId('pnl-from'), '2026-10-30');
    await userEvent.click(screen.getByTestId('pnl-apply'));

    expect(await screen.findByTestId('pnl-invalid')).toHaveTextContent(text.invalidPeriod);
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
    renderPnl(fakeApi({ getPnl: jest.fn(async () => REPORT) }));
    await screen.findByTestId('pnl-report');

    expect(screen.getByLabelText(text.from)).toHaveAttribute('type', 'date');
    expect(screen.getByLabelText(text.to)).toHaveAttribute('type', 'date');
    expect(screen.getByRole('heading', { level: 1 })).toHaveFocus();
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

    expect(await screen.findByTestId('pnl-title')).toHaveTextContent(text.paperLabel);
  });
});
