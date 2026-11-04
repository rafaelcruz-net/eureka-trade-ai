/**
 * @jest-environment node
 */
import { ApiError, createApiClient } from '@/lib/api/client';
import { guestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';

type Answer = { status: number; body?: unknown; headers?: Record<string, string> };

function scripted(answers: Answer[]) {
  const calls: Array<{ url: string; authorization?: string }> = [];
  const fetchImpl = jest.fn(async (url: string, init: RequestInit) => {
    const headers = init.headers as Record<string, string>;
    calls.push({ url, authorization: headers.Authorization });
    const next = answers.shift() ?? { status: 500 };
    return {
      ok: next.status >= 200 && next.status < 300,
      status: next.status,
      headers: new Headers(next.headers ?? {}),
      json: async () => next.body ?? {},
    } as unknown as Response;
  });
  const api = guestApi(
    createApiClient({ baseUrl: 'http://api', fetchImpl: fetchImpl as unknown as typeof fetch }),
  );
  return { api, calls };
}

/** U3 Passo 19 (F6, BR4.1): a 401 tries one renewal; only when it fails too does the 401 reach the screen. */
describe('authenticated calls after a 401', () => {
  beforeEach(() => tokenStore.set('old-token'));

  it('renews once through the cookie and repeats the call with the new token', async () => {
    const { api, calls } = scripted([
      { status: 401, body: { code: 'UNAUTHENTICATED' } },
      { status: 200, body: { accessToken: 'new-token' } },
      { status: 200, body: { walletAddressMasked: 'Abcd…wxyz' } },
    ]);

    const account = await api.getAccount();

    expect(account.walletAddressMasked).toBe('Abcd…wxyz');
    expect(calls.map((c) => c.url)).toEqual([
      'http://api/api/v1/account',
      'http://api/api/v1/auth/refresh',
      'http://api/api/v1/account',
    ]);
    expect(calls[2]?.authorization).toBe('Bearer new-token');
    expect(tokenStore.get()).toBe('new-token');
  });

  it('gives the original 401 back when the renewal fails too', async () => {
    const { api, calls } = scripted([
      { status: 401, body: { code: 'UNAUTHENTICATED' } },
      { status: 401, body: { code: 'UNAUTHENTICATED' } },
    ]);

    await expect(api.listTokens({ cursor: null, limit: 50 })).rejects.toMatchObject({
      status: 401,
      code: 'UNAUTHENTICATED',
    });
    expect(calls).toHaveLength(2);
  });

  it('never renews on another error and never renews twice', async () => {
    const forbidden = scripted([{ status: 403, body: { code: 'FORBIDDEN_ROLE' } }]);
    await expect(forbidden.api.getAccount()).rejects.toBeInstanceOf(ApiError);
    expect(forbidden.calls).toHaveLength(1);

    const twice = scripted([
      { status: 401 },
      { status: 200, body: { accessToken: 'new-token' } },
      { status: 401 },
    ]);
    await expect(twice.api.getAssessment('solana', 'Mint')).rejects.toMatchObject({ status: 401 });
    expect(twice.calls).toHaveLength(3);
  });

  it('the invitation lookup sends no token, no cookie and reads Retry-After', async () => {
    const { api, calls } = scripted([
      { status: 429, body: { code: 'RATE_LIMITED' }, headers: { 'Retry-After': '42' } },
    ]);

    const failure = await api.lookupInvite('A/B').catch((error: unknown) => error);

    expect(failure).toMatchObject({ status: 429, code: 'RATE_LIMITED', retryAfterSeconds: 42 });
    expect(calls[0]?.url).toBe('http://api/api/v1/invites/A%2FB');
    expect(calls[0]?.authorization).toBeUndefined();
  });

  it('ignores a Retry-After that is not a number of seconds', async () => {
    const { api } = scripted([{ status: 429, headers: { 'Retry-After': 'soon' } }]);

    const failure = (await api.lookupInvite('x').catch((error: unknown) => error)) as ApiError;

    expect(failure.retryAfterSeconds).toBeUndefined();
  });
});
