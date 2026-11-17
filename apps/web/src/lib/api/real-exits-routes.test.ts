/**
 * @jest-environment node
 */
import { createApiClient } from '@/lib/api/client';
import { guestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';

/**
 * 261113-trading-real-bot U7, Step 14, written after the code (R5, BR7, BR8): every call of the conditional exits and of
 * the fiscal export goes to its route with its method, the Idempotency-Key of the writes, and the CSV as a file.
 */
afterEach(() => tokenStore.clear());

describe('guest api calls of the exits (U7)', () => {
  it('maps every conditional and export call to its route, method and headers', async () => {
    tokenStore.set('access-token-value');
    const calls: Array<[string, RequestInit]> = [];
    const csv = new Blob(['﻿data_utc'], { type: 'text/csv' });
    const fetchImpl = jest.fn(async (url: string, init: RequestInit) => {
      calls.push([url, init]);
      return {
        ok: true,
        status: 200,
        json: async () => ({}),
        blob: async () => csv,
      } as unknown as Response;
    });
    const api = guestApi(
      createApiClient({ baseUrl: 'http://api', fetchImpl: fetchImpl as unknown as typeof fetch }),
    );

    await api.quoteRealConditional('Mint1');
    await api.createRealConditional(
      { positionMint: 'Mint1', kind: 'STOP', stopPriceUsd: '0.1' },
      'k1',
    );
    await api.listRealConditionals();
    await api.listRealConditionals({ cursor: 'c', limit: 20 });
    await api.signRealConditional('id/1', { signature: 'sig' }, 'k2');
    await api.cancelRealConditional('id-1', 'k3');
    const file = await api.exportRealFiscal({ from: '2026-01-01', to: '2026-02-01', tz: 'UTC' });

    expect(calls.map(([url, init]) => `${init.method} ${url}`)).toEqual([
      'GET http://api/api/v1/real/conditional-orders/quote?positionMint=Mint1',
      'POST http://api/api/v1/real/conditional-orders',
      'GET http://api/api/v1/real/conditional-orders',
      'GET http://api/api/v1/real/conditional-orders?cursor=c&limit=20',
      'POST http://api/api/v1/real/conditional-orders/id%2F1/signature',
      'DELETE http://api/api/v1/real/conditional-orders/id-1',
      'GET http://api/api/v1/real/exports/fiscal?from=2026-01-01&to=2026-02-01&tz=UTC',
    ]);
    const headers = (i: number) => calls[i]?.[1].headers as Record<string, string>;
    expect(headers(1)['Idempotency-Key']).toBe('k1');
    expect(headers(4)['Idempotency-Key']).toBe('k2');
    expect(headers(5)['Idempotency-Key']).toBe('k3');
    expect(headers(0)['Idempotency-Key']).toBeUndefined();
    expect(file).toBe(csv);
  });
});
