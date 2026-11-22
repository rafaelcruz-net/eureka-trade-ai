import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import { tokenStore } from '@/lib/auth/token-store';
import type { GuestApi } from '@/lib/api/guest-api';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { BotCreate } from '@/features/bots/BotCreate';
import { summary, TEMPLATE } from '@/features/bots/fixtures';

const push = jest.fn();

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({}),
}));

function renderCreate(api: jest.Mocked<GuestApi>) {
  tokenStore.set('access-token-value');
  render(
    <Providers api={api}>
      <BotCreate timeZone="America/Sao_Paulo" />
    </Providers>,
  );
  return api;
}

function eligibleApi(overrides: Partial<GuestApi> = {}) {
  return fakeApi({
    listStrategyTemplates: jest.fn(async () => [TEMPLATE]),
    getRealEligibility: jest.fn(async () => ({ available: true })),
    ...overrides,
  });
}

async function chooseRealAndConfirm() {
  await userEvent.type(await screen.findByTestId('bot-create-name'), 'Robo real');
  await userEvent.click(screen.getByTestId('mode-REAL'));
  await userEvent.click(screen.getByTestId('bot-create-submit'));
  await userEvent.click(await screen.findByTestId('real-confirm-check'));
  await userEvent.click(screen.getByTestId('real-confirm-create'));
}

beforeEach(() => {
  push.mockReset();
  tokenStore.clear();
});

/**
 * 261121-paper-real-unico U1, Step 18 (US1.1 to US1.3; BR1.1, BR1.5, BR1.7, BR2.3, BR2.7, BR2.8): step 1 of 5, "Modo
 * e nome". Simulado is created at once with the market; Real goes through the confirmation; a domain refusal reads the
 * eligibility again and goes back to Simulado; a technical error keeps the dialog.
 */
describe('BotCreate, step 1 of 5', () => {
  it('reads the eligibility once, starts on Simulado and creates a PAPER draft with the market', async () => {
    const api = renderCreate(
      eligibleApi({ createBot: jest.fn(async () => summary({ state: 'DRAFT' })) }),
    );

    expect(screen.getByTestId('bot-create-step')).toHaveTextContent('Etapa 1 de 5: Modo e nome');
    expect(await screen.findByTestId('mode-PAPER')).toBeChecked();
    await userEvent.type(screen.getByTestId('bot-create-name'), 'Meu bot');
    expect(screen.getByTestId('bot-create-submit')).toHaveTextContent('Criar rascunho simulado');
    await userEvent.click(screen.getByTestId('bot-create-submit'));

    expect(api.createBot).toHaveBeenCalledWith({
      templateId: 'MOMENTUM_VOLUME',
      name: 'Meu bot',
      market: 'PAPER',
    });
    expect(api.getRealEligibility).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(push).toHaveBeenCalled());
  });

  it('a REAL bot needs the confirmation and is created with market REAL and the key of the dialog', async () => {
    const api = renderCreate(
      eligibleApi({
        createBot: jest.fn(async () => summary({ state: 'DRAFT', market: 'REAL', mode: 'REAL' })),
      }),
    );

    await chooseRealAndConfirm();

    await waitFor(() => expect(api.createBot).toHaveBeenCalledTimes(1));
    const [body, key] = api.createBot.mock.calls[0] as [unknown, string];
    expect(body).toEqual({ templateId: 'MOMENTUM_VOLUME', name: 'Robo real', market: 'REAL' });
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
    await waitFor(() => expect(push).toHaveBeenCalled());
  });

  it('choosing Real changes the button to the confirmation and sends nothing before it', async () => {
    const api = renderCreate(eligibleApi());

    await userEvent.type(await screen.findByTestId('bot-create-name'), 'Robo');
    await userEvent.click(screen.getByTestId('mode-REAL'));
    expect(screen.getByTestId('bot-create-submit')).toHaveTextContent(
      'Continuar para a confirmação',
    );
    await userEvent.click(screen.getByTestId('bot-create-submit'));

    expect(await screen.findByTestId('real-confirm')).toBeInTheDocument();
    expect(api.createBot).not.toHaveBeenCalled();
  });

  it('a domain refusal reads the eligibility again, goes back to Simulado and says why with the code', async () => {
    const getRealEligibility = jest
      .fn()
      .mockResolvedValueOnce({ available: true })
      .mockResolvedValueOnce({ available: false, reason: 'KILL_SWITCH' });
    renderCreate(
      eligibleApi({
        getRealEligibility,
        createBot: jest.fn(async () => {
          throw new ApiError(409, 'REAL_NOT_ELIGIBLE', 'KILL_SWITCH', 'corr-409');
        }),
      }),
    );

    await chooseRealAndConfirm();

    const refusal = await screen.findByTestId('bot-create-refused');
    expect(refusal).toHaveTextContent('O bot real não foi criado.');
    expect(refusal).toHaveTextContent(
      'As operações reais estão suspensas pelo sistema no momento.',
    );
    expect(refusal).toHaveTextContent('corr-409');
    await waitFor(() => expect(refusal).toHaveFocus());
    expect(screen.getByTestId('mode-PAPER')).toBeChecked();
    expect(screen.queryByTestId('real-confirm')).toBeNull();
    expect(screen.getByTestId('bot-create-name')).toHaveValue('Robo real');
    expect(getRealEligibility).toHaveBeenCalledTimes(2);
  });

  it('a technical error keeps the dialog open with "Tentar de novo"', async () => {
    renderCreate(
      eligibleApi({
        createBot: jest.fn(async () => {
          throw new ApiError(503, 'SERVICE_UNAVAILABLE', undefined, 'corr-503');
        }),
      }),
    );

    await chooseRealAndConfirm();

    expect(await screen.findByTestId('real-confirm-error')).toHaveTextContent('corr-503');
    expect(screen.getByTestId('real-confirm')).toBeInTheDocument();
  });

  it('a failed eligibility leaves Real in error with a retry, and Simulado still creates', async () => {
    const getRealEligibility = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE', undefined, 'corr-1'))
      .mockResolvedValueOnce({ available: false, reason: 'NOT_RELEASED' });
    renderCreate(eligibleApi({ getRealEligibility }));

    expect(await screen.findByTestId('mode-REAL-error')).toHaveTextContent('corr-1');
    await userEvent.click(screen.getByTestId('mode-REAL-retry'));

    expect(await screen.findByTestId('mode-REAL-reason')).toHaveTextContent(
      'Sua conta ainda não foi liberada para o modo real.',
    );
    expect(screen.getByTestId('mode-PAPER')).toBeChecked();
  });

  it('refuses an empty name before anything is sent, in either mode', async () => {
    const api = renderCreate(eligibleApi());

    await userEvent.click(await screen.findByTestId('mode-REAL'));
    await userEvent.click(screen.getByTestId('bot-create-submit'));

    expect(screen.getByTestId('bot-create-error')).toHaveTextContent('Informe um nome');
    expect(screen.queryByTestId('real-confirm')).toBeNull();
    expect(api.createBot).not.toHaveBeenCalled();
  });
});
