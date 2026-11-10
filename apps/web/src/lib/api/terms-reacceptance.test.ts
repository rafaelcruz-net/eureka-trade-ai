import { createStreamClient, STREAM_PATH } from '@/features/dashboard/stream-client';
import { ApiError, createApiClient } from '@/lib/api/client';
import { guestApi, isTermsReacceptance, TERMS_REACCEPTANCE_REQUIRED } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';

/**
 * Passo 8 of u3-reacceptance (BR3.7, NFR1.1, NFR2.4): the API layer of the guest app calls ONE handler, registered by
 * the ApiProvider, when a call meets `403 TERMS_REACCEPTANCE_REQUIRED`; the live channel of the dashboard calls the
 * same handler; the screen still receives the error with `currentVersion`. Real client, fake `fetch`.
 */
const ORIGIN = 'http://api.test';

function json(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({ 'Content-Type': 'application/json' }),
    json: async () => body,
  } as unknown as Response;
}

const LOCK = { code: TERMS_REACCEPTANCE_REQUIRED, status: 403, currentVersion: 'v2' };

function apiAnswering(answer: (url: string, init: RequestInit) => Response) {
  const calls: Array<{ url: string; init: RequestInit }> = [];
  const fetchImpl = jest.fn(async (url: string, init: RequestInit = {}) => {
    calls.push({ url, init });
    return answer(url, init);
  }) as unknown as typeof fetch;
  const api = guestApi(
    createApiClient({
      baseUrl: ORIGIN,
      fetchImpl,
      sleep: async () => undefined,
      newId: () => 'id-1',
    }),
  );
  return { api, calls };
}

