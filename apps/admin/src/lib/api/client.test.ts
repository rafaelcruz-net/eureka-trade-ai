import { ApiError, createApiClient } from '@/lib/api/client';
import { adminApi } from '@/lib/api/admin-api';
import { tokenStore } from '@/lib/auth/token-store';

interface FakeResponse {
  ok: boolean;
  status: number;
  json: () => Promise<unknown>;
  headers?: { get: (name: string) => string | null };
}

function respond(
  status: number,
  body?: unknown,
  headers: Record<string, string> = {},
): FakeResponse {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => {
      if (body === undefined) {
        throw new SyntaxError('no body');
      }
      return body;
    },
    headers: { get: (name: string) => headers[name] ?? null },
  };
}

function setup(...results: Array<FakeResponse | Error>) {
  const calls: Array<{ url: string; init: RequestInit }> = [];
  const fetchImpl = jest.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ url: String(url), init: init ?? {} });
    const next = results.shift();
    if (next instanceof Error) {
      throw next;
    }
    return next as unknown as Response;
  });
  let id = 0;
  const client = createApiClient({
    baseUrl: 'http://api',
    fetchImpl,
    sleep: async () => undefined,
    newId: () => `id-${++id}`,
  });
  return { client, calls, api: adminApi(client) };
}

const header = (init: RequestInit, name: string) => (init.headers as Record<string, string>)[name];

describe('administrator API client', () => {
  beforeEach(() => tokenStore.clear());

  it('adds the bearer token only to authenticated calls', async () => {
    tokenStore.set('admin-token');
    const { client, calls } = setup(respond(200, {}), respond(200, {}));

    await client.request('/a', { authenticated: true });
    await client.request('/b');

    expect(header(calls[0]!.init, 'Authorization')).toBe('Bearer admin-token');
    expect(header(calls[1]!.init, 'Authorization')).toBeUndefined();
  });

  it('retries a GET on 503 and gives up after two retries', async () => {
    const { client, calls } = setup(respond(503), respond(503), respond(503));

    await expect(client.request('/x')).rejects.toMatchObject({ status: 503 });
    expect(calls).toHaveLength(3);
  });

  it('never retries a write without an Idempotency-Key', async () => {
    const { client, calls } = setup(respond(503));

    await expect(client.request('/x', { method: 'POST' })).rejects.toBeInstanceOf(ApiError);
    expect(calls).toHaveLength(1);
  });

  it('maps the problem with code, reason, fields and Retry-After', async () => {
    const { client } = setup(
      respond(
        429,
        { code: 'RATE_LIMITED', reason: 'X', fields: [{ field: 'a', message: 'b' }] },
        { 'Retry-After': '30' },
      ),
    );

    await expect(client.request('/x')).rejects.toMatchObject({
      code: 'RATE_LIMITED',
      reason: 'X',
      fields: [{ field: 'a', message: 'b' }],
      retryAfterSeconds: 30,
    });
  });

  it('turns a network failure and a timeout into status 0', async () => {
    const timeout = new Error('aborted');
    timeout.name = 'AbortError';
    const { client } = setup(new TypeError('offline'), timeout, timeout, timeout);

    await expect(client.request('/x', { method: 'POST' })).rejects.toMatchObject({
      code: 'NETWORK',
    });
    await expect(client.request('/y')).rejects.toMatchObject({ code: 'TIMEOUT', status: 0 });
  });

  it('answers undefined for a 204', async () => {
    const { client } = setup(respond(204));

    await expect(client.request('/logout', { method: 'POST' })).resolves.toBeUndefined();
  });
});

describe('typed administrative API', () => {
  beforeEach(() => tokenStore.clear());

  it('sends the cookie only on verify, refresh and logout (R-08)', async () => {
    tokenStore.set('admin-token');
    const { api, calls } = setup(
      respond(200, { message: 'm' }),
      respond(200, { accessToken: 't' }),
      respond(200, { accessToken: 't' }),
      respond(204),
      respond(200, {}),
    );

    await api.requestChallenge('wallet');
    await api.verifyLogin({
      walletAddress: 'w',
      message: 'm',
      signature: 's',
      provider: 'PHANTOM',
    });
    await api.refreshSession();
    await api.logout();
    await api.getKillSwitch();

    expect(calls.map((call) => call.init.credentials)).toEqual([
      'omit',
      'include',
      'include',
      'include',
      'omit',
    ]);
    expect(header(calls[0]!.init, 'Idempotency-Key')).toBeDefined();
  });

  it('renews once through the cookie on a 401 and repeats the call', async () => {
    tokenStore.set('old');
    const { api, calls } = setup(
      respond(401, { code: 'UNAUTHENTICATED' }),
      respond(200, { accessToken: 'new' }),
      respond(200, { state: 'OFF' }),
    );

    await expect(api.getKillSwitch()).resolves.toEqual({ state: 'OFF' });
    expect(tokenStore.get()).toBe('new');
    expect(calls.map((call) => call.url)).toEqual([
      'http://api/api/v1/admin/kill-switch',
      'http://api/api/v1/admin/auth/refresh',
      'http://api/api/v1/admin/kill-switch',
    ]);
  });

  it('gives the original 401 when the renewal fails too', async () => {
    const { api } = setup(
      respond(401, { code: 'UNAUTHENTICATED' }),
      respond(401, { code: 'UNAUTHENTICATED' }),
    );

    await expect(api.getSystemStatus()).rejects.toMatchObject({ status: 401 });
  });

  it('keeps the key of the screen on the PUT and builds the cursor queries', async () => {
    const { api, calls } = setup(
      respond(200, {}),
      respond(200, { items: [] }),
      respond(200, { items: [] }),
    );

    await api.changeKillSwitch({ state: 'ON', reason: 'motivo' }, 'screen-key');
    await api.listInvites('abc');
    await api.listFeedback(null);

    expect(calls[0]!.init.method).toBe('PUT');
    expect(header(calls[0]!.init, 'Idempotency-Key')).toBe('screen-key');
    expect(calls[1]!.url).toBe('http://api/api/v1/admin/invites?cursor=abc&limit=20');
    expect(calls[2]!.url).toBe('http://api/api/v1/admin/feedback?limit=20');
  });
});
