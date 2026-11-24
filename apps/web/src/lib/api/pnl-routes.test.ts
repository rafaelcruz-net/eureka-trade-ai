/**
 * @jest-environment node
 */
import { createApiClient } from '@/lib/api/client';
import { guestApi } from '@/lib/api/guest-api';

describe('guest api calls of U7 (C5)', () => {
  it('maps every ledger, P&L and reconciliation call to its route, method and headers', async () => {
    const calls: Array<[string, RequestInit]> = [];
    const fetchImpl = jest.fn(async (url: string, init: RequestInit) => {
      calls.push([url, init]);
      return { ok: true, status: 200, json: async () => ({}) } as unknown as Response;
    });
    const api = guestApi(
      createApiClient({ baseUrl: 'http://api', fetchImpl: fetchImpl as unknown as typeof fetch }),
    );

    await api.getPnl({ from: '2026-10-22T03:00:00Z', to: '2026-10-29T03:00:00Z' });
    await api.getPnl({ from: 'a', to: 'b', botId: 'bot-1' });
    await api.listLedger();
    await api.listLedger({ botId: 'bot-1', cursor: 'c', limit: 50 });
    await api.listBotTrades('bot-1', { limit: 100 });
    await api.getDayUsage('bot-1');
    await api.getReconciliation();
    await api.getReconciliation('bot-1');
    await api.runReconciliation();

    expect(calls.map(([url, init]) => `${init.method} ${url}`)).toEqual([
      'GET http://api/api/v1/pnl?from=2026-10-22T03%3A00%3A00Z&to=2026-10-29T03%3A00%3A00Z',
      'GET http://api/api/v1/pnl?from=a&to=b&botId=bot-1',
      'GET http://api/api/v1/ledger',
      'GET http://api/api/v1/ledger?botId=bot-1&cursor=c&limit=50',
      'GET http://api/api/v1/bots/bot-1/trades?limit=100',
      'GET http://api/api/v1/bots/bot-1/day-usage',
      'GET http://api/api/v1/reconciliation',
      'GET http://api/api/v1/reconciliation?botId=bot-1',
      'POST http://api/api/v1/reconciliation/run',
    ]);
    const headers = (i: number) => calls[i]?.[1].headers as Record<string, string>;
    expect(headers(8)['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(headers(0)['Idempotency-Key']).toBeUndefined();
    expect(calls.every(([, init]) => init.credentials === 'omit')).toBe(true);
  });
});

/** 261121-paper-real-unico U3, Etapa 16 (BR1.2, BR2.4, BR3.1, BR8.1, NFR3.1): the routes of the screens of the P&L. */
describe('guest api calls of 261121-paper-real-unico U3 (C4, C5, C6, listBots)', () => {
  it('maps C4 with and without market, ALL and RANGE in UTC, listBots, C5 and C6, never a tenant', async () => {
    const calls: string[] = [];
    const fetchImpl = jest.fn(async (url: string) => {
      calls.push(url);
      return { ok: true, status: 200, json: async () => ({}) } as unknown as Response;
    });
    const api = guestApi(
      createApiClient({ baseUrl: 'http://api', fetchImpl: fetchImpl as unknown as typeof fetch }),
    );
    const range = {
      kind: 'RANGE' as const,
      from: '2026-11-17T03:00:00Z',
      to: '2026-11-24T03:00:00Z',
    };

    await api.getPnlByMode();
    await api.getPnlByMode({ market: 'REAL' });
    await api.getPnlByMode({ period: range });
    await api.listBots({ market: 'PAPER', state: 'ACTIVE' });
    await api.listBotPnlLines('bot-1', { limit: 50 });
    await api.listBotPnlLines('bot-1', { cursor: 'AQ_c-1', limit: 50 });
    await api.comparePnl({ paperBotId: 'p-1', realBotId: 'r-1' });
    await api.comparePnl({ paperBotId: 'p-1', realBotId: 'r-1', period: range });

    expect(calls).toEqual([
      'http://api/api/v1/pnl/by-mode?period=ALL',
      'http://api/api/v1/pnl/by-mode?period=ALL&market=REAL',
      'http://api/api/v1/pnl/by-mode?period=RANGE&from=2026-11-17T03%3A00%3A00Z&to=2026-11-24T03%3A00%3A00Z',
      'http://api/api/v1/bots?state=ACTIVE&market=PAPER',
      'http://api/api/v1/bots/bot-1/pnl/lines?limit=50',
      'http://api/api/v1/bots/bot-1/pnl/lines?cursor=AQ_c-1&limit=50',
      'http://api/api/v1/pnl/compare?paperBotId=p-1&realBotId=r-1&period=ALL',
      'http://api/api/v1/pnl/compare?paperBotId=p-1&realBotId=r-1&period=RANGE&from=2026-11-17T03%3A00%3A00Z&to=2026-11-24T03%3A00%3A00Z',
    ]);
    expect(calls.join(' ')).not.toMatch(/tenantId|accountId|userId/);
  });

  // Review finding 2: C6 runs two REAL passes, so the client never repeats it; C4 (one block) still does.
  it('C6 makes exactly one fetch on a 503 and fails with its correlation id; C4 is still repeated', async () => {
    const unavailable = {
      ok: false,
      status: 503,
      headers: new Headers(),
      json: async () => ({ code: 'SERVICE_UNAVAILABLE', correlationId: 'corr-c6' }),
    } as unknown as Response;
    const fetchImpl = jest.fn(async () => unavailable);
    const sleep = jest.fn(async () => undefined);
    const api = guestApi(
      createApiClient({
        baseUrl: 'http://api',
        fetchImpl: fetchImpl as unknown as typeof fetch,
        sleep,
      }),
    );

    await expect(api.comparePnl({ paperBotId: 'p-1', realBotId: 'r-1' })).rejects.toMatchObject({
      status: 503,
      correlationId: 'corr-c6',
    });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(sleep).not.toHaveBeenCalled();

    fetchImpl.mockClear();
    await expect(api.getPnlByMode()).rejects.toMatchObject({ status: 503 });
    expect(fetchImpl).toHaveBeenCalledTimes(3);
  });
});
