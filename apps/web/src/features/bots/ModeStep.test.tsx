import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import type { BotMarket } from '@/lib/api/guest-api';
import { ModeStep, quarantineWhen, type EligibilityLoad } from '@/features/bots/ModeStep';

const ZONE = 'America/Sao_Paulo';

function Harness({
  eligibility,
  onRetry = jest.fn(),
}: {
  eligibility: EligibilityLoad;
  onRetry?: () => void;
}) {
  const [value, setValue] = useState<BotMarket>('PAPER');
  return (
    <>
      <ModeStep
        value={value}
        onChange={setValue}
        eligibility={eligibility}
        onRetry={onRetry}
        timeZone={ZONE}
      />
      <p data-testid="chosen">{value}</p>
    </>
  );
}

/**
 * 261121-paper-real-unico U1, Step 18 (US1.1, US1.2; BR1.7, BR2.7, BR9.4; AC1.1.x, AC1.2.x): the choice of the mode.
 * "Simulado" is the default and always available; an unavailable "Real" stays in the keyboard order with
 * aria-disabled and its reason, and the click, the Space and the arrows never choose it.
 */
describe('ModeStep', () => {
  it('starts on Simulado inside the fieldset "Modo do bot", whatever the eligibility', () => {
    render(<Harness eligibility={{ kind: 'ready', eligibility: { available: true } }} />);

    expect(screen.getByRole('group', { name: 'Modo do bot' })).toBeInTheDocument();
    expect(screen.getByTestId('mode-PAPER')).toBeChecked();
    expect(screen.getByTestId('chosen')).toHaveTextContent('PAPER');
    expect(screen.getByTestId('mode-PAPER-badge')).toHaveTextContent('SIMULADO');
  });

  it('an available Real is chosen with the keyboard and back again', async () => {
    render(<Harness eligibility={{ kind: 'ready', eligibility: { available: true } }} />);

    screen.getByTestId('mode-PAPER').focus();
    await userEvent.keyboard('{ArrowDown}');
    expect(screen.getByTestId('chosen')).toHaveTextContent('REAL');
    await userEvent.click(screen.getByTestId('mode-PAPER'));
    expect(screen.getByTestId('chosen')).toHaveTextContent('PAPER');
  });

  it('an unavailable Real keeps aria-disabled, says why in a live region and the click never chooses it', async () => {
    render(
      <Harness
        eligibility={{ kind: 'ready', eligibility: { available: false, reason: 'KILL_SWITCH' } }}
      />,
    );

    const real = screen.getByTestId('mode-REAL');
    expect(real).toHaveAttribute('aria-disabled', 'true');
    expect(real).not.toBeDisabled();
    expect(screen.getByTestId('mode-REAL-reason')).toHaveTextContent(
      'As operações reais estão suspensas pelo sistema no momento.',
    );
    expect(screen.getByTestId('mode-live')).toHaveTextContent('Real indisponível:');
    await userEvent.click(real);
    real.focus();
    await userEvent.keyboard(' ');
    expect(screen.getByTestId('chosen')).toHaveTextContent('PAPER');
  });

  it.each([
    ['REAL_DISABLED', 'O modo real está desligado no momento.'],
    ['NOT_RELEASED', 'Sua conta ainda não foi liberada para o modo real.'],
  ] as const)('the reason %s has its own text and no link', (reason, sentence) => {
    render(<Harness eligibility={{ kind: 'ready', eligibility: { available: false, reason } }} />);

    expect(screen.getByTestId('mode-REAL-reason')).toHaveTextContent(sentence);
    expect(screen.queryByTestId('mode-REAL-activate')).toBeNull();
  });

  it('only the inactive wallet offers the link to activate it', () => {
    render(
      <Harness
        eligibility={{
          kind: 'ready',
          eligibility: {
            available: false,
            reason: 'WALLET_INACTIVE',
            action: 'ACTIVATE_BOT_WALLET',
          },
        }}
      />,
    );

    expect(screen.getByTestId('mode-REAL-activate')).toHaveAttribute('href', '/real');
    expect(screen.getByTestId('mode-REAL-reason')).toHaveTextContent(
      'A carteira do bot não está ativa.',
    );
  });

  it('the quarantine says from when it counts, in the time zone of the person', () => {
    render(
      <Harness
        eligibility={{
          kind: 'ready',
          eligibility: {
            available: false,
            reason: 'QUARANTINE',
            effectiveAt: '2026-11-22T13:30:00Z',
          },
        }}
      />,
    );

    expect(screen.getByTestId('mode-REAL-reason')).toHaveTextContent(
      'Vale a partir de 22/11 10:30.',
    );
    expect(quarantineWhen('2026-11-22T13:30:00Z', 'UTC')).toBe('22/11 13:30');
  });

  it('loading and error are distinct states and "Tentar de novo" reads again; Simulado stays available', async () => {
    const onRetry = jest.fn();
    const { rerender } = render(<Harness eligibility={{ kind: 'loading' }} />);
    expect(screen.getByTestId('mode-REAL-loading')).toHaveTextContent(
      'Conferindo se o modo real está disponível…',
    );

    rerender(<Harness eligibility={{ kind: 'error', code: 'corr-7' }} onRetry={onRetry} />);
    expect(screen.getByTestId('mode-REAL-error')).toHaveTextContent('corr-7');
    await userEvent.click(screen.getByTestId('mode-REAL-retry'));
    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('mode-PAPER')).not.toHaveAttribute('aria-disabled');
  });
});
