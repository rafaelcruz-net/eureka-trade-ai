import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import { messages } from '@/lib/i18n/messages';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { BotTradesTable } from '@/features/pnl/BotTradesTable';
import { LedgerList } from '@/features/pnl/LedgerList';
import { BOT_ID, ENTRIES, NOT_FILLED, ORDER_ID, TRADE } from '@/features/pnl/fixtures';

const text = messages.pnl;

function renderWith(api: ReturnType<typeof fakeApi>, node: React.ReactNode) {
  render(<Providers api={api}>{node}</Providers>);
  return api;
}

describe('PAPER ledger', () => {
  it('groups the entries by operation with type, signed amount, time and policy; no edit action', async () => {
    renderWith(
      fakeApi({
        listLedger: jest.fn(async () => ({ items: ENTRIES, nextCursor: null, total: 3 })),
      }),
      <LedgerList timeZone="UTC" />,
    );

    const group = await screen.findByTestId(`ledger-group-${ORDER_ID}`);
    expect(within(group).getAllByRole('listitem')).toHaveLength(2);
    expect(group).toHaveTextContent(text.policy('v1'));
    expect(group).toHaveTextContent('Taxa −0,301800 (perda) USDC');
    expect(screen.getByTestId('ledger')).toHaveTextContent(text.paperLabel);
    expect(screen.getByText(text.noOperation)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /editar|apagar|excluir|corrigir/i })).toBeNull();
  });

  it('"ver hipóteses" shows every assumption of the fill', async () => {
    const api = renderWith(
      fakeApi({
        listLedger: jest.fn(async () => ({ items: ENTRIES, nextCursor: null, total: 3 })),
        listBotTrades: jest.fn(async () => ({ items: [TRADE], nextCursor: null, total: 1 })),
      }),
      <LedgerList timeZone="UTC" />,
    );

    await userEvent.click(await screen.findByTestId(`ledger-hypotheses-${ORDER_ID}`));

    const hypotheses = await screen.findByTestId(`hypotheses-${ORDER_ID}`);
    expect(api.listBotTrades).toHaveBeenCalledWith(BOT_ID, { limit: 100 });
    expect(hypotheses).toHaveTextContent('1,000000000000');
    expect(hypotheses).toHaveTextContent('1,005000000000');
    expect(hypotheses).toHaveTextContent('1,006000000000');
    expect(hypotheses).toHaveTextContent('0,5000');
    expect(hypotheses).toHaveTextContent('sim-a, 28/10/2026, 12:00:00');
    expect(screen.getByTestId(`ledger-hypotheses-${ORDER_ID}`)).toHaveAttribute(
      'aria-expanded',
      'true',
    );
    await userEvent.click(screen.getByTestId(`ledger-hypotheses-${ORDER_ID}`));
    expect(screen.queryByTestId(`hypotheses-${ORDER_ID}`)).toBeNull();
  });

  it('hypotheses that cannot be loaded are explained', async () => {
    renderWith(
      fakeApi({
        listLedger: jest.fn(async () => ({ items: ENTRIES, nextCursor: null, total: 3 })),
        listBotTrades: jest.fn(async () =>
          Promise.reject(new ApiError(503, 'SERVICE_UNAVAILABLE')),
        ),
      }),
      <LedgerList />,
    );

    await userEvent.click(await screen.findByTestId(`ledger-hypotheses-${ORDER_ID}`));

    expect(await screen.findByText(text.hypothesesUnavailable)).toBeInTheDocument();
  });

  it('loading, empty, error and the next page are distinct states', async () => {
    const listLedger = jest
      .fn()
      .mockResolvedValueOnce({ items: ENTRIES.slice(0, 1), nextCursor: 'next', total: 3 })
      .mockResolvedValueOnce({ items: ENTRIES.slice(1), nextCursor: null, total: 3 });
    const api = renderWith(fakeApi({ listLedger }), <LedgerList botId={BOT_ID} />);

    expect(screen.getByTestId('ledger-loading')).toHaveTextContent(text.ledgerLoading);
    await userEvent.click(await screen.findByTestId('ledger-more'));
    expect(api.listLedger).toHaveBeenLastCalledWith({ botId: BOT_ID, cursor: 'next', limit: 50 });
    expect(await screen.findByText(text.noOperation)).toBeInTheDocument();
    expect(screen.queryByTestId('ledger-more')).toBeNull();
  });

  it('an empty ledger and a failure', async () => {
    renderWith(fakeApi(), <LedgerList />);
    expect(await screen.findByTestId('ledger-empty')).toHaveTextContent(text.ledgerEmpty);
  });

  it('a failing ledger shows its error', async () => {
    renderWith(
      fakeApi({
        listLedger: jest.fn(async () => Promise.reject(new ApiError(503, 'SERVICE_UNAVAILABLE'))),
      }),
      <LedgerList />,
    );
    expect(await screen.findByTestId('ledger-error')).toHaveTextContent(text.ledgerError);
  });
});

describe('operations of the bot (component of the bot screen of U5)', () => {
  it('lists each operation once with requested and filled quantity, prices and versions', async () => {
    renderWith(
      fakeApi({
        listBotTrades: jest.fn(async () => ({
          items: [TRADE, NOT_FILLED],
          nextCursor: null,
          total: 2,
        })),
      }),
      <BotTradesTable botId={BOT_ID} timeZone="UTC" />,
    );

    const filled = await screen.findByTestId(`bot-trade-${TRADE.orderId}`);
    expect(filled).toHaveTextContent('Compra');
    expect(filled).toHaveTextContent('1,006000000000');
    expect(filled).toHaveTextContent(text.versions('v1', 3));
    const refused = screen.getByTestId(`bot-trade-${NOT_FILLED.orderId}`);
    expect(refused).toHaveTextContent('Não preenchida (saldo virtual insuficiente)');
    expect(screen.getByTestId('bot-trades')).toHaveTextContent(text.paperLabel);
  });

  it('loading, empty and error states', async () => {
    renderWith(fakeApi(), <BotTradesTable botId={BOT_ID} />);
    expect(screen.getByTestId('bot-trades-loading')).toBeInTheDocument();
    expect(await screen.findByTestId('bot-trades-empty')).toHaveTextContent(text.tradesEmpty);
  });

  it('a failure shows the error', async () => {
    renderWith(
      fakeApi({
        listBotTrades: jest.fn(async () => Promise.reject(new ApiError(404, 'NOT_FOUND'))),
      }),
      <BotTradesTable botId={BOT_ID} />,
    );
    expect(await screen.findByTestId('bot-trades-error')).toHaveTextContent(text.tradesError);
  });

  it('no text of the U7 screens promises results', () => {
    const all = JSON.stringify(text).toLowerCase();
    expect(all).not.toMatch(/lucro garantido|sem risco|100% seguro|detecta golpes/);
  });
});
