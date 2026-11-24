import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  applyPeriod,
  PeriodSelector,
  periodAddress,
  periodFromAddress,
  SINCE_START,
  type AppliedPeriod,
} from '@/features/pnl/PeriodSelector';

const ZONE = 'America/Sao_Paulo';
// 2026-11-23T02:30Z is still 22/11 in São Paulo.
const NOW = () => new Date('2026-11-23T02:30:00Z');

function renderSelector(value: AppliedPeriod = SINCE_START, now = NOW) {
  const onChange = jest.fn();
  const view = render(
    <PeriodSelector value={value} onChange={onChange} timeZone={ZONE} now={now} />,
  );
  return { onChange, ...view };
}

function typeDay(testId: string, value: string) {
  fireEvent.change(screen.getByTestId(testId), { target: { value } });
}

/** 261121-paper-real-unico U3, Step 18 (BR2.1 to BR2.3, BR2.7; AC7.1.1 to AC7.1.4). */
describe('PeriodSelector', () => {
  it('offers the five options as native radios of the fieldset "Período", "Desde o início" by default', () => {
    renderSelector();

    expect(screen.getByRole('group', { name: 'Período' })).toBeInTheDocument();
    expect(
      screen.getAllByRole('radio').map((radio) => radio.parentElement?.textContent?.trim()),
    ).toEqual(['Desde o início', 'Hoje', 'Últimos 7 dias', 'Últimos 30 dias', 'Intervalo']);
    expect(screen.getByRole('radio', { name: 'Desde o início' })).toBeChecked();
    expect(screen.getByTestId('pnl-period-showing')).toHaveTextContent(
      `Mostrando: desde o início · datas no seu fuso (${ZONE})`,
    );
    expect(screen.getByTestId('pnl-period-showing')).toHaveAttribute('aria-live', 'polite');
  });

  it('Hoje, 7 and 30 days apply at once with the UTC pair of whole local days', async () => {
    const { onChange } = renderSelector();

    await userEvent.click(screen.getByRole('radio', { name: 'Hoje' }));
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ kind: 'TODAY', fromDay: '2026-11-22', toDay: '2026-11-22' }),
      { from: '2026-11-22T03:00:00Z', to: '2026-11-23T03:00:00Z' },
    );
    await userEvent.click(screen.getByRole('radio', { name: 'Últimos 7 dias' }));
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ kind: 'LAST_7' }), {
      from: '2026-11-16T03:00:00Z',
      to: '2026-11-23T03:00:00Z',
    });
    await userEvent.click(screen.getByRole('radio', { name: 'Últimos 30 dias' }));
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ kind: 'LAST_30' }), {
      from: '2026-10-24T03:00:00Z',
      to: '2026-11-23T03:00:00Z',
    });
    await userEvent.click(screen.getByRole('radio', { name: 'Desde o início' }));
    expect(onChange).toHaveBeenLastCalledWith(SINCE_START, null);
  });

  it('the applied pair stays the same after the local midnight', () => {
    const applied = applyPeriod('LAST_7', ZONE, NOW());
    if (!applied.ok) {
      throw new Error('a preset is valid');
    }
    renderSelector(applied.period, () => new Date('2026-11-24T05:00:00Z'));

    expect(applied.period.utc).toEqual({
      from: '2026-11-16T03:00:00Z',
      to: '2026-11-23T03:00:00Z',
    });
    expect(screen.getByTestId('pnl-period-showing')).toHaveTextContent(
      'Mostrando: 16/11/2026 a 22/11/2026 · realizado no período; não realizado de agora',
    );
  });

  it('an interval applies with "Aplicar", inclusive, with labelled date fields', async () => {
    const { onChange } = renderSelector();

    await userEvent.click(screen.getByRole('radio', { name: 'Intervalo' }));
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByLabelText('De')).toHaveAttribute('type', 'date');
    expect(screen.getByLabelText('Até (inclusive)')).toHaveAttribute('type', 'date');
    typeDay('pnl-from', '2026-11-01');
    typeDay('pnl-to', '2026-11-01');
    await userEvent.click(screen.getByTestId('pnl-apply'));

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'RANGE', fromDay: '2026-11-01', toDay: '2026-11-01' }),
      { from: '2026-11-01T03:00:00Z', to: '2026-11-02T03:00:00Z' },
    );
  });

  it.each([
    ['', '2026-11-01', 'pnl-from', 'Informe a data no formato dd/mm/aaaa.'],
    [
      '2026-11-10',
      '2026-11-01',
      'pnl-to',
      'A data final vem antes da inicial. Corrija o campo "Até".',
    ],
    [
      '2026-11-30',
      '2026-12-01',
      'pnl-from',
      'O período começa depois de hoje. Escolha um dia inicial até hoje.',
    ],
    ['2025-01-01', '2026-11-01', 'pnl-to', 'Escolha um período de até 366 dias.'],
  ])(
    'refuses %s to %s on the field %s with its message, keeps the draft and the focus goes there',
    async (from, to, field, message) => {
      const { onChange } = renderSelector();
      await userEvent.click(screen.getByRole('radio', { name: 'Intervalo' }));
      typeDay('pnl-from', from);
      typeDay('pnl-to', to);

      await userEvent.click(screen.getByTestId('pnl-apply'));

      expect(screen.getByTestId(`${field}-error`)).toHaveTextContent(message);
      expect(screen.getByTestId(`${field}-error`)).toHaveAttribute('role', 'alert');
      expect(screen.getByTestId(field)).toHaveAttribute('aria-invalid', 'true');
      expect(screen.getByTestId(field)).toHaveFocus();
      expect(screen.getByTestId('pnl-from')).toHaveValue(from);
      expect(screen.getByTestId('pnl-to')).toHaveValue(to);
      expect(onChange).not.toHaveBeenCalled();
    },
  );

  it('the address carries the period and an invalid or future one opens on "Desde o início"', () => {
    const applied = applyPeriod('RANGE', ZONE, NOW(), {
      fromDay: '2026-11-01',
      toDay: '2026-11-10',
    });
    if (!applied.ok) {
      throw new Error('a valid interval');
    }
    expect(periodAddress(applied.period)).toEqual({
      periodo: 'intervalo',
      de: '2026-11-01',
      ate: '2026-11-10',
    });
    expect(periodAddress(SINCE_START)).toEqual({});
    expect(periodFromAddress(new URLSearchParams('periodo=hoje'), ZONE, NOW()).kind).toBe('TODAY');
    expect(periodFromAddress(new URLSearchParams('periodo=7d'), ZONE, NOW()).kind).toBe('LAST_7');
    expect(periodFromAddress(new URLSearchParams('periodo=30d'), ZONE, NOW()).kind).toBe('LAST_30');
    expect(
      periodFromAddress(
        new URLSearchParams('periodo=intervalo&de=2026-11-01&ate=2026-11-10'),
        ZONE,
        NOW(),
      ).utc,
    ).toEqual(applied.period.utc);
    expect(periodFromAddress(new URLSearchParams('periodo=amanha'), ZONE, NOW())).toBe(SINCE_START);
    expect(
      periodFromAddress(
        new URLSearchParams('periodo=intervalo&de=2026-12-01&ate=2026-12-02'),
        ZONE,
        NOW(),
      ),
    ).toBe(SINCE_START);
    expect(periodFromAddress(null, ZONE, NOW())).toBe(SINCE_START);
  });
});
