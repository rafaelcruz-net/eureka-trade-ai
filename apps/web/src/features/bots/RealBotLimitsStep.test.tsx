import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { RealAccountLimits } from '@/lib/api/guest-api';
import { RealBotLimitsStep, type LimitsLoad } from '@/features/bots/RealBotLimitsStep';

const LIMITS: RealAccountLimits = {
  currency: 'SOL',
  cap: { status: 'KNOWN', value: '5000000000' },
  orderMax: { status: 'KNOWN', value: '500000000', percentOfCap: '10' },
  dailyBuys: { status: 'KNOWN', count: 20 },
  dailyLoss: { status: 'KNOWN', value: '1000000000', percentOfCap: '20' },
};

function renderStep(load: LimitsLoad) {
  const handlers = { onRetry: jest.fn(), onBack: jest.fn(), onContinue: jest.fn() };
  render(<RealBotLimitsStep load={load} {...handlers} />);
  return handlers;
}

/**
 * 261121-paper-real-unico U1, Step 18 (US1.1; BR1.8, BR3.1, BR3.2, BR3.4; mockups M3b): the limits step of a REAL bot
 * shows the four REAL limits of the account read only, in SOL, with "não definido" for a missing value.
 */
describe('RealBotLimitsStep', () => {
  it('shows the four limits in SOL with the share of the cap and continues without any field', async () => {
    const handlers = renderStep({ kind: 'ready', limits: LIMITS });

    expect(screen.getByTestId('real-limits-cap')).toHaveTextContent('5,00 SOL');
    expect(screen.getByTestId('real-limits-order')).toHaveTextContent('0,50 SOL (10% do teto)');
    expect(screen.getByTestId('real-limits-buys')).toHaveTextContent('até 20');
    expect(screen.getByTestId('real-limits-loss')).toHaveTextContent('1,00 SOL (20% do teto)');
    expect(screen.getByTestId('real-limits-change')).toHaveTextContent('Alterar limites');
    expect(screen.queryByRole('textbox')).toBeNull();
    await userEvent.click(screen.getByTestId('real-limits-continue'));
    expect(handlers.onContinue).toHaveBeenCalledTimes(1);
  });

  it('without a cap the order and the loss are "não definido (depende do teto)" and the link defines the cap', () => {
    renderStep({
      kind: 'ready',
      limits: {
        ...LIMITS,
        cap: { status: 'UNKNOWN', reason: 'NOT_DEFINED' },
        orderMax: { status: 'UNKNOWN', reason: 'NOT_DEFINED' },
        dailyLoss: { status: 'UNKNOWN', reason: 'NOT_DEFINED' },
      },
    });

    expect(screen.getByTestId('real-limits-cap')).toHaveTextContent('não definido');
    expect(screen.getByTestId('real-limits-order')).toHaveTextContent(
      'não definido (depende do teto)',
    );
    expect(screen.getByTestId('real-limits-loss')).toHaveTextContent(
      'não definido (depende do teto)',
    );
    expect(screen.getByTestId('real-limits-buys')).toHaveTextContent('até 20');
    expect(screen.getByTestId('real-limits-change')).toHaveTextContent('Definir o teto');
    expect(screen.getByTestId('real-limits-continue')).toBeEnabled();
  });

  it('while loading "Continuar" is disabled and says to wait', () => {
    renderStep({ kind: 'loading' });

    expect(screen.getByTestId('real-limits-loading')).toHaveTextContent(
      'Espere os limites carregarem.',
    );
    expect(screen.getByTestId('real-limits-continue')).toBeDisabled();
  });

  it('after an error "Continuar" is disabled and "Tentar de novo" reads again', async () => {
    const handlers = renderStep({ kind: 'error', code: 'corr-503' });

    expect(screen.getByTestId('real-limits-error')).toHaveTextContent('corr-503');
    expect(screen.getByTestId('real-limits-continue')).toBeDisabled();
    await userEvent.click(screen.getByTestId('real-limits-retry'));
    expect(handlers.onRetry).toHaveBeenCalledTimes(1);
  });

  it('"Voltar" goes back by keyboard', async () => {
    const handlers = renderStep({ kind: 'ready', limits: LIMITS });

    screen.getByTestId('real-limits-back').focus();
    await userEvent.keyboard('{Enter}');
    expect(handlers.onBack).toHaveBeenCalledTimes(1);
  });

  it('a percentage with decimals uses the comma of pt-BR', () => {
    renderStep({
      kind: 'ready',
      limits: {
        ...LIMITS,
        orderMax: { status: 'KNOWN', value: '625000000', percentOfCap: '12.5' },
      },
    });

    expect(screen.getByTestId('real-limits-order')).toHaveTextContent('0,625 SOL (12,5% do teto)');
  });
});
