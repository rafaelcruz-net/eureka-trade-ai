import { act, render, screen } from '@testing-library/react';
import { useEffect, useState } from 'react';
import { tokenStore } from '@/lib/auth/token-store';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { useSession } from '@/features/auth/SessionProvider';
import { AppShell } from '@/components/shell/AppShell';
import { resetWalletCache } from '@/components/shell/WalletBadge';
import { JOIN_WINDOW_MS } from '@/components/shell/NoticesRegion';
import { dashboardMessages } from '@/features/dashboard/messages';
import { BACKOFF_MS } from '@/features/dashboard/stream-client';
import {
  fakeChannel,
  fakeFetch,
  response,
  settle,
  sourceStatus,
  sseBlock,
  startFakeClock,
} from '@/features/dashboard/test-support';
import {
  LiveChannelProvider,
  useLiveChannel,
  type LiveChannelValue,
  type LiveKind,
} from '@/features/live/LiveChannelProvider';

/**
 * 261113-trading-real-bot U6, Step 6, written after the code (logical-components.md "Canal ao vivo no layout raiz";
 * BR3.5, BR1.4, BR5.6 of the U9 as changed by the U6; U6-NFR4.1): ONE channel per person in the root layout, opened
 * only with a session and outside the screens without one; subscriptions per kind; reconnection announced to the
 * subscribers; the reacceptance of the terms keeps it closed.
 */
let pathname = '/';
jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => pathname,
}));

const SIGNING = {
  type: 'REAL_SIGNING_REQUESTED',
  realOrderId: '0dde0000-0000-4000-8000-0000000000a1',
  deadline: '2026-11-16T12:00:10Z',
};

let latest: LiveChannelValue | null = null;

/** The 403 of the reacceptance lock at the opening of the channel. */
function lockedResponse(): Response {
  const locked = response(403, {}, null) as unknown as { json: () => Promise<unknown> };
  locked.json = async () => ({ code: 'TERMS_REACCEPTANCE_REQUIRED' });
  return locked as unknown as Response;
}

function Probe() {
  latest = useLiveChannel();
  return <span data-testid="probe-state">{latest.status.state}</span>;
}

function Subscriber({ kind, onEvent }: { kind: LiveKind; onEvent: (data: unknown) => void }) {
  const { subscribe } = useLiveChannel();
  useEffect(() => subscribe(kind, (event) => onEvent(event.data)), [subscribe, kind, onEvent]);
  return null;
}

function renderChannel(children: React.ReactNode, net = fakeFetch(), api = fakeApi()) {
  const view = render(
    <Providers api={api}>
      <LiveChannelProvider fetchImpl={net.impl}>
        <Probe />
        {children}
      </LiveChannelProvider>
    </Providers>,
  );
  return { ...view, net, api };
}

beforeEach(() => {
  pathname = '/';
  latest = null;
  resetWalletCache();
});

afterEach(() => {
  jest.useRealTimers();
  tokenStore.clear();
  jest.restoreAllMocks();
});

