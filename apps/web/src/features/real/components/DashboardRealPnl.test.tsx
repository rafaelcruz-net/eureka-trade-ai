import { act, render, screen, within } from '@testing-library/react';
import type { GuestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { DashboardScreen } from '@/features/dashboard/DashboardScreen';
import { botPage, pnlReport, reconciliation, settle } from '@/features/dashboard/test-support';
import { resetWalletCache } from '@/components/shell/WalletBadge';
import { resetSessionReal } from '@/features/real/session-real';
import { pnlDto, RealProviders, realApi, walletDto } from '@/features/real/test-support';

/**
 * 261113-trading-real-bot U6, Step 10, written after the code (BR7.2, S31; U6-NFR2.1; team.md "separação entre REAL e
 * PAPER"): the dashboard with PAPER and REAL shows two P&L cards, the REAL one with its label, and never adds them.
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

    const paper = screen.getByTestId('dashboard-pnl-card');
    const real = screen.getByTestId('real-pnl-card');
    expect(within(real).getByTestId('real-pnl-badge')).toHaveTextContent('REAL');
    expect(within(real).getByTestId('real-pnl-realized')).toHaveTextContent('+0,084000000 SOL');
    expect(paper).not.toHaveTextContent('0,084000000');
    expect(real).not.toHaveTextContent('12,5');
    expect(paper).toHaveTextContent('MODO PAPER (simulado)');
  });

  it('without a bot wallet the dashboard has only the PAPER card and never reads the P&L REAL', async () => {
    const api = dashboardApi({
      getBotWallet: jest.fn(async () => {
        throw new Error('404');
      }),
    });
    await renderDashboard(api);

    expect(screen.getByTestId('dashboard-pnl-card')).toBeInTheDocument();
    expect(screen.queryByTestId('real-pnl-card')).toBeNull();
    expect(api.getRealPnl).not.toHaveBeenCalled();
  });

  it('a failing P&L REAL shows only in its card', async () => {
    await renderDashboard(
      dashboardApi({
        getRealPnl: jest.fn(async () => {
          throw new Error('down');
        }),
      }),
    );

    expect(screen.getByTestId('real-pnl-error')).toBeInTheDocument();
    expect(screen.queryByTestId('dashboard-card-error-pnl')).toBeNull();
  });
});
