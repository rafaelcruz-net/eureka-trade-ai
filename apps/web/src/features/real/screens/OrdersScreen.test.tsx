import { act, fireEvent, render, screen, within } from '@testing-library/react';
import type { GuestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { settle } from '@/features/dashboard/test-support';
import { explorerLink, ORDERS_PAGE, OrdersScreen } from '@/features/real/screens/OrdersScreen';
import { realMessages as text } from '@/features/real/messages';
import { resetSessionReal } from '@/features/real/session-real';
import { orderDto, problem, RealProviders, realApi, walletDto } from '@/features/real/test-support';
import RealOrdersPage from '@/app/real/ordens/page';

/**
 * 261113-trading-real-bot U6, Step 13, written after the code (R5; US4.1, US8.3; BR8.4 to BR8.7; S26 to S29;
 * U6-NFR3.6): the REAL orders by cursor, with their states, signatures, dates and amounts.
 */
jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => '/real/ordens',
}));

const SIGNATURE = '5'.repeat(64);

/**
 * The list of the screen answers page after page; the read of the signing banner of the shell (state QUOTED) answers
 * an empty page, so it never takes a page of the screen.
 */
function pages(...answers: Array<unknown>) {
  const queue = [...answers];
  return jest.fn(async (request: { state?: string } = {}) => {
    if (request.state === 'QUOTED') {
      return { items: [], total: 0 };
    }
    const next = queue.shift();
    if (next instanceof Error) {
      throw next;
    }
    return next ?? { items: [], total: 0 };
  });
}

async function renderOrders(api: jest.Mocked<GuestApi>) {
  tokenStore.set('access-token-value');
  render(
    <RealProviders api={api}>
      <OrdersScreen timeZone="America/Sao_Paulo" />
    </RealProviders>,
  );
  await act(async () => settle(20));
}

beforeEach(() => resetSessionReal());
afterEach(() => tokenStore.clear());

