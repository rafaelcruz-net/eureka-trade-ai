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
