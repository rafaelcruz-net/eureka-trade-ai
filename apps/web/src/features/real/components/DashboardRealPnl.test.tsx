import { act, render, screen } from '@testing-library/react';
import type { GuestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { DashboardScreen } from '@/features/dashboard/DashboardScreen';
import { botPage, pnlReport, reconciliation, settle } from '@/features/dashboard/test-support';
import { resetWalletCache } from '@/components/shell/WalletBadge';
import { resetSessionReal } from '@/features/real/session-real';
import { BY_MODE } from '@/features/pnl/fixtures';
import { pnlDto, RealProviders, realApi, walletDto } from '@/features/real/test-support';

/**
 * 261113-trading-real-bot U6, Step 10, written after the code (BR7.2, S31; U6-NFR2.1; team.md "separação entre REAL e
 * PAPER"): the dashboard with PAPER and REAL shows two P&L cards, the REAL one with its label, and never adds them.
 *
 * 261121-paper-real-unico U3 (BR7.1): the DashboardRealPnl (P&L REAL of `GET /real/pnl`) left the dashboard with the
 * PnlCard; the two cards by mode, "Resultado simulado" and "Resultado real", both from C4, took their place. The three
 * cases keep their intent against the new cards.
 */
jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => '/',
}));

function dashboardApi(overrides: Partial<GuestApi> = {}) {
  return realApi({
    getBotWallet: jest.fn(async () => walletDto({ mode: 'AUTO_FULL' })),
    listBots: jest.fn(async () => botPage([])),
    getPnl: jest.fn(async () => pnlReport({ realized: '12.500000' })),
    getReconciliation: jest.fn(async () => reconciliation()),
    getRealPnl: jest.fn(async () => pnlDto({ realizedLamports: '84000000' })),
    getPnlByMode: jest.fn(async () => BY_MODE),
    ...overrides,
  });
}

async function renderDashboard(api: jest.Mocked<GuestApi>) {
  tokenStore.set('access-token-value');
  render(
    <RealProviders api={api}>
      <DashboardScreen now={() => new Date('2026-11-16T12:00:00Z')} timeZone="America/Sao_Paulo" />
    </RealProviders>,
  );
  await act(async () => settle(30));
}

beforeEach(() => {
  resetSessionReal();
  resetWalletCache();
});
afterEach(() => tokenStore.clear());

describe('dashboard P&L REAL', () => {
  it('shows the REAL card beside the PAPER one, each with only its own values (S31, BR7.2)', async () => {
    await renderDashboard(dashboardApi());

    const paper = screen.getByTestId('pnl-compact-PAPER');
    const real = screen.getByTestId('pnl-compact-REAL');
    expect(screen.getByTestId('pnl-compact-REAL-badge')).toHaveTextContent('REAL');
    expect(screen.getByTestId('pnl-compact-REAL-total')).toHaveTextContent('+0,0312 SOL');
    expect(paper).not.toHaveTextContent('SOL');
    expect(real).not.toHaveTextContent('US$');
    expect(screen.getByTestId('pnl-compact-PAPER-badge')).toHaveTextContent('SIMULADO');
  });

  it('the dashboard never reads the P&L REAL of /real/pnl: the REAL card comes from C4', async () => {
    const api = dashboardApi({
      getBotWallet: jest.fn(async () => {
        throw new Error('404');
      }),
    });
    await renderDashboard(api);

    expect(screen.getByTestId('pnl-compact-PAPER')).toBeInTheDocument();
    expect(screen.getByTestId('pnl-compact-REAL')).toBeInTheDocument();
    expect(screen.queryByTestId('real-pnl-card')).toBeNull();
    expect(api.getRealPnl).not.toHaveBeenCalled();
  });

  it('a failing REAL block shows only in its card', async () => {
    await renderDashboard(
      dashboardApi({
        getPnlByMode: jest.fn(async () => ({
          ...BY_MODE,
          real: {
            status: 'UNAVAILABLE' as const,
            market: 'REAL' as const,
            currency: 'SOL' as const,
            code: 'SERVICE_UNAVAILABLE',
            correlationId: 'corr-real',
          },
        })),
      }),
    );

    expect(screen.getByTestId('pnl-compact-REAL-error')).toHaveTextContent('corr-real');
    expect(screen.queryByTestId('pnl-compact-PAPER-error')).toBeNull();
    expect(screen.getByTestId('pnl-compact-PAPER-total')).toHaveTextContent('+US$ 12,00');
  });
});
