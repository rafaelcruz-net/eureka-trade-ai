import { render, screen } from '@testing-library/react';
import { parseEnvelope } from '@/features/dashboard/envelopes';
import { isEventKind } from '@/features/dashboard/warn';
import { availableModes, ModeSelector } from '@/features/real/components/ModeSelector';
import { realMessages as text } from '@/features/real/messages';

/**
 * 261113-trading-real-bot U7, Step 14, written after the code (BR8.1, BR9.3, BR5.5): the mode "Automática nas saídas"
 * follows `exitsEnabled` of the server (off: "Em breve", except for whoever is already in it), and the live channel
 * accepts `real.conditional.state` with its closed values.
 */
describe('the mode AUTO_EXITS follows the exits flag', () => {
  it('is offered when the server says the exits are on', () => {
    render(<ModeSelector current="SIGNED" value="SIGNED" onChange={jest.fn()} exitsEnabled />);

    expect(screen.getByTestId('real-mode-option-AUTO_EXITS')).toBeEnabled();
    expect(availableModes(true)).toContain('AUTO_EXITS');
  });

  it('is disabled with "Em breve" when the exits are off', () => {
    render(<ModeSelector current="SIGNED" value="SIGNED" onChange={jest.fn()} />);

    expect(screen.getByTestId('real-mode-option-AUTO_EXITS')).toBeDisabled();
    expect(screen.getByText(text.mode.soon)).toBeInTheDocument();
  });

  it('whoever is already in AUTO_EXITS stays in it with the exits off (BR8.1)', () => {
    render(<ModeSelector current="AUTO_EXITS" value="AUTO_EXITS" onChange={jest.fn()} />);

    expect(screen.getByTestId('real-mode-option-AUTO_EXITS')).toBeEnabled();
  });
});

describe('real.conditional.state on the live channel', () => {
  const data = {
    type: 'REAL_CONDITIONAL_STATE',
    conditionalOrderId: '0c0c0000-0000-4000-8000-0000000000c1',
    positionMint: 'TestMint111111111111111111111111111111111111',
    state: 'FILLED',
    filledSide: 'TAKE_PROFIT',
    at: '2026-11-17T12:00:00Z',
  };

  it('is a known event and its envelope is checked', () => {
    expect(isEventKind('real.conditional.state')).toBe(true);
    expect(parseEnvelope('real.conditional.state', JSON.stringify(data))).toEqual({
      kind: 'real.conditional.state',
      data,
    });
  });

  it('an unknown state or side is discarded', () => {
    expect(
      parseEnvelope('real.conditional.state', JSON.stringify({ ...data, state: 'LOST' })),
    ).toBeNull();
    expect(
      parseEnvelope('real.conditional.state', JSON.stringify({ ...data, filledSide: 'BOTH' })),
    ).toBeNull();
  });

  it('a conditional without a filled side keeps none', () => {
    const open = { ...data, state: 'OPEN', filledSide: undefined };

    expect(parseEnvelope('real.conditional.state', JSON.stringify(open))?.data).not.toHaveProperty(
      'filledSide',
    );
  });
});
