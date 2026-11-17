import { act, fireEvent, render, screen } from '@testing-library/react';
import type { GuestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { settle } from '@/features/dashboard/test-support';
import { ConditionalsTab } from '@/features/real/components/ConditionalsTab';
import { realMessages as text } from '@/features/real/messages';
import { MINT, problem, RealProviders, realApi } from '@/features/real/test-support';

/**
 * 261113-trading-real-bot U7, Step 14, written after the code (BR8.3, BR6.7, S38, S48): the tab "Condicionais" says
 * every state in text, shows the notice of a stalled cancellation, and cancels with a confirmation.
 */
type State =
  | 'PENDING_SIGNATURE'
  | 'OPEN'
  | 'CANCELLING'
  | 'FILLED'
  | 'CANCELLED'
  | 'EXPIRED'
  | 'FAILED';

function item(id: string, state: State, overrides: Record<string, unknown> = {}) {
  return {
    conditionalOrderId: id,
    positionMint: MINT,
    kind: 'OCO' as const,
    stopPriceUsd: '0.00008',
    takeProfitPriceUsd: '0.00015',
    amountBaseUnits: '5000',
    state,
    needsUserCancellation: false,
    createdAt: '2026-11-17T12:00:00Z',
    ...overrides,
  };
}

async function renderTab(api: jest.Mocked<GuestApi>) {
  tokenStore.set('access-token-value');
  render(
    <RealProviders api={api}>
      <ConditionalsTab api={api} timeZone="UTC" newKey={() => 'key-1'} />
    </RealProviders>,
  );
  await act(async () => settle(20));
}

afterEach(() => tokenStore.clear());

describe('ConditionalsTab', () => {
  it('says every state in text, with the leg that filled', async () => {
    const states: State[] = [
      'PENDING_SIGNATURE',
      'OPEN',
      'CANCELLING',
      'FILLED',
      'CANCELLED',
      'EXPIRED',
      'FAILED',
    ];
    await renderTab(
      realApi({
        listRealConditionals: jest.fn(async () => ({
          items: states.map((state, index) =>
            item(`id-${index}`, state, state === 'FILLED' ? { filledSide: 'STOP' } : {}),
          ),
          total: states.length,
        })),
      }),
    );

    states.forEach((state, index) =>
      expect(screen.getByTestId(`real-exits-state-id-${index}`)).toHaveTextContent(
        text.exits.states[state] as string,
      ),
    );
    expect(screen.getByTestId('real-exits-state-id-3')).toHaveTextContent('pelo stop');
    expect(screen.getAllByText('0,00008 / 0,00015')).toHaveLength(states.length);
  });

  it('S38, BR6.7: a cancellation stalled for 15 minutes shows that the support was told', async () => {
    await renderTab(
      realApi({
        listRealConditionals: jest.fn(async () => ({
          items: [item('stalled', 'CANCELLING', { needsUserCancellation: true })],
          total: 1,
        })),
      }),
    );

    expect(screen.getByTestId('real-exits-stalled')).toHaveTextContent(text.exits.stalled);
    expect(screen.queryByTestId('real-exits-cancel-stalled')).not.toBeInTheDocument();
  });

  it('S48: cancels an OPEN conditional after the confirmation and shows CANCELLING', async () => {
    const api = realApi({
      listRealConditionals: jest.fn(async () => ({ items: [item('open', 'OPEN')], total: 1 })),
      cancelRealConditional: jest.fn(async () => item('open', 'CANCELLING')),
    });
    await renderTab(api);

    fireEvent.click(screen.getByTestId('real-exits-cancel-open'));
    await act(async () => settle(5));
    await act(async () => {
      fireEvent.click(screen.getByTestId('real-exits-cancel-confirm-submit'));
    });
    await act(async () => settle(20));

    expect(api.cancelRealConditional).toHaveBeenCalledWith('open', 'key-1');
    expect(screen.getByTestId('real-exits-state-open')).toHaveTextContent(
      text.exits.states.CANCELLING as string,
    );
  });

  it('says when there is no conditional yet', async () => {
    await renderTab(
      realApi({ listRealConditionals: jest.fn(async () => ({ items: [], total: 0 })) }),
    );

    expect(screen.getByTestId('real-exits-empty')).toHaveTextContent(text.exits.empty);
  });

  it('an error of the list is in text with "Tentar de novo"', async () => {
    const api = realApi({
      listRealConditionals: jest.fn(async () => {
        throw problem(503, 'SERVICE_UNAVAILABLE');
      }),
    });
    await renderTab(api);

    expect(screen.getByTestId('real-exits-list-error')).toBeInTheDocument();
  });
});
