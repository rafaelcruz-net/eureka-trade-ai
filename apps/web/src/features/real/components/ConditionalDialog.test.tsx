import { act, fireEvent, render, screen } from '@testing-library/react';
import type { GuestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { settle } from '@/features/dashboard/test-support';
import type { WalletPort } from '@/features/auth/wallets';
import { ConditionalDialog } from '@/features/real/components/ConditionalDialog';
import { realMessages as text } from '@/features/real/messages';
import { MINT, problem, RealProviders, realApi, signingWallet } from '@/features/real/test-support';

/**
 * 261113-trading-real-bot U7, Step 14, written after the code (BR8.2, BR3, BR2.6, BR3.8, BR1.2; S1 to S5, S43): the
 * dialog "Definir stop e alvo" shows the current price with its source, takes the prices as decimal text, says the
 * bounds and the warning of the stop, and creates the conditional with the consent signed by the main wallet.
 */
const ID = '0c0c0000-0000-4000-8000-0000000000c1';

function quote(overrides: Record<string, unknown> = {}) {
  return {
    positionMint: MINT,
    positionBaseUnits: '5000',
    priceStatus: 'KNOWN' as const,
    priceUsd: '0.0001',
    source: 'sim-a',
    observedAt: '2026-11-17T12:00:00Z',
    minStopPriceUsd: '0.00005',
    maxTakeProfitPriceUsd: '0.001',
    ...overrides,
  };
}

function conditional(state: 'OPEN' | 'PENDING_SIGNATURE' = 'OPEN') {
  return {
    conditionalOrderId: ID,
    positionMint: MINT,
    kind: 'OCO' as const,
    stopPriceUsd: '0.00008',
    takeProfitPriceUsd: '0.00015',
    amountBaseUnits: '5000',
    state,
    needsUserCancellation: false,
    createdAt: '2026-11-17T12:00:00Z',
  };
}

function exitsApi(overrides: Partial<GuestApi> = {}) {
  return realApi({
    quoteRealConditional: jest.fn(async () => quote()),
    createRealConditional: jest.fn(async () => ({
      conditionalOrderId: ID,
      conditionalOrder: conditional('PENDING_SIGNATURE'),
      signingSession: {
        kind: 'MESSAGE' as const,
        message: 'Eureka Trader - saída automática REAL',
        deadline: '2026-11-17T12:01:00Z',
        serverTime: '2026-11-17T12:00:00Z',
      },
      stopNotGuaranteed: true,
    })),
    signRealConditional: jest.fn(async () => conditional('OPEN')),
    ...overrides,
  });
}

async function renderDialog(
  api: jest.Mocked<GuestApi>,
  wallet: WalletPort | null = signingWallet(),
  onCreated = jest.fn(),
) {
  tokenStore.set('access-token-value');
  render(
    <RealProviders api={api}>
      <ConditionalDialog
        api={api}
        mint={MINT}
        tokenLabel="Test…1111"
        pickWallet={async () => wallet}
        timeZone="UTC"
        newKey={() => 'key-1'}
        onClose={jest.fn()}
        onCreated={onCreated}
      />
    </RealProviders>,
  );
  await act(async () => settle(20));
  return onCreated;
}

function type(testId: string, value: string) {
  fireEvent.change(screen.getByTestId(testId), { target: { value } });
}

afterEach(() => tokenStore.clear());

describe('ConditionalDialog', () => {
  it('shows the current price in USD with its source, the position, the bounds and the stop warning', async () => {
    await renderDialog(exitsApi());

    expect(screen.getByTestId('real-exits-price')).toHaveTextContent('0,0001');
    expect(screen.getByTestId('real-exits-price-source')).toHaveTextContent('sim-a');
    expect(screen.getByTestId('real-exits-position')).toHaveTextContent('5000');
    expect(screen.getByTestId('real-exits-bounds')).toHaveTextContent('0,00005');
    expect(screen.getByTestId('real-exits-bounds')).toHaveTextContent('0,001');
    expect(screen.getByTestId('real-exits-stop-warning')).toHaveTextContent(text.exits.stopWarning);
  });

  it('S3: without a current price the confirmation stays off and says "Sem preço atual"', async () => {
    await renderDialog(
      exitsApi({
        quoteRealConditional: jest.fn(async () =>
          quote({ priceStatus: 'UNKNOWN', priceUsd: undefined, minStopPriceUsd: undefined }),
        ),
      }),
    );
    type('real-exits-stop', '0,00008');
    type('real-exits-take-profit', '0,00015');

    expect(screen.getByTestId('real-exits-price')).toHaveTextContent(text.exits.noPrice);
    expect(screen.getByTestId('real-exits-confirm')).toBeDisabled();
  });

  it('S4, S5, S43: the order of the OCO, the side and the bounds are said before sending', async () => {
    await renderDialog(exitsApi());

    type('real-exits-stop', '0,00009');
    type('real-exits-take-profit', '0,00008');
    expect(screen.getByTestId('real-exits-trigger-problem')).toHaveTextContent(
      text.errors.TP_NOT_ABOVE_SL,
    );
    type('real-exits-stop', '0,00011');
    type('real-exits-take-profit', '0,00015');
    expect(screen.getByTestId('real-exits-trigger-problem')).toHaveTextContent(
      text.errors.TRIGGER_ON_WRONG_SIDE,
    );
    type('real-exits-stop', '0,00004');
    expect(screen.getByTestId('real-exits-trigger-problem')).toHaveTextContent(
      text.errors.TRIGGER_OUT_OF_BOUNDS,
    );
    expect(screen.getByTestId('real-exits-confirm')).toBeDisabled();
  });

  it('a price that is not a decimal is refused in the field, never sent', async () => {
    const api = exitsApi();
    await renderDialog(api);

    type('real-exits-stop', '8e-5');

    expect(screen.getByTestId('real-exits-stop-error')).toHaveTextContent(
      text.exits.invalid.format as string,
    );
    expect(screen.getByTestId('real-exits-confirm')).toBeDisabled();
    expect(api.createRealConditional).not.toHaveBeenCalled();
  });

  it('S1, S2: creates with the decimal text, signs the consent unchanged and sends the signature', async () => {
    const api = exitsApi();
    const wallet = signingWallet();
    const onCreated = await renderDialog(api, wallet);
    type('real-exits-stop', '0,00008');
    type('real-exits-take-profit', '0,00015');

    await act(async () => {
      fireEvent.click(screen.getByTestId('real-exits-confirm'));
    });
    await act(async () => settle(20));

    expect(api.createRealConditional).toHaveBeenCalledWith(
      { positionMint: MINT, kind: 'OCO', stopPriceUsd: '0.00008', takeProfitPriceUsd: '0.00015' },
      'key-1',
    );
    expect(new TextDecoder().decode((wallet.signMessage as jest.Mock).mock.calls[0][0])).toBe(
      'Eureka Trader - saída automática REAL',
    );
    expect(api.signRealConditional).toHaveBeenCalledWith(
      ID,
      { signature: expect.any(String) },
      'key-1',
    );
    expect(onCreated).toHaveBeenCalledWith(expect.objectContaining({ id: ID, state: 'OPEN' }));
  });

  it('only the stop of a STOP is sent', async () => {
    const api = exitsApi();
    await renderDialog(api);
    fireEvent.click(screen.getByTestId('real-exits-kind-STOP'));
    type('real-exits-stop', '0,00009');

    await act(async () => {
      fireEvent.click(screen.getByTestId('real-exits-confirm'));
    });
    await act(async () => settle(20));

    expect(api.createRealConditional).toHaveBeenCalledWith(
      { positionMint: MINT, kind: 'STOP', stopPriceUsd: '0.00009' },
      'key-1',
    );
    expect(screen.queryByTestId('real-exits-take-profit')).not.toBeInTheDocument();
  });

  it('a refusal in the wallet sends nothing and says so', async () => {
    const api = exitsApi();
    await renderDialog(
      api,
      signingWallet({
        signMessage: jest.fn(async () => {
          throw new Error('User rejected');
        }),
      }),
    );
    type('real-exits-stop', '0,00008');
    type('real-exits-take-profit', '0,00015');

    await act(async () => {
      fireEvent.click(screen.getByTestId('real-exits-confirm'));
    });
    await act(async () => settle(20));

    expect(screen.getByTestId('real-exits-problem')).toHaveTextContent(text.walletRefused);
    expect(api.signRealConditional).not.toHaveBeenCalled();
  });

  it('a refusal of the server is in text (S10: EXITS_DISABLED)', async () => {
    const api = exitsApi({
      createRealConditional: jest.fn(async () => {
        throw problem(403, 'EXITS_DISABLED');
      }),
    });
    await renderDialog(api);
    type('real-exits-stop', '0,00008');
    type('real-exits-take-profit', '0,00015');

    await act(async () => {
      fireEvent.click(screen.getByTestId('real-exits-confirm'));
    });
    await act(async () => settle(20));

    expect(screen.getByTestId('real-exits-problem')).toHaveTextContent(text.errors.EXITS_DISABLED);
  });

  it('a session by transaction is not offered before the real recording', async () => {
    const api = exitsApi({
      createRealConditional: jest.fn(async () => ({
        conditionalOrderId: ID,
        conditionalOrder: conditional('PENDING_SIGNATURE'),
        signingSession: {
          kind: 'TRANSACTION' as const,
          transactionBase64: 'AQID',
          deadline: '2026-11-17T12:01:00Z',
          serverTime: '2026-11-17T12:00:00Z',
        },
        stopNotGuaranteed: true,
      })),
    });
    await renderDialog(api);
    type('real-exits-stop', '0,00008');
    type('real-exits-take-profit', '0,00015');

    await act(async () => {
      fireEvent.click(screen.getByTestId('real-exits-confirm'));
    });
    await act(async () => settle(20));

    expect(screen.getByTestId('real-exits-problem')).toHaveTextContent(
      text.exits.transactionNotYet,
    );
    expect(api.signRealConditional).not.toHaveBeenCalled();
  });

  it('without a wallet nothing is created', async () => {
    const api = exitsApi();
    await renderDialog(api, null);
    type('real-exits-stop', '0,00008');
    type('real-exits-take-profit', '0,00015');

    await act(async () => {
      fireEvent.click(screen.getByTestId('real-exits-confirm'));
    });
    await act(async () => settle(20));

    expect(api.createRealConditional).not.toHaveBeenCalled();
    expect(screen.getByTestId('real-exits-problem')).toBeInTheDocument();
  });
});
