import { render, screen, within } from '@testing-library/react';
import type { PaperModeBlock, RealModeBlock } from '@/lib/api/guest-api';
import { PnlModeBlock, unownedVisible } from '@/features/pnl/PnlModeBlock';
import {
  COMPARISON,
  PAPER_BLOCK,
  PAPER_BOT_ID,
  REAL_BLOCK,
  REAL_BLOCK_PRORATED,
  REAL_BOT_ID,
} from '@/features/pnl/fixtures';

const ZONE = 'America/Sao_Paulo';
const EMPTY_REAL: RealModeBlock = { ...REAL_BLOCK, empty: true, bots: [] };
const EMPTY_PAPER: PaperModeBlock = { ...PAPER_BLOCK, empty: true, bots: [] };

/** 261121-paper-real-unico U3, Step 20 (BR3.4, BR4.7, BR4.8, BR7.1 to BR7.4, BR9.2, BR11.2; AC4.4.x). */
describe('PnlModeBlock compact variant', () => {
  it('writes the total, realized and fees of the PAPER in US$ with the disclaimer and the link to /pnl', () => {
    render(
      <PnlModeBlock
        variant="compact"
        market="PAPER"
        load={{ kind: 'ready', block: PAPER_BLOCK }}
        onRetry={jest.fn()}
      />,
    );

    expect(screen.getByRole('heading', { level: 3 })).toHaveTextContent('Resultado simulado');
    expect(screen.getByTestId('pnl-compact-PAPER-total')).toHaveTextContent('+US$ 12,00');
    expect(screen.getByTestId('pnl-compact-PAPER-realized')).toHaveTextContent('+US$ 12,40');
    expect(screen.getByTestId('pnl-compact-PAPER-fees')).toHaveTextContent('US$ 0,40');
    expect(screen.getByTestId('pnl-compact-PAPER-disclaimer')).toHaveTextContent(
      'O resultado simulado não garante o resultado real.',
    );
    expect(screen.getByLabelText('Total simulado, em dólares')).toHaveAttribute(
      'aria-live',
      'polite',
    );
    expect(screen.getByTestId('pnl-compact-PAPER-open')).toHaveAttribute('href', '/pnl');
    expect(screen.getByTestId('pnl-compact-PAPER')).not.toHaveTextContent('SOL');
  });

  it('writes the REAL in SOL, never with the disclaimer', () => {
    render(
      <PnlModeBlock
        variant="compact"
        market="REAL"
        load={{ kind: 'ready', block: REAL_BLOCK }}
        onRetry={jest.fn()}
      />,
    );

    expect(screen.getByRole('heading', { level: 3 })).toHaveTextContent('Resultado real');
    expect(screen.getByTestId('pnl-compact-REAL-total')).toHaveTextContent('+0,0312 SOL');
    expect(screen.getByLabelText('Total real, em SOL')).toHaveAttribute('aria-atomic', 'true');
    expect(screen.queryByTestId('pnl-compact-REAL-disclaimer')).toBeNull();
    expect(screen.getByTestId('pnl-compact-REAL')).not.toHaveTextContent('US$');
    expect(screen.getByTestId('pnl-compact-REAL-open')).toHaveTextContent('Ver o resultado real');
  });

  it('turns the announcement off when asked (the first load and within 30 s)', () => {
    render(
      <PnlModeBlock
        variant="compact"
        market="REAL"
        load={{ kind: 'ready', block: REAL_BLOCK }}
        onRetry={jest.fn()}
        announce={false}
      />,
    );

    expect(screen.getByTestId('pnl-compact-REAL-total-region')).toHaveAttribute('aria-live', 'off');
  });

  it('has loading, unknown, empty and unavailable states of its own', () => {
    const onRetry = jest.fn();
    const { rerender } = render(
      <PnlModeBlock
        variant="compact"
        market="PAPER"
        load={{ kind: 'loading' }}
        onRetry={onRetry}
      />,
    );
    expect(screen.getByTestId('pnl-compact-PAPER-loading')).toHaveAttribute('role', 'status');
    expect(screen.queryByTestId('pnl-compact-PAPER-disclaimer')).toBeNull();

    rerender(
      <PnlModeBlock
        variant="compact"
        market="PAPER"
        load={{
          kind: 'ready',
          block: { ...PAPER_BLOCK, total: { status: 'UNKNOWN', reason: 'PRICE_UNKNOWN' } },
        }}
        onRetry={onRetry}
      />,
    );
    expect(screen.getByTestId('pnl-compact-PAPER-total')).toHaveTextContent(
      'desconhecido (depende do não realizado)',
    );
    expect(screen.getByTestId('pnl-compact-PAPER-realized')).toHaveTextContent('+US$ 12,40');

    rerender(
      <PnlModeBlock
        variant="compact"
        market="PAPER"
        load={{ kind: 'ready', block: EMPTY_PAPER }}
        onRetry={onRetry}
      />,
    );
    expect(screen.getByTestId('pnl-compact-PAPER-empty')).toHaveTextContent('Sem bots simulados.');
    expect(screen.queryByTestId('pnl-compact-PAPER-disclaimer')).toBeNull();

    rerender(
      <PnlModeBlock
        variant="compact"
        market="PAPER"
        load={{ kind: 'error', code: 'corr-7' }}
        onRetry={onRetry}
      />,
    );
    expect(screen.getByTestId('pnl-compact-PAPER-error')).toHaveTextContent(
      'Não foi possível carregar o resultado simulado. Os outros quadros continuam funcionando.',
    );
    expect(screen.getByTestId('pnl-compact-PAPER-code')).toHaveTextContent('corr-7');
    screen.getByTestId('pnl-compact-PAPER-retry').click();
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it.each([
    [{ kind: 'available' as const }, 'pnl-compact-REAL-empty-create', '/bots/new'],
    [
      { kind: 'reason' as const, reason: 'WALLET_INACTIVE' as const },
      'pnl-compact-REAL-empty-activate',
      '/real',
    ],
  ])('the empty REAL with %o links to its reason', (reason, link, href) => {
    render(
      <PnlModeBlock
        variant="compact"
        market="REAL"
        load={{ kind: 'ready', block: EMPTY_REAL }}
        onRetry={jest.fn()}
        realUnavailable={reason}
      />,
    );

    expect(screen.getByTestId('pnl-compact-REAL-empty')).toHaveTextContent('Sem bots reais.');
    expect(screen.getByTestId(link)).toHaveAttribute('href', href);
  });

  it('the empty REAL with another reason writes it without a link, and without C2 shows no link', () => {
    const { rerender } = render(
      <PnlModeBlock
        variant="compact"
        market="REAL"
        load={{ kind: 'ready', block: EMPTY_REAL }}
        onRetry={jest.fn()}
        realUnavailable={{ kind: 'reason', reason: 'KILL_SWITCH' }}
      />,
    );
    expect(screen.getByTestId('pnl-compact-REAL-empty-reason')).toHaveTextContent(
      'As operações reais estão suspensas pelo sistema no momento.',
    );
    expect(within(screen.getByTestId('pnl-compact-REAL-empty')).queryByRole('link')).toBeNull();

    rerender(
      <PnlModeBlock
        variant="compact"
        market="REAL"
        load={{ kind: 'ready', block: EMPTY_REAL }}
        onRetry={jest.fn()}
        realUnavailable={{ kind: 'unknown' }}
      />,
    );
    expect(within(screen.getByTestId('pnl-compact-REAL-empty')).queryByRole('link')).toBeNull();
  });
});

describe('PnlModeBlock bot variant', () => {
  it('writes the line of one PAPER bot with "Total do bot, em dólares" and the disclaimer it is given', () => {
    render(
      <PnlModeBlock
        variant="bot"
        market="PAPER"
        heading="P&L do bot"
        load={{
          kind: 'ready',
          block:
            COMPARISON.paper.status === 'AVAILABLE' ? COMPARISON.paper.line : PAPER_BLOCK.bots[0]!,
        }}
        disclaimer="O resultado simulado não garante o resultado real."
      />,
    );

    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('P&L do bot');
    expect(screen.getByLabelText('Total do bot, em dólares')).toHaveAttribute(
      'aria-live',
      'polite',
    );
    expect(screen.getByTestId('pnl-bot-PAPER-total')).toHaveTextContent('+US$ 12,00');
    expect(screen.getByTestId('pnl-bot-PAPER-unrealized')).toHaveTextContent('US$ 0,00');
    expect(screen.getByTestId('pnl-bot-PAPER-disclaimer')).toBeInTheDocument();
  });

  it('writes the line of one REAL bot in SOL with its own label and heading level', () => {
    render(
      <PnlModeBlock
        variant="bot"
        market="REAL"
        heading="Real B, real, em SOL"
        headingLevel={3}
        totalLabel="Total do bot real, em SOL"
        load={{ kind: 'ready', block: REAL_BLOCK.bots[0]! }}
      />,
    );

    expect(screen.getByRole('heading', { level: 3 })).toHaveTextContent('Real B, real, em SOL');
    expect(screen.getByLabelText('Total do bot real, em SOL')).toBeInTheDocument();
    expect(screen.getByTestId('pnl-bot-REAL-total')).toHaveTextContent('+0,0312 SOL');
    expect(screen.getByTestId('pnl-bot-REAL-fees')).toHaveTextContent('0,00001 SOL');
    expect(screen.queryByTestId('pnl-bot-REAL-disclaimer')).toBeNull();
  });

  it('a bot without a line shows "—", and an error offers a retry', () => {
    const onRetry = jest.fn();
    const { rerender } = render(
      <PnlModeBlock
        variant="bot"
        market="REAL"
        heading="P&L do bot"
        load={{ kind: 'none' }}
        onRetry={onRetry}
      />,
    );
    expect(screen.getByTestId('pnl-bot-REAL-none')).toHaveTextContent('—');

    rerender(
      <PnlModeBlock
        variant="bot"
        market="REAL"
        heading="P&L do bot"
        load={{ kind: 'error', code: 'corr-3' }}
        onRetry={onRetry}
      />,
    );
    expect(screen.getByTestId('pnl-bot-REAL-code')).toHaveTextContent('corr-3');
    screen.getByTestId('pnl-bot-REAL-retry').click();
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});

describe('PnlModeBlock REAL full: prorated shares and the line "Sem bot"', () => {
  it('shows the shares of each bot and the line "Sem bot" with its three reasons, values as they came', () => {
    render(
      <PnlModeBlock
        market="REAL"
        load={{ kind: 'ready', block: REAL_BLOCK_PRORATED }}
        onRetry={jest.fn()}
        timeZone={ZONE}
      />,
    );

    expect(screen.getByTestId(`prorated-${REAL_BOT_ID}`)).toBeInTheDocument();
    const noBot = screen.getByTestId('pnl-block-REAL-nobot');
    expect(noBot).toHaveTextContent('Sem bot');
    expect(screen.getByTestId('pnl-block-REAL-nobot-realized')).toHaveTextContent('+0,025 SOL');
    expect(screen.getByTestId('pnl-block-REAL-nobot-fees')).toHaveTextContent('0,000001 SOL');
    expect(
      within(screen.getByTestId('pnl-block-REAL-nobot-reasons'))
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual([
      'Motivo: excedente sem dono',
      'Motivo: venda sem custo registrado',
      'Motivo: registro da ordem ausente',
    ]);
    expect(within(noBot).queryByRole('link')).toBeNull();
    expect(screen.getByTestId('pnl-block-REAL')).not.toHaveTextContent('US$');
  });

  it('the line "Sem bot" is absent when zero, and the total is never re-computed', () => {
    render(
      <PnlModeBlock
        market="REAL"
        load={{ kind: 'ready', block: REAL_BLOCK }}
        onRetry={jest.fn()}
        timeZone={ZONE}
      />,
    );

    expect(screen.queryByTestId('pnl-block-REAL-nobot')).toBeNull();
    expect(screen.queryByTestId(`prorated-${REAL_BOT_ID}`)).toBeNull();
    expect(screen.getByTestId('pnl-block-REAL-total')).toHaveTextContent('+0,0312 SOL');
  });

  it('decides the line "Sem bot" by comparing the texts with zero', () => {
    const zero = REAL_BLOCK.unowned;
    expect(unownedVisible(zero)).toBe(false);
    expect(unownedVisible(null)).toBe(false);
    expect(unownedVisible(zero && { ...zero, feesLamports: '1' })).toBe(true);
    expect(
      unownedVisible(
        zero && { ...zero, unrealizedLamports: { status: 'UNKNOWN', reason: 'PRICE_UNKNOWN' } },
      ),
    ).toBe(true);
    expect(unownedVisible(zero && { ...zero, reasons: ['UNOWNED_EXCESS'] })).toBe(false);
  });

  it('the PAPER full block takes the table by token as its content and keeps the heading level asked', () => {
    render(
      <PnlModeBlock
        market="PAPER"
        load={{ kind: 'ready', block: PAPER_BLOCK }}
        onRetry={jest.fn()}
        headingLevel={3}
      >
        <p data-testid="inside">dentro do bloco</p>
      </PnlModeBlock>,
    );

    expect(screen.getByRole('heading', { level: 3 })).toHaveTextContent('Simulado, em dólares');
    expect(within(screen.getByTestId('pnl-block-PAPER')).getByTestId('inside')).toBeInTheDocument();
    expect(screen.getByTestId(`pnl-block-PAPER-bot-${PAPER_BOT_ID}`)).toBeInTheDocument();
  });
});
