import { createApiClient } from '@/lib/api/client';
import { guestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';

/**
 * 261113-trading-real-bot U6, Step 12, written before the code (logical-components.md "Ajustes menores"; BR1.4, BR3.5,
 * U6-NFR4.5): the second instance of the guest API, used by the signing banner and the screen of deposit and
 * withdrawal, never calls the handler of the reacceptance lock, and shares the ONE renewal of the session with the main
 * instance (two parallel refreshes would revoke the session).
 */
function answer(status: number, body: unknown) {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: { get: () => null },
    json: async () => body,
  } as unknown as Response;
}

afterEach(() => tokenStore.clear());

describe('the guest API without the handler of the reacceptance lock', () => {
  it('a 403 TERMS_REACCEPTANCE_REQUIRED reaches only the caller of the second instance', async () => {
    const fetchImpl = jest.fn(async () => answer(403, { code: 'TERMS_REACCEPTANCE_REQUIRED' }));
    const client = createApiClient({
      baseUrl: 'http://api',
      fetchImpl,
      sleep: async () => undefined,
    });
    const main = guestApi(client);
    const quiet = guestApi(client, { renewSession: () => main.refreshSession() });
    const handler = jest.fn();
    main.setTermsReacceptanceHandler(handler);
    tokenStore.set('token');

    await expect(quiet.listRealOrders({ state: 'QUOTED', limit: 1 })).rejects.toMatchObject({
      code: 'TERMS_REACCEPTANCE_REQUIRED',
    });
    expect(handler).not.toHaveBeenCalled();

    await expect(main.getRealPnl()).rejects.toMatchObject({ code: 'TERMS_REACCEPTANCE_REQUIRED' });
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('a 401 on both instances at the same time renews the session ONCE', async () => {
    let refreshes = 0;
    const fetchImpl = jest.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
      if (String(url).endsWith('/api/v1/auth/refresh')) {
        refreshes += 1;
        return answer(200, {
          accessToken: 'renewed',
          accessTokenExpiresAt: '2026-11-16T12:15:00Z',
          sessionExpiresAt: '2026-11-17T00:00:00Z',
          role: 'BETA_USER',
        });
      }
      const auth = (init?.headers as Record<string, string>).Authorization;
      return auth === 'Bearer renewed'
        ? answer(200, { items: [], total: 0 })
        : answer(401, { code: 'UNAUTHORIZED' });
    });
    const client = createApiClient({
      baseUrl: 'http://api',
      fetchImpl,
      sleep: async () => undefined,
    });
    const main = guestApi(client);
    const quiet = guestApi(client, { renewSession: () => main.refreshSession() });
    tokenStore.set('expired');

    await Promise.all([main.listRealOrders(), quiet.listRealOrders()]);

    expect(refreshes).toBe(1);
  });

  it('the REAL routes carry the method, the path and the Idempotency-Key the contract asks', async () => {
    const fetchImpl = jest.fn(async () => answer(200, {}));
    const client = createApiClient({
      baseUrl: 'http://api',
      fetchImpl,
      sleep: async () => undefined,
    });
    const api = guestApi(client);
    tokenStore.set('token');

    await api.changeRealPreferences({ capLamports: '1' }, 'key-1');
    await api.rejectRealSigning('order 1', 'key-2');
    await api.getRealWithdrawal('w/1');

    const calls = fetchImpl.mock.calls as unknown as Array<[string, RequestInit]>;
    expect(calls[0]?.[0]).toBe('http://api/api/v1/real/bot-wallet/preferences');
    expect(calls[0]?.[1].method).toBe('PATCH');
    expect((calls[0]?.[1].headers as Record<string, string>)['Idempotency-Key']).toBe('key-1');
    expect(calls[1]?.[0]).toBe('http://api/api/v1/real/orders/order%201/reject');
    expect(calls[2]?.[0]).toBe('http://api/api/v1/real/bot-wallet/withdrawals/w%2F1');
  });
});
