import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import { tokenStore } from '@/lib/auth/token-store';
import type { RealBotWallet } from '@/lib/api/guest-api';
import { fakeApi, fakeWallet, Providers } from '@/features/auth/test-support';
import type { WalletPort } from '@/features/auth/wallets';
import { BotWalletScreen } from './BotWalletScreen';
import BotWalletPage from './page';
import { formatSol, formatSolAmount, parseSol } from './lamports';

const replace = jest.fn();

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: replace }),
  useSearchParams: () => new URLSearchParams(),
}));

const ADDRESS = 'BotWa11et1111111111111111111111111111111111';
const MAIN = 'MainWa11et111111111111111111111111111111111';

const WALLET: RealBotWallet = {
  address: ADDRESS,
  mainWalletAddress: MAIN,
  status: 'ACTIVE',
  mode: 'SIGNED',
  capLamports: '2000000000',
  maxCapLamports: '10000000000',
  maxSlippageBps: 200,
  balance: {
    totalLamports: '5000000000',
    freeLamports: '4500000000',
    reservedLamports: '500000000',
    feeReserveLamports: '30000000',
    aboveCapLamports: '2470000000',
    operableLamports: '2000000000',
    asOf: '2026-11-14T12:00:00Z',
  },
  pausedReason: 'NONE',
  network: 'LOCALNET',
  policyResetRequired: false,
};

function renderScreen(
  api = fakeApi(),
  pickWallet: () => Promise<WalletPort | null> = async () => fakeWallet(),
) {
  tokenStore.set('access-token-value');
  render(
    <Providers api={api}>
      <BotWalletScreen pickWallet={pickWallet} />
    </Providers>,
  );
  return api;
}

