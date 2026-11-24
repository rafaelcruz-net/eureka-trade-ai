import { configure, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import { tokenStore } from '@/lib/auth/token-store';
import type { GuestApi } from '@/lib/api/guest-api';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { BotPnlLines } from '@/features/pnl/BotPnlLines';
import { LINES_CURSOR, LINES_PAGE_1, LINES_PAGE_2, REAL_BOT_ID } from '@/features/pnl/fixtures';

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({}),
}));

const ZONE = 'America/Sao_Paulo';

// A page of C5 renders 50 lines twice (table and cards); with the whole suite in parallel the default wait of 1 s was
// once too short for "Carregar mais" (an unstable test is a defect, team.md). This file waits up to 5 s.
configure({ asyncUtilTimeout: 5_000 });
// The cases with two pages render 53 lines twice and click twice; they get their own budget, as the wizard flow of the
// bots does (BotScreens.test.tsx). Jest's global 5 s default is unchanged.
jest.setTimeout(20_000);

function renderLines(overrides: Partial<GuestApi>, onNotFound = jest.fn()) {
  tokenStore.set('access-token-value');
  const api = fakeApi(overrides);
  render(
    <Providers api={api}>
      <BotPnlLines botId={REAL_BOT_ID} botName="Real B" timeZone={ZONE} onNotFound={onNotFound} />
    </Providers>,
  );
  return { api, onNotFound };
}

