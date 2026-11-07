import {
  BACKOFF_MS,
  createStreamClient,
  STALE_AFTER_MS,
  type ChannelStatus,
  type StreamClientOptions,
} from '@/features/dashboard/stream-client';
import type { DashboardEvent } from '@/features/dashboard/envelopes';
import {
  botSummary,
  fakeChannel,
  fakeFetch,
  response,
  settle,
  sseBlock,
  startFakeClock,
} from '@/features/dashboard/test-support';

/**
 * Passo 6 of U9 (BR4.2, BR5.1 to BR5.6; NFR1.3, NFR2.1, NFR2.2, NFR6.1, NFR6.3, NFR6.6): the live channel client with
 * Jest's fake clock and a fake `fetch`; no real network.
 */
const URL_ = 'http://api.test/api/v1/stream/dashboard';
const HEARTBEAT = sseBlock('heartbeat', { at: '2026-10-28T12:00:15Z' }, 'b1.l2.s3');

function setup(overrides: Partial<StreamClientOptions> = {}) {
  const clock = startFakeClock();
  const net = fakeFetch();
  const events: DashboardEvent[] = [];
  const statuses: ChannelStatus[] = [];
  const session = { token: 'token-one' as string | null };
  let correlation = 0;
  const renew = jest.fn(async () => {
    session.token = 'token-two';
    return true;
  });
  const onSessionExpired = jest.fn();
  const client = createStreamClient({
    url: URL_,
    getToken: () => session.token,
    renew,
    onEvent: (event) => events.push(event),
    onStatus: (status) => statuses.push(status),
    onSessionExpired,
    fetchImpl: net.impl,
    newId: () => `corr-${++correlation}`,
    ...overrides,
  });
  const state = () => statuses[statuses.length - 1]?.state;
  return { clock, net, events, statuses, session, renew, onSessionExpired, client, state };
}

afterEach(() => {
  jest.useRealTimers();
  jest.restoreAllMocks();
});

