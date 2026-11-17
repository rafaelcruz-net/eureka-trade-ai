import { act, fireEvent, render, screen } from '@testing-library/react';
import type { GuestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { settle } from '@/features/dashboard/test-support';
import { OverviewScreen } from '@/features/real/screens/OverviewScreen';
import { OrdersScreen } from '@/features/real/screens/OrdersScreen';
import { realMessages as text } from '@/features/real/messages';
import { resetSessionReal } from '@/features/real/session-real';
import {
  MINT,
  pnlDto,
  RealProviders,
  realApi,
  signingWallet,
  walletDto,
} from '@/features/real/test-support';

/**
 * 261113-trading-real-bot U7, Step 14, written after the code (BR8.1 to BR8.4): the button "Definir stop e alvo" of each
 * position follows the mode and the exits flag; the screen of the orders gets the tab "Condicionais" and the export.
 */
jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => '/real',
}));

function withPosition(mode: 'SIGNED' | 'AUTO_EXITS', exitsEnabled: boolean): jest.Mocked<GuestApi> {
  return realApi({
    getRealRelease: jest.fn(async () => ({
      network: 'LOCALNET',
      release: { state: 'ACTIVE' as const, effectiveAt: '2026-11-10T12:00:00Z' },
      exitsEnabled,
    })),
    getBotWallet: jest.fn(async () => walletDto({ mode })),
    getRealPnl: jest.fn(async () =>
      pnlDto({
        positions: [
          {
            mint: MINT,
            baseUnits: '5000',
            costLamports: '50000000',
            priceStatus: 'KNOWN',
            valueLamports: '60000000',
            unrealizedLamports: '10000000',
          },
        ],
      }),
    ),
    quoteRealConditional: jest.fn(async () => ({
      positionMint: MINT,
      positionBaseUnits: '5000',
      priceStatus: 'KNOWN' as const,
      priceUsd: '0.0001',
      source: 'sim-a',
      observedAt: '2026-11-17T12:00:00Z',
      minStopPriceUsd: '0.00005',
      maxTakeProfitPriceUsd: '0.001',
    })),
  });
}

async function renderOverview(api: jest.Mocked<GuestApi>) {
  tokenStore.set('access-token-value');
  render(
    <RealProviders api={api}>
      <OverviewScreen pickWallet={async () => signingWallet()} timeZone="UTC" />
    </RealProviders>,
  );
  await act(async () => settle(20));
}

beforeEach(() => resetSessionReal());
afterEach(() => tokenStore.clear());

describe('the exits on the overview', () => {
  it('with the exits off the button stays disabled with "Em breve"', async () => {
    await renderOverview(withPosition('AUTO_EXITS', false));

    expect(screen.getByTestId(`real-exits-define-${MINT}`)).toBeDisabled();
    expect(screen.getByText(text.exits.soon, { exact: false })).toBeInTheDocument();
  });

  it('in the mode SIGNED it says the mode it needs', async () => {
    await renderOverview(withPosition('SIGNED', true));

    expect(screen.getByTestId(`real-exits-define-${MINT}`)).toBeDisabled();
    expect(screen.getByText(text.exits.needsMode, { exact: false })).toBeInTheDocument();
  });

  it('in AUTO_EXITS with the exits on it opens the dialog with the price of the position', async () => {
    const api = withPosition('AUTO_EXITS', true);
    await renderOverview(api);

    await act(async () => {
      fireEvent.click(screen.getByTestId(`real-exits-define-${MINT}`));
    });
    await act(async () => settle(20));

    expect(screen.getByTestId('real-exits-dialog')).toBeInTheDocument();
    expect(api.quoteRealConditional).toHaveBeenCalledWith(MINT);
    expect(screen.getByTestId('real-exits-price')).toHaveTextContent('0,0001');
  });
});

describe('the orders screen gets the conditionals and the export', () => {
  it('the tab "Condicionais" lists the conditional exits and the export is offered', async () => {
    const api = realApi({
      listRealOrders: jest.fn(async () => ({ items: [], total: 0 })),
      listRealConditionals: jest.fn(async () => ({ items: [], total: 0 })),
    });
    tokenStore.set('access-token-value');
    render(
      <RealProviders api={api}>
        <OrdersScreen timeZone="UTC" />
      </RealProviders>,
    );
    await act(async () => settle(20));

    expect(screen.getByTestId('real-orders-tab-orders')).toHaveAttribute('aria-selected', 'true');
    await act(async () => {
      fireEvent.click(screen.getByTestId('real-orders-tab-conditionals'));
    });
    await act(async () => settle(20));

    expect(screen.getByTestId('real-orders-tab-conditionals')).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(screen.getByTestId('real-exits-empty')).toHaveTextContent(text.exits.empty);
    expect(screen.getByTestId('real-fiscal-button')).toHaveTextContent(text.fiscal.export);
  });

  it('the arrows move between the tabs', async () => {
    const api = realApi({
      listRealOrders: jest.fn(async () => ({ items: [], total: 0 })),
      listRealConditionals: jest.fn(async () => ({ items: [], total: 0 })),
    });
    tokenStore.set('access-token-value');
    render(
      <RealProviders api={api}>
        <OrdersScreen timeZone="UTC" />
      </RealProviders>,
    );
    await act(async () => settle(20));

    fireEvent.keyDown(screen.getByTestId('real-orders-tab-orders'), { key: 'ArrowRight' });
    await act(async () => settle(10));

    expect(screen.getByTestId('real-orders-tab-conditionals')).toHaveAttribute(
      'aria-selected',
      'true',
    );
  });
});