/** 261121-paper-real-unico U3, Step 21 (BR4.1 to BR4.6, BR2.5, BR10.1, BR10.2; AC3.10.1 to AC3.10.4, A9). */
describe('BotPnlLines', () => {
  it('reads 50 lines without a cursor and shows them in the order received, with six columns', async () => {
    const { api } = renderLines({ listBotPnlLines: jest.fn(async () => LINES_PAGE_1) });

    expect(screen.getByTestId('bot-lines-loading')).toHaveAttribute('role', 'status');
    const table = await screen.findByRole('table', { name: 'Linhas do P&L de Real B' });
    expect(api.listBotPnlLines).toHaveBeenCalledWith(
      REAL_BOT_ID,
      { cursor: null, limit: 50 },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((cell) => cell.textContent),
    ).toEqual(['Data (seu fuso)', 'Lado', 'Token', 'Resultado (SOL)', 'Taxa (SOL)', 'Origem']);
    const rows = screen.getAllByTestId(/^bot-lines-row-\d+$/);
    expect(rows).toHaveLength(50);
    expect(rows[0]).toHaveTextContent('28/10 11:00');
    expect(rows[1]).toHaveTextContent('28/10 10:01');
    expect(screen.getByRole('region', { name: 'Linhas do P&L de Real B' })).toHaveAttribute(
      'tabindex',
      '0',
    );
  });

  it('a buy writes "—" read as "sem resultado"; a sale writes its result; the origin is in text', async () => {
    renderLines({ listBotPnlLines: jest.fn(async () => LINES_PAGE_1) });

    const buy = await screen.findByTestId('bot-lines-result-0');
    expect(buy).toHaveTextContent('—');
    expect(within(buy).getByRole('img', { name: 'sem resultado' })).toBeInTheDocument();
    expect(screen.getByTestId('bot-lines-result-1')).toHaveTextContent('+0,000020001 SOL');
    expect(screen.getByTestId('bot-lines-origin-0')).toHaveTextContent('Bot');
    expect(screen.getByTestId('bot-lines-row-0')).toHaveTextContent('Compra');
    expect(screen.getByTestId('bot-lines-card-0')).toHaveTextContent('Compra');
  });

  it('"Carregar mais" sends the cursor, appends the lines and puts the focus on the first new one', async () => {
    const listBotPnlLines = jest
      .fn()
      .mockResolvedValueOnce(LINES_PAGE_1)
      .mockResolvedValueOnce(LINES_PAGE_2);
    renderLines({ listBotPnlLines });

    await userEvent.click(await screen.findByTestId('bot-lines-more'));

    expect(await screen.findByTestId('bot-lines-row-52')).toBeInTheDocument();
    expect(listBotPnlLines).toHaveBeenLastCalledWith(
      REAL_BOT_ID,
      { cursor: LINES_CURSOR, limit: 50 },
      expect.anything(),
    );
    expect(screen.getByTestId('bot-lines-row-50')).toHaveFocus();
    expect(screen.getByTestId('bot-lines-origin-50')).toHaveTextContent('liquidação');
    expect(screen.queryByTestId('bot-lines-more')).toBeNull();
  });

  it('a failure of the next page keeps the 50 lines and retries once with the same cursor', async () => {
    const listBotPnlLines = jest
      .fn()
      .mockResolvedValueOnce(LINES_PAGE_1)
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE', undefined, 'corr-5'))
      .mockResolvedValueOnce(LINES_PAGE_2);
    renderLines({ listBotPnlLines });
    await userEvent.click(await screen.findByTestId('bot-lines-more'));

    expect(await screen.findByTestId('bot-lines-more-error')).toHaveTextContent('corr-5');
    expect(screen.getAllByTestId(/^bot-lines-row-\d+$/)).toHaveLength(50);
    await userEvent.click(screen.getByTestId('bot-lines-more-retry'));

    expect(await screen.findByTestId('bot-lines-row-52')).toBeInTheDocument();
    expect(listBotPnlLines).toHaveBeenCalledTimes(3);
    expect(listBotPnlLines.mock.calls[2]?.[1]).toEqual({ cursor: LINES_CURSOR, limit: 50 });
  });

  it('a stale cursor offers "Recarregar do início", which reads without a cursor and never repeats the refused one', async () => {
    const listBotPnlLines = jest
      .fn()
      .mockResolvedValueOnce(LINES_PAGE_1)
      .mockRejectedValueOnce(
        new ApiError(400, 'VALIDATION_FAILED', undefined, 'corr-6', [
          { field: 'cursor', message: 'invalid' },
        ]),
      )
      .mockResolvedValueOnce(LINES_PAGE_2);
    renderLines({ listBotPnlLines });
    await userEvent.click(await screen.findByTestId('bot-lines-more'));

    expect(await screen.findByTestId('bot-lines-stale')).toHaveTextContent(
      'A lista mudou desde a última página.',
    );
    const reload = screen.getByTestId('bot-lines-reload');
    expect(reload).toHaveTextContent('Recarregar do início');
    await userEvent.click(reload);

    await waitFor(() => expect(screen.getAllByTestId(/^bot-lines-row-\d+$/)).toHaveLength(3));
    expect(listBotPnlLines).toHaveBeenCalledTimes(3);
    expect(listBotPnlLines.mock.calls[2]?.[1]).toEqual({ cursor: null, limit: 50 });
  });

  it('the four states are distinct: empty, error with code and retry, and 404 to onNotFound', async () => {
    renderLines({
      listBotPnlLines: jest.fn(async () => ({
        currency: 'SOL' as const,
        items: [],
        nextCursor: null,
      })),
    });
    expect(await screen.findByTestId('bot-lines-empty')).toHaveTextContent(
      'Nenhuma operação real até agora.',
    );
  });

  it('an error of the first page has the code and "Tentar de novo"', async () => {
    const listBotPnlLines = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE', undefined, 'corr-8'))
      .mockResolvedValueOnce(LINES_PAGE_2);
    renderLines({ listBotPnlLines });

    expect(await screen.findByTestId('bot-lines-code')).toHaveTextContent('corr-8');
    expect(screen.queryByTestId('bot-lines-table')).toBeNull();
    await userEvent.click(screen.getByTestId('bot-lines-retry'));
    expect(await screen.findByTestId('bot-lines-row-2')).toBeInTheDocument();
  });

  it('a 404 hands over to onNotFound and shows no line', async () => {
    const { onNotFound } = renderLines({
      listBotPnlLines: jest.fn(async () => {
        throw new ApiError(404, 'NOT_FOUND');
      }),
    });

    await waitFor(() => expect(onNotFound).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('bot-lines-table')).toBeNull();
  });

  it('never writes a total of the lines', async () => {
    renderLines({ listBotPnlLines: jest.fn(async () => LINES_PAGE_1) });

    await screen.findByTestId('bot-lines-table');
    expect(screen.getByTestId('bot-lines')).not.toHaveTextContent(/total/i);
  });
});
