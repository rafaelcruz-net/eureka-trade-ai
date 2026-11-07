import { channelRenewal } from '@/features/dashboard/channel-renewal';
import { createStreamClient, STREAM_PATH } from '@/features/dashboard/stream-client';
import { createApiClient } from '@/lib/api/client';
import { guestApi, type GuestSession } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';

/**
 * U9 review R-01: the renewal of the access token is single-flight. U3's refresh revokes the whole session when an
 * already exchanged refresh token comes back (REUSED), so REST calls that get 401 and the live channel of the dashboard
 * must share ONE `POST /auth/refresh` while it is in flight. Real `guestApi`, real stream client, fake `fetch`.
 */
const ORIGIN = 'http://api.test';
const REFRESH = `${ORIGIN}/api/v1/auth/refresh`;
const STREAM = `${ORIGIN}${STREAM_PATH}`;

interface Deferred<T> {
  promise: Promise<T>;
  resolve(value: T): void;
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function json(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({ 'Content-Type': 'application/json' }),
    json: async () => body,
  } as unknown as Response;
}

function session(token: string): GuestSession {
  return { accessToken: token } as GuestSession;
}

/** Fake network: REST and channel answer 401 to the expired token and 200 to the renewed one; refresh waits. */
function fakeNetwork(refreshAnswer: Deferred<Response>) {
  const requests: Array<{ url: string; authorization?: string }> = [];
  const impl = jest.fn(async (url: string, init: RequestInit = {}) => {
    const authorization = (init.headers as Record<string, string> | undefined)?.Authorization;
    requests.push({ url, authorization });
    if (url === REFRESH) {
      return refreshAnswer.promise;
    }
    const renewed = authorization === 'Bearer token-renewed';
    if (url === STREAM) {
      // The reopening after the renewal never answers; the test closes the channel.
      return renewed ? new Promise<Response>(() => undefined) : json(401, { code: 'UNAUTHORIZED' });
    }
    return renewed ? json(200, { id: 'acc-1' }) : json(401, { code: 'UNAUTHORIZED' });
  });
  const refreshCount = () => requests.filter((request) => request.url === REFRESH).length;
  return { impl, requests, refreshCount };
}

function setup() {
  const refreshAnswer = deferred<Response>();
  const net = fakeNetwork(refreshAnswer);
  const api = guestApi(
    createApiClient({ baseUrl: ORIGIN, fetchImpl: net.impl as unknown as typeof fetch }),
  );
  const onSessionExpired = jest.fn();
  const channel = createStreamClient({
    url: STREAM,
    getToken: () => tokenStore.get(),
    renew: channelRenewal(api),
    onEvent: () => undefined,
    onStatus: () => undefined,
    onSessionExpired,
    fetchImpl: net.impl as unknown as typeof fetch,
  });
  return { refreshAnswer, net, api, channel, onSessionExpired };
}

/** Lets every pending promise chain run (no timers involved). */
async function flush() {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve();
  }
}

beforeEach(() => {
  tokenStore.set('token-expired');
  jest.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => {
  tokenStore.clear();
  jest.restoreAllMocks();
});

describe('single-flight token renewal', () => {
  it('sends exactly one refresh for simultaneous 401s from two REST calls and the channel', async () => {
    const t = setup();

    const first = t.api.getAccount();
    const second = t.api.getAccount();
    t.channel.start();
    await flush();

    expect(t.net.refreshCount()).toBe(1);
    t.refreshAnswer.resolve(json(200, session('token-renewed')));

    await expect(first).resolves.toEqual({ id: 'acc-1' });
    await expect(second).resolves.toEqual({ id: 'acc-1' });
    await flush();

    expect(t.net.refreshCount()).toBe(1);
    expect(tokenStore.get()).toBe('token-renewed');
    // The channel reopened with the renewed token and did not start the session-expired flow.
    const streamOpens = t.net.requests.filter((request) => request.url === STREAM);
    expect(streamOpens.map((request) => request.authorization)).toEqual([
      'Bearer token-expired',
      'Bearer token-renewed',
    ]);
    expect(t.onSessionExpired).not.toHaveBeenCalled();
    t.channel.close();
  });

  it('repeats a late 401 with the token another caller already renewed, without a new refresh', async () => {
    const t = setup();
    t.refreshAnswer.resolve(json(200, session('token-renewed')));
    let releaseLate!: () => void;
    const lateGate = new Promise<void>((done) => {
      releaseLate = done;
    });
    const original = t.net.impl.getMockImplementation()!;
    t.net.impl.mockImplementation(async (url: string, init: RequestInit = {}) => {
      const answer = original(url, init);
      if (url === `${ORIGIN}/api/v1/pnl?from=a&to=b`) {
        // This call was sent with the expired token; its 401 only arrives after the renewal ended.
        await lateGate;
      }
      return answer;
    });

    const late = t.api.getPnl({ from: 'a', to: 'b' });
    await t.api.getAccount();
    expect(t.net.refreshCount()).toBe(1);
    releaseLate();

    await expect(late).resolves.toEqual({ id: 'acc-1' });
    // The repeat went out with the renewed token: no second refresh was sent.
    expect(t.net.refreshCount()).toBe(1);
    const pnlCalls = t.net.requests.filter((request) => request.url.includes('/pnl'));
    expect(pnlCalls.map((request) => request.authorization)).toEqual([
      'Bearer token-expired',
      'Bearer token-renewed',
    ]);
  });

  it('keeps the original 401 and the channel expiry when the shared renewal fails', async () => {
    const t = setup();

    const call = t.api.getAccount();
    t.channel.start();
    await flush();
    t.refreshAnswer.resolve(json(401, { code: 'REUSED' }));

    await expect(call).rejects.toMatchObject({ status: 401, code: 'UNAUTHORIZED' });
    await flush();

    expect(t.net.refreshCount()).toBe(1);
    expect(t.onSessionExpired).toHaveBeenCalledTimes(1);
    expect(tokenStore.get()).toBe('token-expired');
  });

  it('starts a new renewal after the previous one ended', async () => {
    const t = setup();
    t.refreshAnswer.resolve(json(200, session('token-renewed')));

    await t.api.refreshSession();
    tokenStore.set('token-expired');
    await t.api.refreshSession();

    expect(t.net.refreshCount()).toBe(2);
  });
});
