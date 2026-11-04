/**
 * @jest-environment node
 */
import { GET } from '@/app/api/health/route';
import { createApiClient } from '@/lib/api/client';
import { guestApi } from '@/lib/api/guest-api';

describe('health route', () => {
  it('answers 200 with UP and no caching', async () => {
    const response = GET();

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: 'UP' });
    expect(response.headers.get('Cache-Control')).toBe('no-store');
  });
});

describe('guest api calls', () => {
  it('maps every C1 and C2 call to its route, method and options', async () => {
    const calls: Array<[string, RequestInit]> = [];
    const fetchImpl = jest.fn(async (url: string, init: RequestInit) => {
      calls.push([url, init]);
      return { ok: true, status: 200, json: async () => ({}) } as unknown as Response;
    });
    const api = guestApi(
      createApiClient({ baseUrl: 'http://api', fetchImpl: fetchImpl as unknown as typeof fetch }),
    );

    await api.requestChallenge('Wallet111');
    await api.verifyLogin({
      walletAddress: 'w',
      message: 'm',
      signature: 's',
      provider: 'PHANTOM',
    });
    await api.refreshSession();
    await api.logout();
    await api.listTokens({ cursor: null, limit: 50 });
    await api.listTokens({ cursor: 'abc', limit: 10, filters: { minLiquidity: '5' } });
    await api.getAssessment('solana', 'Mint111');

    expect(calls.map(([url, init]) => `${init.method} ${url}`)).toEqual([
      'POST http://api/api/v1/auth/challenge',
      'POST http://api/api/v1/auth/verify',
      'POST http://api/api/v1/auth/refresh',
      'POST http://api/api/v1/auth/logout',
      'GET http://api/api/v1/scanner/tokens?limit=50',
      'GET http://api/api/v1/scanner/tokens?limit=10&cursor=abc&minLiquidity=5',
      'GET http://api/api/v1/tokens/solana/Mint111/assessment',
    ]);
    expect(calls.map(([, init]) => init.credentials)).toEqual([
      'omit',
      'include',
      'include',
      'include',
      'omit',
      'omit',
      'omit',
    ]);
    const keyOf = (i: number) =>
      (calls[i]?.[1].headers as Record<string, string>)['Idempotency-Key'];
    expect(keyOf(0)).toBeDefined();
    expect(keyOf(2)).toBeUndefined();
  });
});