describe('createStreamClient', () => {
  it('opens with fetch, the token only in Authorization and the right headers', async () => {
    const t = setup();
    t.net.respond(fakeChannel().response);

    t.client.start();
    await settle();

    const [request] = t.net.requests;
    expect(request?.url).toBe(URL_);
    expect(request?.url).not.toContain('token');
    expect(request?.headers).toEqual({
      Accept: 'text/event-stream',
      'X-Correlation-Id': 'corr-1',
      Authorization: 'Bearer token-one',
    });
    expect(request?.init.credentials).toBe('omit');
  });

  it('is OPEN on the 200 answer, before any event', async () => {
    const t = setup();
    t.net.respond(fakeChannel().response);

    t.client.start();
    expect(t.state()).toBe('CONNECTING');
    await settle();

    expect(t.statuses.at(-1)).toEqual({ state: 'OPEN', outdated: false });
    expect(t.events).toEqual([]);
  });

  it('is STALE after 60 s without any event and OPEN again on the next one', async () => {
    const t = setup();
    const channel = fakeChannel();
    t.net.respond(channel.response);
    t.client.start();
    await settle();

    await t.clock.advance(STALE_AFTER_MS - 1);
    expect(t.state()).toBe('OPEN');
    await t.clock.advance(1);
    expect(t.statuses.at(-1)).toEqual({ state: 'STALE', outdated: true });

    channel.push(HEARTBEAT);
    await settle();
    expect(t.statuses.at(-1)).toEqual({ state: 'OPEN', outdated: false });
    expect(t.events.map((event) => event.kind)).toEqual(['heartbeat']);
  });

  it('every event restarts the 60 s timer', async () => {
    const t = setup();
    const channel = fakeChannel();
    t.net.respond(channel.response);
    t.client.start();
    await settle();

    await t.clock.advance(50_000);
    channel.push(HEARTBEAT);
    await settle();
    await t.clock.advance(50_000);

    expect(t.state()).toBe('OPEN');
  });

  it('waits 2, 4, 8, 16 and then 30 s between attempts, and 2 s again after an opening', async () => {
    const t = setup();
    for (let i = 0; i < 7; i++) {
      t.net.respond(new TypeError('offline'));
    }
    jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    t.client.start();
    await settle();
    expect(t.statuses.at(-1)).toEqual({ state: 'WAITING', outdated: true });

    for (const wait of [...BACKOFF_MS, 30_000]) {
      const before = t.net.requests.length;
      await t.clock.advance(wait - 1);
      expect(t.net.requests).toHaveLength(before);
      await t.clock.advance(1);
      expect(t.net.requests).toHaveLength(before + 1);
    }

    const channel = fakeChannel();
    t.net.respond(channel.response);
    await t.clock.advance(30_000);
    expect(t.state()).toBe('OPEN');
    channel.end();
    await settle();
    const before = t.net.requests.length;
    await t.clock.advance(BACKOFF_MS[0] as number);
    expect(t.net.requests).toHaveLength(before + 1);
  });

  it('a 429 waits at least its Retry-After', async () => {
    const t = setup();
    t.net.respond(response(429, { 'Retry-After': '30' }));
    t.client.start();
    await settle();

    await t.clock.advance(29_999);
    expect(t.net.requests).toHaveLength(1);
    await t.clock.advance(1);
    expect(t.net.requests).toHaveLength(2);
  });

  it('auth.expired renews the token once and reopens with Last-Event-ID, without the outdated notice', async () => {
    const t = setup();
    const channel = fakeChannel();
    t.net.respond(channel.response, fakeChannel().response);
    t.client.start();
    await settle();

    channel.push(
      HEARTBEAT + sseBlock('auth.expired', { expiredAt: '2026-10-28T12:15:00Z' }, 'b1.l2.s4'),
    );
    await settle();

    expect(t.renew).toHaveBeenCalledTimes(1);
    expect(t.statuses.some((status) => status.state === 'RENEWING' && !status.outdated)).toBe(true);
    expect(t.net.requests[1]?.headers).toMatchObject({
      Authorization: 'Bearer token-two',
      'Last-Event-ID': 'b1.l2.s4',
    });
    expect(t.events.map((event) => event.kind)).toEqual(['heartbeat']);
    expect(t.state()).toBe('OPEN');
  });

  it('a 401 at the opening renews the token and opens again', async () => {
    const t = setup();
    t.net.respond(response(401), fakeChannel().response);

    t.client.start();
    await settle();

    expect(t.renew).toHaveBeenCalledTimes(1);
    expect(t.net.requests[1]?.headers.Authorization).toBe('Bearer token-two');
    expect(t.state()).toBe('OPEN');
    expect(t.onSessionExpired).not.toHaveBeenCalled();
  });

  it('a failed renewal closes the channel for good and starts the session-expired flow', async () => {
    const t = setup({ renew: jest.fn(async () => false) });
    t.net.respond(response(401));

    t.client.start();
    await settle();
    await t.clock.advance(120_000);

    expect(t.onSessionExpired).toHaveBeenCalledTimes(1);
    expect(t.state()).toBe('CLOSED');
    expect(t.net.requests).toHaveLength(1);
  });

  it('a renewal that throws is a failed renewal too', async () => {
    const t = setup({
      renew: jest.fn(async () => {
        throw new Error('refresh failed');
      }),
    });
    t.net.respond(response(401));

    t.client.start();
    await settle();

    expect(t.onSessionExpired).toHaveBeenCalledTimes(1);
    expect(t.state()).toBe('CLOSED');
  });

  it('a 401 again right after the renewal closes without new attempts', async () => {
    const t = setup();
    t.net.respond(response(401), response(401));

    t.client.start();
    await settle();
    await t.clock.advance(120_000);

    expect(t.renew).toHaveBeenCalledTimes(1);
    expect(t.onSessionExpired).toHaveBeenCalledTimes(1);
    expect(t.net.requests).toHaveLength(2);
  });

  it('a renewal slower than 10 s shows the outdated notice', async () => {
    const t = setup({ renew: () => new Promise<boolean>(() => undefined) });
    t.net.respond(response(401));
    t.client.start();
    await settle();
    expect(t.statuses.at(-1)).toEqual({ state: 'RENEWING', outdated: false });

    await t.clock.advance(10_000);

    expect(t.statuses.at(-1)).toEqual({ state: 'RENEWING', outdated: true });
  });

  it('discards an event outside the contract without closing the channel', async () => {
    const t = setup();
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    const channel = fakeChannel();
    t.net.respond(channel.response);
    t.client.start();
    await settle();

    channel.push(
      sseBlock('bot.state', '{not json', 'b9.l9.s9') +
        sseBlock('bot.state', { items: [{ botId: 1 }], activeCount: 0 }) +
        sseBlock('surprise', { x: 1 }) +
        sseBlock('bot.state', {
          items: [botSummary({ extra: 'dropped' } as never)],
          activeCount: 1,
        }),
    );
    await settle();

    expect(t.events).toHaveLength(1);
    expect(t.events[0]).toEqual({
      kind: 'bot.state',
      data: { items: [botSummary()], activeCount: 1 },
    });
    expect(t.state()).toBe('OPEN');
    expect(warn.mock.calls.map((call) => call.join(' '))).toEqual([
      'Dashboard live channel: bot.state INVALID_ENVELOPE',
      'Dashboard live channel: bot.state INVALID_ENVELOPE',
      'Dashboard live channel: unknown UNKNOWN_EVENT',
    ]);
  });

  it('never writes the token, the wallet, the event id or the data to the console', async () => {
    const t = setup();
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    const error = jest.spyOn(console, 'error').mockImplementation(() => undefined);
    const channel = fakeChannel();
    t.net.respond(channel.response, new TypeError('offline'));
    t.client.start();
    await settle();

    channel.push(sseBlock('data.status', { items: [{ wallet: 'TestW…1111' }] }, 'b7.l8.s9'));
    channel.push(`data: ${'x'.repeat(1024 * 1024 + 10)}`);
    await settle();
    await t.clock.advance(BACKOFF_MS[0] as number);

    const printed = [...warn.mock.calls, ...error.mock.calls].flat().map(String).join('\n');
    expect(printed).toContain('OVERSIZED_EVENT');
    expect(printed).toContain('NETWORK');
    for (const secret of ['token-one', 'Bearer', 'TestW', 'b7.l8.s9', 'wallet', 'xxxx']) {
      expect(printed).not.toContain(secret);
    }
    expect(channel.cancelled).toBe(true);
  });

  it('close() cancels the reading and leaves no attempt scheduled', async () => {
    const t = setup();
    jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    t.net.respond(new TypeError('offline'));
    t.client.start();
    await settle();
    expect(t.state()).toBe('WAITING');

    t.client.close();
    await t.clock.advance(120_000);

    expect(t.state()).toBe('CLOSED');
    expect(t.net.requests).toHaveLength(1);
    expect(jest.getTimerCount()).toBe(0);
  });

  it('a hidden tab neither closes nor reopens the channel', async () => {
    const t = setup();
    t.net.respond(fakeChannel().response);
    t.client.start();
    await settle();
    const seen = t.statuses.length;

    Object.defineProperty(document, 'hidden', { configurable: true, value: true });
    document.dispatchEvent(new Event('visibilitychange'));
    await t.clock.advance(1_000);

    expect(t.statuses).toHaveLength(seen);
    expect(t.net.requests).toHaveLength(1);
    Object.defineProperty(document, 'hidden', { configurable: true, value: false });
  });

  it('sends a fresh X-Correlation-Id on every opening and "Tentar de novo" opens at once', async () => {
    const t = setup();
    jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    t.net.respond(new TypeError('offline'), fakeChannel().response);
    t.client.start();
    await settle();

    t.client.retryNow();
    await settle();

    expect(t.net.requests.map((request) => request.headers['X-Correlation-Id'])).toEqual([
      'corr-1',
      'corr-2',
    ]);
    expect(t.statuses.some((status) => status.state === 'CONNECTING' && status.outdated)).toBe(
      true,
    );
    expect(t.state()).toBe('OPEN');
  });

  it('a 200 that is not an event stream, or the end of the body, is a drop', async () => {
    const t = setup();
    const channel = fakeChannel();
    t.net.respond(response(200, { 'Content-Type': 'application/json' }), channel.response);
    t.client.start();
    await settle();
    expect(t.state()).toBe('WAITING');

    await t.clock.advance(BACKOFF_MS[0] as number);
    expect(t.state()).toBe('OPEN');
    channel.end();
    await settle();

    expect(t.state()).toBe('WAITING');
  });

  it('a reading that fails is a drop and start() twice opens one channel', async () => {
    const t = setup();
    jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    const channel = fakeChannel();
    t.net.respond(channel.response);
    t.client.start();
    t.client.start();
    await settle();

    channel.fail();
    await settle();

    expect(t.net.requests).toHaveLength(1);
    expect(t.state()).toBe('WAITING');
  });
});