describe('the 403 of the reacceptance lock in the API layer', () => {
  beforeEach(() => tokenStore.set('token-1'));
  afterEach(() => tokenStore.clear());

  it('calls the registered handler once and still rejects with currentVersion', async () => {
    const { api } = apiAnswering(() => json(403, LOCK));
    const handler = jest.fn();
    api.setTermsReacceptanceHandler(handler);

    const failure = await api.listBots().catch((error: unknown) => error);

    expect(handler).toHaveBeenCalledTimes(1);
    expect(isTermsReacceptance(failure)).toBe(true);
    expect((failure as ApiError).currentVersion).toBe('v2');
  });

  it('does not call the handler for any other 403', async () => {
    const { api } = apiAnswering(() => json(403, { code: 'FORBIDDEN_ROLE' }));
    const handler = jest.fn();
    api.setTermsReacceptanceHandler(handler);

    await expect(api.getPnl({ from: 'a', to: 'b' })).rejects.toBeInstanceOf(ApiError);
    expect(handler).not.toHaveBeenCalled();
    expect(isTermsReacceptance(new ApiError(409, 'TERMS_VERSION_OUTDATED'))).toBe(false);
    expect(isTermsReacceptance('not an error')).toBe(false);
  });

  it('calls the handler when the call meets the lock after a renewal of the token', async () => {
    let refreshed = false;
    const { api } = apiAnswering((url) => {
      if (url.endsWith('/api/v1/auth/refresh')) {
        refreshed = true;
        return json(200, { accessToken: 'token-2' });
      }
      return refreshed ? json(403, LOCK) : json(401, { code: 'UNAUTHENTICATED' });
    });
    const handler = jest.fn();
    api.setTermsReacceptanceHandler(handler);

    await expect(api.listLedger()).rejects.toMatchObject({ code: TERMS_REACCEPTANCE_REQUIRED });
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('unregisters the handler and never fails without one', async () => {
    const { api } = apiAnswering(() => json(403, LOCK));
    const handler = jest.fn();
    const unregister = api.setTermsReacceptanceHandler(handler);
    unregister();

    await expect(api.listBots()).rejects.toBeInstanceOf(ApiError);
    expect(handler).not.toHaveBeenCalled();
    expect(() => api.notifyTermsReacceptance()).not.toThrow();
  });

  it('an older registration never removes a newer handler', async () => {
    const { api } = apiAnswering(() => json(403, LOCK));
    const first = jest.fn();
    const second = jest.fn();
    const unregisterFirst = api.setTermsReacceptanceHandler(first);
    api.setTermsReacceptanceHandler(second);
    unregisterFirst();

    api.notifyTermsReacceptance();

    expect(second).toHaveBeenCalledTimes(1);
    expect(first).not.toHaveBeenCalled();
  });

  it('accepts the version with the Idempotency-Key of the screen and the token', async () => {
    const { api, calls } = apiAnswering(() =>
      json(200, { acceptedVersion: 'v2', currentVersion: 'v2', acceptedAt: 'x', upToDate: true }),
    );

    const status = await api.acceptTerms('v2', 'key-of-the-submission');

    expect(status.upToDate).toBe(true);
    const call = calls[0];
    expect(call?.url).toBe(`${ORIGIN}/api/v1/terms/acceptances`);
    expect(call?.init.method).toBe('POST');
    expect(call?.init.body).toBe(JSON.stringify({ version: 'v2' }));
    const headers = call?.init.headers as Record<string, string>;
    expect(headers['Idempotency-Key']).toBe('key-of-the-submission');
    expect(headers.Authorization).toBe('Bearer token-1');
  });

  it('a 409 of the acceptance carries the current version and never calls the handler', async () => {
    const { api } = apiAnswering(() =>
      json(409, { code: 'TERMS_VERSION_OUTDATED', currentVersion: 'v3' }),
    );
    const handler = jest.fn();
    api.setTermsReacceptanceHandler(handler);

    const failure = (await api.acceptTerms('v2', 'k').catch((error: unknown) => error)) as ApiError;

    expect(failure.code).toBe('TERMS_VERSION_OUTDATED');
    expect(failure.currentVersion).toBe('v3');
    expect(handler).not.toHaveBeenCalled();
  });

  it('reads the current legal documents without token nor cookie, revalidating with the server', async () => {
    const { api, calls } = apiAnswering(() => json(200, { version: 'v2' }));

    await api.getCurrentLegalDocuments();

    const call = calls[0];
    expect(call?.url).toBe(`${ORIGIN}/api/v1/legal/current`);
    expect(call?.init.cache).toBe('no-cache');
    expect(call?.init.credentials).toBe('omit');
    expect((call?.init.headers as Record<string, string>).Authorization).toBeUndefined();
  });
});

describe('the 403 of the reacceptance lock in the live channel', () => {
  it('closes the channel and calls the same handler', async () => {
    const onTermsReacceptance = jest.fn();
    const onStatus = jest.fn();
    const fetchImpl = jest.fn(async () => json(403, LOCK)) as unknown as typeof fetch;
    const channel = createStreamClient({
      url: `${ORIGIN}${STREAM_PATH}`,
      getToken: () => 'token-1',
      renew: async () => true,
      onEvent: jest.fn(),
      onStatus,
      onSessionExpired: jest.fn(),
      onTermsReacceptance,
      fetchImpl,
      newId: () => 'id',
    });

    channel.start();

    await new Promise((resolve) => setTimeout(resolve, 0));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(onTermsReacceptance).toHaveBeenCalledTimes(1);
    expect(onStatus).toHaveBeenLastCalledWith({ state: 'CLOSED', outdated: false });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    channel.close();
  });

  it('treats any other 403 of the channel as a drop, without the handler', async () => {
    jest.useFakeTimers();
    const onTermsReacceptance = jest.fn();
    const channel = createStreamClient({
      url: `${ORIGIN}${STREAM_PATH}`,
      getToken: () => 'token-1',
      renew: async () => true,
      onEvent: jest.fn(),
      onStatus: jest.fn(),
      onSessionExpired: jest.fn(),
      onTermsReacceptance,
      fetchImpl: jest.fn(async () =>
        json(403, { code: 'FORBIDDEN_ROLE' }),
      ) as unknown as typeof fetch,
      newId: () => 'id',
    });

    channel.start();
    await jest.advanceTimersByTimeAsync(0);

    expect(onTermsReacceptance).not.toHaveBeenCalled();
    channel.close();
    jest.useRealTimers();
  });
});