describe('LiveChannelProvider', () => {
  it('opens ONE connection with a session and delivers each event to the subscribers of its kind', async () => {
    tokenStore.set('access-token-value');
    const channel = fakeChannel();
    const net = fakeFetch();
    net.respond(channel.response);
    const signing = jest.fn();
    const bots = jest.fn();
    renderChannel(
      <>
        <Subscriber kind="real.signing.requested" onEvent={signing} />
        <Subscriber kind="bot.state" onEvent={bots} />
      </>,
      net,
    );
    await act(async () => settle());

    await act(async () => channel.push(sseBlock('real.signing.requested', SIGNING)));

    expect(net.requests).toHaveLength(1);
    expect(net.requests[0]?.headers.Authorization).toBe('Bearer access-token-value');
    expect(screen.getByTestId('probe-state')).toHaveTextContent('OPEN');
    expect(signing).toHaveBeenCalledWith(SIGNING);
    expect(bots).not.toHaveBeenCalled();
  });

  it('a data.status subscriber that arrives after the channel opened gets the last snapshot at once (regression of run 44009850490)', async () => {
    tokenStore.set('access-token-value');
    const channel = fakeChannel();
    const net = fakeFetch();
    net.respond(channel.response);
    const late = jest.fn();
    function Page() {
      const [dashboardOn, setDashboardOn] = useState(false);
      return (
        <>
          {dashboardOn && <Subscriber kind="data.status" onEvent={late} />}
          <button type="button" onClick={() => setDashboardOn(true)}>
            abrir o painel
          </button>
        </>
      );
    }
    renderChannel(<Page />, net);
    await act(async () => settle());
    const snapshot = { items: [sourceStatus()] };
    await act(async () => channel.push(sseBlock('data.status', snapshot)));
    expect(late).not.toHaveBeenCalled();

    await act(async () => screen.getByRole('button', { name: 'abrir o painel' }).click());
    await act(async () => settle());

    expect(late).toHaveBeenCalledTimes(1);
    expect(late).toHaveBeenCalledWith(snapshot);
    expect(net.requests).toHaveLength(1);
  });

  it('never opens without a session', async () => {
    const net = fakeFetch();
    renderChannel(null, net);
    await act(async () => settle());

    expect(net.requests).toHaveLength(0);
    expect(screen.getByTestId('probe-state')).toHaveTextContent('CLOSED');
  });

  it.each(['/entrar', '/convite/abc', '/termos', '/privacidade', '/novo-aceite'])(
    'never opens on %s',
    async (path) => {
      pathname = path;
      tokenStore.set('access-token-value');
      const net = fakeFetch();
      renderChannel(null, net);
      await act(async () => settle());

      expect(net.requests).toHaveLength(0);
    },
  );

  it('keeps the same connection across the navigations of the App Router', async () => {
    tokenStore.set('access-token-value');
    const channel = fakeChannel();
    const net = fakeFetch();
    net.respond(channel.response);
    const { rerender, api } = renderChannel(null, net);
    await act(async () => settle());

    for (const path of ['/real', '/bots', '/real/deposito-saque']) {
      pathname = path;
      rerender(
        <Providers api={api}>
          <LiveChannelProvider fetchImpl={net.impl}>
            <Probe />
          </LiveChannelProvider>
        </Providers>,
      );
      await act(async () => settle());
    }

    expect(net.requests).toHaveLength(1);
    expect(net.requests[0]?.init.signal?.aborted).toBe(false);
  });

  it('a subscriber that leaves stops receiving while the channel stays open for the others (BR5.6 of the U9)', async () => {
    tokenStore.set('access-token-value');
    const channel = fakeChannel();
    const net = fakeFetch();
    net.respond(channel.response);
    const dashboard = jest.fn();
    const banner = jest.fn();
    function Page() {
      const [dashboardOn, setDashboardOn] = useState(true);
      return (
        <>
          {dashboardOn && <Subscriber kind="real.signing.requested" onEvent={dashboard} />}
          <Subscriber kind="real.signing.requested" onEvent={banner} />
          <button type="button" onClick={() => setDashboardOn(false)}>
            sair do painel
          </button>
        </>
      );
    }
    renderChannel(<Page />, net);
    await act(async () => settle());

    act(() => screen.getByRole('button', { name: 'sair do painel' }).click());
    await act(async () => channel.push(sseBlock('real.signing.requested', SIGNING)));

    expect(dashboard).not.toHaveBeenCalled();
    expect(banner).toHaveBeenCalledTimes(1);
    expect(net.requests[0]?.init.signal?.aborted).toBe(false);
    expect(net.requests).toHaveLength(1);
  });

  it('calls onReconnected after a drop and the new opening, never on the first opening', async () => {
    const clock = startFakeClock();
    tokenStore.set('access-token-value');
    const first = fakeChannel();
    const second = fakeChannel();
    const net = fakeFetch();
    net.respond(first.response, second.response);
    const reconnected = jest.fn();
    function Listener() {
      const { onReconnected } = useLiveChannel();
      useEffect(() => onReconnected(reconnected), [onReconnected]);
      return null;
    }
    renderChannel(<Listener />, net);
    await act(async () => settle());
    expect(reconnected).not.toHaveBeenCalled();

    await act(async () => first.end());
    await act(async () => clock.advance(BACKOFF_MS[0] as number));
    await act(async () => settle());

    expect(net.requests).toHaveLength(2);
    expect(reconnected).toHaveBeenCalledTimes(1);
  });

  it('"Tentar de novo" reopens at once', async () => {
    startFakeClock();
    tokenStore.set('access-token-value');
    const first = fakeChannel();
    const net = fakeFetch();
    net.respond(first.response, fakeChannel().response);
    renderChannel(null, net);
    await act(async () => settle());
    await act(async () => first.end());

    await act(async () => latest?.retryNow());
    await act(async () => settle());

    expect(net.requests).toHaveLength(2);
  });

  it('a 403 TERMS_REACCEPTANCE_REQUIRED closes it, marks the reacceptance pending and calls the global handler', async () => {
    tokenStore.set('access-token-value');
    const net = fakeFetch();
    net.respond(lockedResponse());
    const api = fakeApi();
    renderChannel(null, net, api);
    await act(async () => settle());

    expect(latest?.termsPending).toBe(true);
    expect(screen.getByTestId('probe-state')).toHaveTextContent('CLOSED');
    expect(api.notifyTermsReacceptance).toHaveBeenCalledTimes(1);
    expect(net.requests).toHaveLength(1);
  });

  it('on the withdrawal screen the lock never calls the global handler (BR1.4, U6-NFR4.5)', async () => {
    pathname = '/real/deposito-saque';
    tokenStore.set('access-token-value');
    const net = fakeFetch();
    net.respond(lockedResponse());
    const api = fakeApi();
    renderChannel(null, net, api);
    await act(async () => settle());

    expect(latest?.termsPending).toBe(true);
    expect(api.notifyTermsReacceptance).not.toHaveBeenCalled();
  });

  it('a pending reacceptance closes the channel, and leaving /novo-aceite opens it again', async () => {
    tokenStore.set('access-token-value');
    const first = fakeChannel();
    const net = fakeFetch();
    net.respond(first.response, fakeChannel().response);
    const { rerender, api } = renderChannel(null, net);
    await act(async () => settle());

    act(() => latest?.markTermsPending());
    await act(async () => settle());
    expect(net.requests[0]?.init.signal?.aborted).toBe(true);
    expect(screen.getByTestId('probe-state')).toHaveTextContent('CLOSED');

    for (const path of ['/novo-aceite', '/real']) {
      pathname = path;
      rerender(
        <Providers api={api}>
          <LiveChannelProvider fetchImpl={net.impl}>
            <Probe />
          </LiveChannelProvider>
        </Providers>,
      );
      await act(async () => settle());
    }

    expect(latest?.termsPending).toBe(false);
    expect(net.requests).toHaveLength(2);
  });

  it('an invalid REAL event is discarded and the channel stays open', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    tokenStore.set('access-token-value');
    const channel = fakeChannel();
    const net = fakeFetch();
    net.respond(channel.response);
    const signing = jest.fn();
    const states = jest.fn();
    renderChannel(
      <>
        <Subscriber kind="real.signing.requested" onEvent={signing} />
        <Subscriber kind="real.order.state" onEvent={states} />
      </>,
      net,
    );
    await act(async () => settle());

    await act(async () =>
      channel.push(
        sseBlock('real.signing.requested', { ...SIGNING, type: 'OTHER' }) +
          sseBlock('real.order.state', {
            type: 'REAL_ORDER_STATE',
            realOrderId: SIGNING.realOrderId,
            state: 'CONFIRMED',
            at: '2026-11-16T12:00:05Z',
          }),
      ),
    );

    expect(signing).not.toHaveBeenCalled();
    expect(states).toHaveBeenCalledWith(expect.objectContaining({ state: 'CONFIRMED' }));
    expect(warn).toHaveBeenCalledWith(
      'Dashboard live channel: real.signing.requested INVALID_ENVELOPE',
    );
    expect(screen.getByTestId('probe-state')).toHaveTextContent('OPEN');
  });

  it('the shell announces the loss and the return of the channel once each', async () => {
    const clock = startFakeClock();
    jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    tokenStore.set('access-token-value');
    const first = fakeChannel();
    const net = fakeFetch();
    net.respond(first.response, fakeChannel().response);
    renderChannel(
      <AppShell current="dashboard" titleId="t">
        <h1 id="t">Painel</h1>
      </AppShell>,
      net,
    );
    await act(async () => settle());

    await act(async () => first.end());
    await act(async () => clock.advance(JOIN_WINDOW_MS));
    expect(screen.getByTestId('app-notices-region')).toHaveTextContent(
      dashboardMessages.notices.lost,
    );
    await act(async () => clock.advance(BACKOFF_MS[0] as number));
    await act(async () => clock.advance(JOIN_WINDOW_MS));

    expect(screen.getByTestId('app-notices-region')).toHaveTextContent(
      dashboardMessages.notices.resumed,
    );
  });

  it('closes when the session ends', async () => {
    tokenStore.set('access-token-value');
    const channel = fakeChannel();
    const net = fakeFetch();
    net.respond(channel.response);
    let endSession: () => void = () => undefined;
    function Session() {
      endSession = useSession().expire;
      return null;
    }
    renderChannel(<Session />, net);
    await act(async () => settle());
    expect(screen.getByTestId('probe-state')).toHaveTextContent('OPEN');

    act(() => endSession());
    await act(async () => settle());

    expect(net.requests[0]?.init.signal?.aborted).toBe(true);
    expect(screen.getByTestId('probe-state')).toHaveTextContent('CLOSED');
    expect(net.requests).toHaveLength(1);
  });
});

describe('useLiveChannel outside the provider', () => {
  it('is closed and never delivers anything', () => {
    const received = jest.fn();
    render(<Subscriber kind="bot.state" onEvent={received} />);
    function Outside() {
      return <span data-testid="outside">{useLiveChannel().status.state}</span>;
    }
    render(<Outside />);

    expect(screen.getByTestId('outside')).toHaveTextContent('CLOSED');
    expect(received).not.toHaveBeenCalled();
  });
});