describe('Bot wallet screen /real/carteira', () => {
  beforeEach(() => {
    replace.mockReset();
    tokenStore.clear();
  });

  it('shows loading and then the address, the balance and the cap', async () => {
    renderScreen(fakeApi({ getBotWallet: jest.fn(async () => WALLET) }));

    expect(screen.getByTestId('real-wallet-loading')).toBeInTheDocument();
    expect(await screen.findByTestId('real-wallet-address')).toHaveTextContent(ADDRESS);
    expect(screen.getByTestId('real-wallet-total')).toHaveTextContent('5 SOL');
    expect(screen.getByTestId('real-wallet-free')).toHaveTextContent('4,5 SOL');
    expect(screen.getByTestId('real-wallet-fee-reserve')).toHaveTextContent('0,03 SOL');
    expect(screen.getByTestId('real-wallet-above-cap')).toHaveTextContent('2,47 SOL');
    expect(screen.getByTestId('real-wallet-main')).toHaveTextContent(MAIN);
  });

  it('shows the activation when there is no wallet and activates with the signed challenge', async () => {
    const wallet = fakeWallet();
    const api = fakeApi({
      requestRealChallenge: jest.fn(async () => ({
        challengeId: '0b8f0d4e-6d7e-4a43-9a3c-2a8f51f0c001',
        message: 'Eureka Trader - consentimento REAL v1',
        nonce: 'n',
        expiresAt: '2026-11-14T12:05:00Z',
      })),
      activateBotWallet: jest.fn(async () => WALLET),
    });
    renderScreen(api, async () => wallet);

    await screen.findByTestId('real-wallet-activation');
    await userEvent.clear(screen.getByTestId('real-wallet-cap-input'));
    await userEvent.type(screen.getByTestId('real-wallet-cap-input'), '2');
    await userEvent.click(screen.getByTestId('real-wallet-activate-button'));

    expect(await screen.findByTestId('real-wallet-address')).toHaveTextContent(ADDRESS);
    expect(api.requestRealChallenge).toHaveBeenCalledWith({
      kind: 'ACTIVATE',
      capLamports: '2000000000',
    });
    expect(wallet.signMessage).toHaveBeenCalledWith(
      new TextEncoder().encode('Eureka Trader - consentimento REAL v1'),
    );
    expect(api.activateBotWallet).toHaveBeenCalledWith(
      expect.objectContaining({ challengeId: '0b8f0d4e-6d7e-4a43-9a3c-2a8f51f0c001' }),
    );
  });

  it('explains in text why the activation is unavailable', async () => {
    renderScreen();

    await screen.findByTestId('real-wallet-activation');
    await userEvent.click(screen.getByTestId('real-wallet-activate-button'));

    expect(await screen.findByTestId('real-wallet-problem')).toHaveTextContent(
      'O REAL está desligado neste ambiente.',
    );
  });

  it('refuses an invalid cap and says there is no compatible wallet', async () => {
    renderScreen(fakeApi(), async () => null);

    await screen.findByTestId('real-wallet-activation');
    await userEvent.clear(screen.getByTestId('real-wallet-cap-input'));
    await userEvent.type(screen.getByTestId('real-wallet-cap-input'), 'abc');
    await userEvent.click(screen.getByTestId('real-wallet-activate-button'));
    expect(await screen.findByTestId('real-wallet-problem')).toHaveTextContent(
      'Informe um valor em SOL',
    );

    await userEvent.clear(screen.getByTestId('real-wallet-cap-input'));
    await userEvent.type(screen.getByTestId('real-wallet-cap-input'), '1');
    await userEvent.click(screen.getByTestId('real-wallet-activate-button'));
    expect(await screen.findByTestId('real-wallet-problem')).toHaveTextContent(
      'Nenhuma carteira compatível',
    );
  });

  it('shows an error state with a retry', async () => {
    const getBotWallet = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
      .mockResolvedValueOnce(WALLET);
    renderScreen(fakeApi({ getBotWallet }));

    await userEvent.click(await screen.findByTestId('real-wallet-retry'));

    expect(await screen.findByTestId('real-wallet-address')).toBeInTheDocument();
  });

  it('withdraws an amount with one idempotency key and then everything with a new one', async () => {
    const api = fakeApi({
      getBotWallet: jest.fn(async () => WALLET),
      requestRealWithdrawal: jest.fn(async () => ({
        withdrawalId: '0b8f0d4e-6d7e-4a43-9a3c-2a8f51f0c002',
        kind: 'PARTIAL',
        lamports: '1000000000',
        feeLamports: '5000',
        state: 'REQUESTED',
        requestedAt: '2026-11-14T12:00:00Z',
      })),
    });
    renderScreen(api);

    await userEvent.type(await screen.findByTestId('real-wallet-amount-input'), '1');
    await userEvent.click(screen.getByTestId('real-wallet-withdraw-button'));
    expect(await screen.findByTestId('real-wallet-withdrawal-state')).toHaveTextContent(
      'REQUESTED',
    );
    await userEvent.click(screen.getByTestId('real-wallet-withdraw-all-button'));

    await waitFor(() => expect(api.requestRealWithdrawal).toHaveBeenCalledTimes(2));
    const [first, second] = api.requestRealWithdrawal.mock.calls;
    expect(first?.[0]).toEqual({ amount: { lamports: '1000000000' } });
    expect(second?.[0]).toEqual({ amount: { all: true } });
    expect(first?.[1]).not.toEqual(second?.[1]);
  });

  it('shows the refusal of a withdrawal above the free balance', async () => {
    renderScreen(
      fakeApi({
        getBotWallet: jest.fn(async () => WALLET),
        requestRealWithdrawal: jest.fn(async () => {
          throw new ApiError(422, 'INSUFFICIENT_FREE_BALANCE');
        }),
      }),
    );

    await userEvent.type(await screen.findByTestId('real-wallet-amount-input'), '100');
    await userEvent.click(screen.getByTestId('real-wallet-withdraw-button'));

    expect(await screen.findByTestId('real-wallet-problem')).toHaveTextContent(
      'Saldo livre insuficiente',
    );
  });

  it('renders the page component', async () => {
    tokenStore.set('access-token-value');
    render(
      <Providers api={fakeApi()}>
        <BotWalletPage />
      </Providers>,
    );

    expect(await screen.findByRole('heading', { name: 'Carteira do bot' })).toBeInTheDocument();
  });
});

describe('lamports as text', () => {
  it('formats and parses without floating point', () => {
    expect(formatSol('0')).toBe('0');
    expect(formatSol('1500000000')).toBe('1,5');
    expect(formatSol('1234000000000')).toBe('1.234');
    expect(formatSol('1')).toBe('0,000000001');
    expect(parseSol('1,5')).toBe('1500000000');
    expect(parseSol('0.000000001')).toBe('1');
    expect(parseSol('0')).toBeNull();
    expect(parseSol('1,0000000001')).toBeNull();
    expect(parseSol('-1')).toBeNull();
    expect(formatSolAmount(undefined)).toBe('indisponível');
    expect(formatSolAmount('2000000000')).toBe('2 SOL');
  });
});
