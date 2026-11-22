import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PnlModeBlock } from '@/features/pnl/PnlModeBlock';
import { PAPER_BLOCK, PAPER_BOT_ID, REAL_BLOCK, REAL_BOT_ID } from '@/features/pnl/fixtures';
import { paperLoad, realLoad } from '@/features/pnl/by-mode';

const ZONE = 'America/Sao_Paulo';

/**
 * 261121-paper-real-unico U1, Step 19 (C17; BR5.1 to BR5.5, BR6.1, BR6.2, BR7.1, BR7.2, BR9.1, BR9.2; NFR6.2): one
 * block of one mode, with five distinct states, never a sum of two modes.
 */
describe('PnlModeBlock', () => {
  it('loading shows the title and the badge and never a zero', () => {
    render(
      <PnlModeBlock
        market="PAPER"
        load={{ kind: 'loading' }}
        onRetry={jest.fn()}
        timeZone={ZONE}
      />,
    );

    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Simulado, em dólares');
    expect(screen.getByTestId('pnl-block-PAPER-badge')).toHaveTextContent('SIMULADO');
    expect(screen.getByTestId('pnl-block-PAPER-loading')).toHaveTextContent(
      'Calculando o resultado simulado…',
    );
    expect(screen.getByTestId('pnl-block-PAPER')).not.toHaveTextContent('0,00');
  });

  it('the PAPER block shows its total in US$, its parts, its bots and the fixed disclaimer', () => {
    render(
      <PnlModeBlock
        market="PAPER"
        load={{ kind: 'ready', block: PAPER_BLOCK }}
        onRetry={jest.fn()}
        timeZone={ZONE}
      />,
    );

    expect(screen.getByTestId('pnl-block-PAPER-total')).toHaveTextContent('+US$ 12,00 (ganho)');
    expect(screen.getByTestId('pnl-block-PAPER-realized')).toHaveTextContent('+US$ 12,40');
    expect(screen.getByTestId('pnl-block-PAPER-fees')).toHaveTextContent('US$ 0,40');
    expect(screen.getByTestId(`pnl-block-PAPER-bot-${PAPER_BOT_ID}`)).toHaveTextContent('Ativo');
    expect(screen.getByTestId('pnl-block-PAPER-disclaimer')).toHaveTextContent(
      'O resultado simulado não garante o resultado real.',
    );
    expect(screen.getByLabelText('Total simulado, em dólares')).toBeInTheDocument();
    expect(screen.getByTestId('pnl-block-PAPER')).not.toHaveTextContent('SOL');
  });

  it('each bots table scrolls inside its own named, focusable region, never the page (NFR6.1)', () => {
    for (const [market, block] of [
      ['PAPER', PAPER_BLOCK],
      ['REAL', REAL_BLOCK],
    ] as const) {
      const { unmount } = render(
        market === 'PAPER' ? (
          <PnlModeBlock
            market="PAPER"
            load={{ kind: 'ready', block: block as typeof PAPER_BLOCK }}
            onRetry={jest.fn()}
            timeZone={ZONE}
          />
        ) : (
          <PnlModeBlock
            market="REAL"
            load={{ kind: 'ready', block: block as typeof REAL_BLOCK }}
            onRetry={jest.fn()}
            timeZone={ZONE}
          />
        ),
      );
      const table = screen.getByTestId(`pnl-block-${market}-bots`);
      const region = table.closest('.table-scroll');
      expect(region).not.toBeNull();
      expect(region).toHaveAttribute('role', 'region');
      expect(region).toHaveAttribute('tabindex', '0');
      expect(screen.getByRole('region', { name: /^Bots do modo: / })).toBe(region);
      unmount();
    }
  });

  it('the REAL block shows SOL, the fees already included and the fixed note of the U1, and no disclaimer', () => {
    render(
      <PnlModeBlock
        market="REAL"
        load={{ kind: 'ready', block: REAL_BLOCK }}
        onRetry={jest.fn()}
        timeZone={ZONE}
      />,
    );

    expect(screen.getByTestId('pnl-block-REAL-total')).toHaveTextContent('+0,0312 SOL (ganho)');
    expect(screen.getByTestId('pnl-block-REAL-realized')).toHaveTextContent(
      '−0,000005 SOL (perda)',
    );
    expect(screen.getByText('Taxas (já incluídas no realizado e no custo)')).toBeInTheDocument();
    expect(screen.getByTestId('pnl-block-REAL-note')).toHaveTextContent(
      'Algumas operações ainda não têm bot dono; o rateio chega na próxima etapa.',
    );
    expect(screen.getByTestId(`pnl-block-REAL-bot-${REAL_BOT_ID}`)).toHaveTextContent('Pausado');
    expect(screen.queryByTestId('pnl-block-REAL-disclaimer')).toBeNull();
    expect(screen.getByTestId('pnl-block-REAL')).not.toHaveTextContent('US$');
  });

  it('an unknown unrealized makes the total unknown, never zero, with the partial notice', () => {
    render(
      <PnlModeBlock
        market="REAL"
        load={{
          kind: 'ready',
          block: {
            ...REAL_BLOCK,
            totalLamports: { status: 'UNKNOWN', reason: 'PRICE_UNKNOWN' },
            unrealizedLamports: { status: 'UNKNOWN', reason: 'PRICE_UNKNOWN' },
          },
        }}
        onRetry={jest.fn()}
        timeZone={ZONE}
      />,
    );

    expect(screen.getByTestId('pnl-block-REAL-total')).toHaveTextContent(
      'desconhecido (depende do não realizado)',
    );
    expect(screen.getByTestId('pnl-block-REAL-unrealized')).toHaveTextContent(
      'desconhecido (sem preço)',
    );
    expect(screen.getByTestId('pnl-block-REAL-partial')).toHaveTextContent('Dado parcial');
    expect(screen.getByTestId('pnl-block-REAL-realized')).toHaveTextContent('SOL');
  });

  it('an empty block says so: PAPER offers a simulated bot, REAL the bot wallet, and PAPER hides the disclaimer', () => {
    render(
      <>
        <PnlModeBlock
          market="PAPER"
          load={{ kind: 'ready', block: { ...PAPER_BLOCK, empty: true } }}
          onRetry={jest.fn()}
        />
        <PnlModeBlock
          market="REAL"
          load={{ kind: 'ready', block: { ...REAL_BLOCK, empty: true } }}
          onRetry={jest.fn()}
        />
      </>,
    );

    expect(screen.getByTestId('pnl-block-PAPER-empty')).toHaveTextContent(
      'Nenhuma operação simulada neste período.',
    );
    expect(screen.getByTestId('pnl-block-REAL-empty')).toHaveTextContent('Sem bots reais.');
    expect(screen.queryByTestId('pnl-block-PAPER-disclaimer')).toBeNull();
    expect(screen.queryByTestId('pnl-block-REAL-note')).toBeNull();
  });

  it('an UNAVAILABLE block is an error with the code and a retry operable by keyboard', async () => {
    const onRetry = jest.fn();
    render(
      <PnlModeBlock
        market="REAL"
        load={realLoad({
          status: 'UNAVAILABLE',
          market: 'REAL',
          currency: 'SOL',
          code: 'SERVICE_UNAVAILABLE',
          correlationId: 'corr-42',
        })}
        onRetry={onRetry}
      />,
    );

    expect(screen.getByTestId('pnl-block-REAL-error')).toHaveTextContent(
      'Não foi possível carregar o resultado real.',
    );
    expect(screen.getByTestId('pnl-block-REAL-code')).toHaveTextContent('corr-42');
    screen.getByTestId('pnl-block-REAL-retry').focus();
    await userEvent.keyboard('{Enter}');
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('turns an available result into a ready load and keeps the heading focusable on request', () => {
    expect(paperLoad(PAPER_BLOCK)).toEqual({ kind: 'ready', block: PAPER_BLOCK });
    render(
      <PnlModeBlock
        market="PAPER"
        load={paperLoad(PAPER_BLOCK)}
        onRetry={jest.fn()}
        focusHeading
      />,
    );

    expect(screen.getByRole('heading', { level: 2 })).toHaveFocus();
  });

  it('no text promises a profit (BR6.2)', () => {
    render(
      <>
        <PnlModeBlock
          market="PAPER"
          load={{ kind: 'ready', block: PAPER_BLOCK }}
          onRetry={jest.fn()}
        />
        <PnlModeBlock
          market="REAL"
          load={{ kind: 'ready', block: REAL_BLOCK }}
          onRetry={jest.fn()}
        />
      </>,
    );

    expect(document.body).not.toHaveTextContent(/lucro garantido|ganho garantido|garantimos/i);
  });
});
