import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BotModeFilter, parseModeFilter } from '@/features/bots/BotModeFilter';

/** 261121-paper-real-unico U3, Step 17 (BR1.1, BR1.4, BR1.5, BR1.7; AC2.3.1, AC2.3.5). */
describe('BotModeFilter', () => {
  it('offers Todos, Simulado and Real as native radios of the fieldset "Modo", Todos by default', () => {
    render(<BotModeFilter value="ALL" onChange={jest.fn()} />);

    const group = screen.getByRole('group', { name: 'Modo' });
    expect(group.tagName).toBe('FIELDSET');
    const radios = screen.getAllByRole('radio');
    expect(radios.map((radio) => radio.getAttribute('value'))).toEqual(['ALL', 'PAPER', 'REAL']);
    expect(screen.getByRole('radio', { name: 'Todos' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Simulado' })).not.toBeChecked();
    radios.forEach((radio) => expect(radio).toHaveAttribute('name', 'bot-mode-filter'));
  });

  it('puts the initial focus on the checked radio', () => {
    render(<BotModeFilter value="REAL" onChange={jest.fn()} />);

    expect(screen.getByTestId('bots-mode-filter-REAL')).toHaveFocus();
  });

  it('does not take the focus when autoFocus is off', () => {
    render(<BotModeFilter value="REAL" onChange={jest.fn()} autoFocus={false} />);

    expect(screen.getByTestId('bots-mode-filter-REAL')).not.toHaveFocus();
  });

  it('an arrow changes and applies the option without moving the focus out of the group', async () => {
    const onChange = jest.fn();
    render(<BotModeFilter value="ALL" onChange={onChange} />);

    await userEvent.keyboard('{ArrowRight}');

    expect(onChange).toHaveBeenCalledWith('PAPER');
    expect(document.activeElement?.getAttribute('name')).toBe('bot-mode-filter');
  });

  it('a click applies the option', async () => {
    const onChange = jest.fn();
    render(<BotModeFilter value="ALL" onChange={onChange} />);

    await userEvent.click(screen.getByTestId('bots-mode-filter-REAL'));

    expect(onChange).toHaveBeenCalledWith('REAL');
  });

  it('"Mostrando N de M bots." only with a filter, in a polite live region, with the numbers of the API', () => {
    const { rerender } = render(
      <BotModeFilter value="ALL" onChange={jest.fn()} matchingCount={3} totalCount={3} />,
    );
    const shown = screen.getByTestId('bots-shown');
    expect(shown).toHaveAttribute('aria-live', 'polite');
    expect(shown).toHaveTextContent('');

    rerender(<BotModeFilter value="REAL" onChange={jest.fn()} matchingCount={1} totalCount={3} />);
    expect(screen.getByTestId('bots-shown')).toHaveTextContent('Mostrando 1 de 3 bots.');

    rerender(<BotModeFilter value="PAPER" onChange={jest.fn()} matchingCount={0} totalCount={1} />);
    expect(screen.getByTestId('bots-shown')).toHaveTextContent('Mostrando 0 de 1 bot.');
  });

  it('an invalid ?mode is Todos', () => {
    expect(parseModeFilter('REAL')).toBe('REAL');
    expect(parseModeFilter('PAPER')).toBe('PAPER');
    expect(parseModeFilter('real')).toBe('ALL');
    expect(parseModeFilter('OTHER')).toBe('ALL');
    expect(parseModeFilter(null)).toBe('ALL');
  });
});
