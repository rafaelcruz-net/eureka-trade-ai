import { ApiError, createApiClient } from '@/lib/api/client';
import { tokenStore } from '@/lib/auth/token-store';

interface FakeResponse {
  ok: boolean;
  status: number;
  json: () => Promise<unknown>;
}

function respond(status: number, body?: unknown): FakeResponse {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => {
      if (body === undefined) {
        throw new SyntaxError('no body');
      }
      return body;
    },
  };
}

function abortError(): Error {
  const error = new Error('aborted');
  error.name = 'AbortError';
  return error;
}

function setup(...results: Array<FakeResponse | Error>) {
  const fetchImpl = jest.fn(async () => {
    const next = results.shift();
    if (next instanceof Error) {
      throw next;
    }
    return next as unknown as Response;
  });
  const sleep = jest.fn(async () => undefined);
  let id = 0;
  const client = createApiClient({
    baseUrl: 'http://api',
    fetchImpl: fetchImpl as unknown as typeof fetch,
    sleep,
    newId: () => `id-${++id}`,
  });
  return { client, fetchImpl, sleep };
}

function headersOf(fetchImpl: jest.Mock, call = 0): Record<string, string> {
  return (fetchImpl.mock.calls[call] as unknown as [string, RequestInit])[1].headers as Record<
    string,
    string
  >;
}

describe('api client', () => {
  afterEach(() => tokenStore.clear());

  it('sends a correlation id and the bearer token only when asked', async () => {
    tokenStore.set('secret-token');
    const { client, fetchImpl } = setup(respond(200, { ok: 1 }), respond(200, { ok: 2 }));

    await client.request('/a', { authenticated: true });
    await client.request('/b');

    expect(headersOf(fetchImpl, 0)['X-Correlation-Id']).toBe('id-1');
    expect(headersOf(fetchImpl, 0).Authorization).toBe('Bearer secret-token');
    expect(headersOf(fetchImpl, 1).Authorization).toBeUndefined();
  });

  it('adds an Idempotency-Key and credentials only when requested', async () => {
    const { client, fetchImpl } = setup(respond(200, {}), respond(200, {}));

    await client.request('/x', {
      method: 'POST',
      body: { a: 1 },
      idempotent: true,
      credentials: true,
    });
    await client.request('/y', { method: 'POST', body: {} });

    const first = (fetchImpl.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect((first.headers as Record<string, string>)['Idempotency-Key']).toBe('id-2');
    expect(first.credentials).toBe('include');
    expect(first.body).toBe('{"a":1}');
    expect(headersOf(fetchImpl, 1)['Idempotency-Key']).toBeUndefined();
    expect((fetchImpl.mock.calls[1] as unknown as [string, RequestInit])[1].credentials).toBe(
      'omit',
    );
  });

  it('retries a GET at most twice on 503 with growing waits', async () => {
    const { client, fetchImpl, sleep } = setup(
      respond(503),
      respond(503),
      respond(200, { done: true }),
    );

    await expect(client.request('/list')).resolves.toEqual({ done: true });
    expect(fetchImpl).toHaveBeenCalledTimes(3);
    expect(sleep.mock.calls).toEqual([[300], [600]]);
  });

  it('gives up after two retries', async () => {
    const { client, fetchImpl } = setup(
      respond(503),
      respond(503),
      respond(503, { code: 'SERVICE_UNAVAILABLE' }),
    );

    await expect(client.request('/list')).rejects.toMatchObject({
      status: 503,
      code: 'SERVICE_UNAVAILABLE',
    });
    expect(fetchImpl).toHaveBeenCalledTimes(3);
  });

  it('never retries a POST without Idempotency-Key', async () => {
    const { client, fetchImpl } = setup(respond(503, { code: 'SERVICE_UNAVAILABLE' }));

    await expect(client.request('/refresh', { method: 'POST' })).rejects.toBeInstanceOf(ApiError);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it('retries an idempotent POST after a timeout and reuses its key', async () => {
    const { client, fetchImpl } = setup(abortError(), respond(200, { ok: true }));

    await expect(
      client.request('/challenge', { method: 'POST', body: {}, idempotent: true }),
    ).resolves.toEqual({
      ok: true,
    });
    expect(headersOf(fetchImpl, 0)['Idempotency-Key']).toBe(
      headersOf(fetchImpl, 1)['Idempotency-Key'],
    );
  });

  it('does not retry other HTTP errors and maps the problem body', async () => {
    const { client, fetchImpl } = setup(
      respond(401, { code: 'LOGIN_CHALLENGE_REJECTED', reason: 'EXPIRED', correlationId: 'c-1' }),
    );

    await expect(
      client.request('/verify', { method: 'POST', idempotent: true }),
    ).rejects.toMatchObject({
      status: 401,
      code: 'LOGIN_CHALLENGE_REJECTED',
      reason: 'EXPIRED',
      correlationId: 'c-1',
    });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it('maps network failures and empty error bodies', async () => {
    const { client } = setup(new TypeError('offline'), respond(500));

    await expect(client.request('/a', { method: 'POST' })).rejects.toMatchObject({
      status: 0,
      code: 'NETWORK',
    });
    await expect(client.request('/b', { method: 'POST' })).rejects.toMatchObject({
      status: 500,
      code: 'HTTP_ERROR',
    });
  });

  it('returns undefined for 204', async () => {
    const { client } = setup(respond(204));

    await expect(client.request('/logout', { method: 'POST' })).resolves.toBeUndefined();
  });

  it('aborts a request after the timeout', async () => {
    jest.useFakeTimers();
    const fetchImpl = jest.fn(
      (_url: string, init: RequestInit) =>
        new Promise((_resolve, reject) => {
          init.signal?.addEventListener('abort', () => reject(abortError()));
        }),
    );
    const client = createApiClient({
      baseUrl: 'http://api',
      fetchImpl: fetchImpl as unknown as typeof fetch,
      maxRetries: 0,
      timeoutMs: 10_000,
    });

    const pending = client.request('/slow');
    jest.advanceTimersByTime(10_000);

    await expect(pending).rejects.toMatchObject({ code: 'TIMEOUT' });
    jest.useRealTimers();
  });
});
