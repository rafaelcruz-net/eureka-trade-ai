import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ProratedLines, originText } from '@/features/pnl/ProratedLines';
import { REAL_BOT_ID, TWELVE_SHARES } from '@/features/pnl/fixtures';

const ZONE = 'America/Sao_Paulo';

function renderShares(prorated = TWELVE_SHARES) {
  return render(
    <ProratedLines botId={REAL_BOT_ID} botName="Real B" prorated={prorated} timeZone={ZONE} />,
  );
}

/** 261121-paper-real-unico U3, Step 20 (BR4.1, BR4.7, BR2.5, BR10.1, BR11.1; AC3.10.2). */
describe('ProratedLines', () => {
  it('shows nothing for a bot without shares', () => {
    const { container } = renderShares({ count: 0, summary: [], lines: [] });

    expect(container).toBeEmptyDOMElement();
  });

  it('writes one "inclui" line per origin with the value of the API and the origin in text', () => {
    renderShares();

    const summary = screen.getByTestId(`prorated-${REAL_BOT_ID}-summary`);
    expect(
      within(summary)
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual([
      'inclui +0,0004 SOL de liquidação',
      'inclui −0,00012 SOL de venda condicional',
      'inclui +0,00005 SOL de vendido por outro bot',
    ]);
  });

  it('the button opens and closes the list with aria-expanded and aria-controls, named with the bot', async () => {
    renderShares();
    const toggle = screen.getByRole('button', { name: 'Ver 12 linhas rateadas de Real B' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    const list = document.getElementById(toggle.getAttribute('aria-controls') ?? '');
    expect(list).not.toBeNull();
    expect(list).not.toBeVisible();

    await userEvent.click(toggle);

    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(list).toBeVisible();
    expect(screen.getByRole('table', { name: 'Linhas rateadas de Real B' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Linhas rateadas de Real B' })).toHaveAttribute(
      'tabindex',
      '0',
    );
    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
  });

  it('lists the 10 received shares, each value as the fixture written, and links to the bot with more than 10', async () => {
    renderShares();
    await userEvent.click(screen.getByTestId(`prorated-${REAL_BOT_ID}-toggle`));

    const rows = screen.getAllByTestId(new RegExp(`^prorated-${REAL_BOT_ID}-row-\\d+$`));
    expect(rows).toHaveLength(10);
    const first = rows[0] as HTMLElement;
    expect(first).toHaveTextContent('28/10 07:05');
    expect(first).toHaveTextContent('Mint…3333');
    expect(first).toHaveTextContent('liquidação');
    expect(first).toHaveTextContent('0,0003 SOL');
    expect(first).toHaveTextContent('0,0000025 SOL');
    expect(screen.getByTestId(`prorated-${REAL_BOT_ID}-row-realized-0`)).toHaveTextContent(
      '+0,00001 SOL',
    );
    expect(screen.getByTestId(`prorated-${REAL_BOT_ID}-row-realized-9`)).toHaveTextContent(
      '+0,000010009 SOL',
    );
    expect(screen.getAllByTestId(new RegExp(`^prorated-${REAL_BOT_ID}-card-\\d+$`))).toHaveLength(
      10,
    );
    expect(screen.getByTestId(`prorated-${REAL_BOT_ID}-see-all`)).toHaveAttribute(
      'href',
      `/bots/${REAL_BOT_ID}`,
    );
  });

  it('without more than 10 there is no link to the bot, and no sum is ever written', async () => {
    renderShares({ ...TWELVE_SHARES, count: 2, lines: TWELVE_SHARES.lines.slice(0, 2) });
    await userEvent.click(screen.getByRole('button', { name: /Ver 2 linhas rateadas/ }));

    expect(screen.queryByTestId(`prorated-${REAL_BOT_ID}-see-all`)).toBeNull();
    expect(screen.getByTestId(`prorated-${REAL_BOT_ID}`)).not.toHaveTextContent(/total/i);
  });

  it('the four origins in text and an unknown code as it came', () => {
    expect(originText('BOT')).toBe('Bot');
    expect(originText('LIQUIDATION')).toBe('liquidação');
    expect(originText('CONDITIONAL')).toBe('venda condicional');
    expect(originText('SOLD_BY_OTHER_BOT')).toBe('vendido por outro bot');
    expect(originText('NEW_ORIGIN')).toBe('NEW_ORIGIN');
  });
});