describe('R5 orders', () => {
  it('the page asks the first 20 orders, never the default of 50 (U6-NFR3.6)', async () => {
    const api = realApi();
    tokenStore.set('access-token-value');
    render(
      <RealProviders api={api}>
        <RealOrdersPage />
      </RealProviders>,
    );
    await act(async () => settle(20));

    expect(api.listRealOrders).toHaveBeenCalledWith({ limit: ORDERS_PAGE });
    expect(ORDERS_PAGE).toBe(20);
  });

  it('an empty list says "Nenhuma ordem real ainda" (S26)', async () => {
    await renderOrders(realApi());

    expect(screen.getByTestId('real-orders-empty')).toHaveTextContent(text.orders.empty);
  });

  it('shows date in the time zone, token, side, amounts with the decimals of the mint and the fee (BR8.6, BR8.7)', async () => {
    await renderOrders(
      realApi({
        listRealOrders: jest.fn(async () => ({
          items: [orderDto({ state: 'CONFIRMED', txSignature: SIGNATURE })],
          total: 1,
        })),
      }),
    );

    const row = screen.getByTestId(`real-order-row-${orderDto().realOrderId}`);
    expect(row).toHaveTextContent('16/11/2026, 10:02:00');
    expect(row).toHaveTextContent('Test…1111');
    expect(row).toHaveTextContent('Compra');
    expect(row).toHaveTextContent('0,200000000 SOL');
    expect(row).toHaveTextContent('16.260,000000');
    expect(row).toHaveTextContent('0,000005000 SOL');
    expect(
      within(row).getByTestId(`real-order-row-state-${orderDto().realOrderId}`),
    ).toHaveTextContent('✔ Confirmada');
  });

  it('an ambiguous order is "Em conferência" with its icon and the reserved amount (S27, BR8.4)', async () => {
    await renderOrders(
      realApi({
        listRealOrders: jest.fn(async () => ({
          items: [orderDto({ state: 'AMBIGUOUS' })],
          total: 1,
        })),
      }),
    );

    expect(screen.getByTestId(`real-order-row-state-${orderDto().realOrderId}`)).toHaveTextContent(
      '⟳ Em conferência · Valor reservado: 0,200000000 SOL',
    );
  });

  it('on the local network there is no link, only the abbreviated signature (S28, BR8.5)', async () => {
    await renderOrders(
      realApi({
        listRealOrders: jest.fn(async () => ({
          items: [orderDto({ state: 'CONFIRMED', txSignature: SIGNATURE })],
          total: 1,
        })),
      }),
    );

    expect(
      screen.getByTestId(`real-order-row-signature-${orderDto().realOrderId}`),
    ).toHaveTextContent('555555…555555');
    expect(screen.queryByTestId(`real-order-row-link-${orderDto().realOrderId}`)).toBeNull();
  });

  it('on the mainnet the link has no cluster and on the devnet it has cluster=devnet (S29, BR8.5)', async () => {
    expect(explorerLink('MAINNET', SIGNATURE)).toBe(`https://solscan.io/tx/${SIGNATURE}`);
    expect(explorerLink('DEVNET', SIGNATURE)).toBe(
      `https://solscan.io/tx/${SIGNATURE}?cluster=devnet`,
    );
    expect(explorerLink('LOCALNET', SIGNATURE)).toBeNull();
    await renderOrders(
      realApi({
        getBotWallet: jest.fn(async () => walletDto({ network: 'MAINNET' })),
        listRealOrders: jest.fn(async () => ({
          items: [orderDto({ state: 'CONFIRMED', txSignature: SIGNATURE })],
          total: 1,
        })),
      }),
    );

    const link = screen.getByTestId(`real-order-row-link-${orderDto().realOrderId}`);
    expect(link).toHaveAttribute('href', `https://solscan.io/tx/${SIGNATURE}`);
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('"Carregar mais" reads the next page by cursor and keeps the first one', async () => {
    const listRealOrders = pages(
      { items: [orderDto()], nextCursor: 'c1', total: 2 },
      { items: [orderDto({ realOrderId: '0dde0000-0000-4000-8000-0000000000a2' })], total: 2 },
    );
    await renderOrders(
      realApi({ listRealOrders: listRealOrders as unknown as GuestApi['listRealOrders'] }),
    );

    await act(async () => fireEvent.click(screen.getByTestId('real-orders-more')));
    await act(async () => settle(20));

    expect(listRealOrders).toHaveBeenLastCalledWith({ limit: 20, cursor: 'c1' });
    expect(screen.getByTestId('real-orders-table').querySelectorAll('tbody tr')).toHaveLength(2);
    expect(screen.queryByTestId('real-orders-more')).toBeNull();
  });

  it('an error shows its text and "Tentar de novo" reads again (BR8.1)', async () => {
    const listRealOrders = pages(problem(503, 'SERVICE_UNAVAILABLE'), { items: [], total: 0 });
    await renderOrders(
      realApi({ listRealOrders: listRealOrders as unknown as GuestApi['listRealOrders'] }),
    );

    expect(screen.getByTestId('real-orders-error')).toHaveTextContent(text.orders.error);
    await act(async () => fireEvent.click(screen.getByTestId('real-orders-error-retry')));
    await act(async () => settle(20));
    expect(screen.getByTestId('real-orders-empty')).toBeInTheDocument();
  });

  it('a failing next page keeps the first page and offers to try again', async () => {
    const listRealOrders = pages(
      { items: [orderDto()], nextCursor: 'c1', total: 2 },
      problem(503, 'SERVICE_UNAVAILABLE'),
    );
    await renderOrders(
      realApi({ listRealOrders: listRealOrders as unknown as GuestApi['listRealOrders'] }),
    );

    await act(async () => fireEvent.click(screen.getByTestId('real-orders-more')));
    await act(async () => settle(20));

    expect(screen.getByTestId('real-orders-more-error')).toBeInTheDocument();
    expect(screen.getByTestId(`real-order-row-${orderDto().realOrderId}`)).toBeInTheDocument();
  });

  it('every order is also a card, for the screens below 768 px (S37, U6-NFR6.5)', async () => {
    await renderOrders(
      realApi({
        listRealOrders: jest.fn(async () => ({
          items: [orderDto({ side: 'SELL', state: 'FAILED' })],
          total: 1,
        })),
      }),
    );

    const card = screen.getByTestId(`real-order-card-${orderDto().realOrderId}`);
    expect(card).toHaveTextContent('Venda');
    expect(
      within(card).getByTestId(`real-order-card-state-${orderDto().realOrderId}`),
    ).toHaveTextContent('✖ Falhou');
  });

  it('a missing fee is "sem dado", never zero', async () => {
    await renderOrders(
      realApi({
        listRealOrders: jest.fn(async () => ({
          items: [orderDto({ feeLamports: undefined, output: undefined })],
          total: 1,
        })),
      }),
    );

    const row = screen.getByTestId(`real-order-row-${orderDto().realOrderId}`);
    expect(row).toHaveTextContent('sem dado');
    expect(row).not.toHaveTextContent('0,000000000 SOL');
  });
});
