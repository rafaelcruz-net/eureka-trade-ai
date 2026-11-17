'use client';

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { usePathname } from 'next/navigation';
import { useApi, useApiOrigin } from '@/lib/api/ApiProvider';
import { tokenStore } from '@/lib/auth/token-store';
import { useSession } from '@/features/auth/SessionProvider';
import { useAnnounce } from '@/components/shell/NoticesRegion';
import type { DashboardEvent } from '@/features/dashboard/envelopes';
import { dashboardMessages } from '@/features/dashboard/messages';
import { channelRenewal } from '@/features/dashboard/channel-renewal';
import {
  createStreamClient,
  STREAM_PATH,
  type ChannelStatus,
  type StreamClient,
} from '@/features/dashboard/stream-client';
import { REACCEPTANCE_PATH } from '@/features/reacceptance/return-path';

/**
 * 261113-trading-real-bot U6 (logical-components.md "Canal ao vivo no layout raiz"; BR3.5, U6-NFR4.1): ONE live channel
 * C7 per person, mounted in the root layout inside the SessionProvider, so it survives the navigations of the App
 * Router. The dashboard and the signing banner subscribe to the kinds they need; neither opens a connection of its own.
 *
 * - It opens only with a session, never on the screens without one (`/entrar`, `/convite`, `/termos`, `/privacidade`)
 *   nor on `/novo-aceite`, and stays closed while the reacceptance of the terms is pending.
 * - A `403 TERMS_REACCEPTANCE_REQUIRED` at the opening closes it and marks the reacceptance pending; it goes to the
 *   global handler of the new acceptance except on the screen of deposit and withdrawal, which stays open (BR1.4).
 * - Every reconnection after a loss calls the `onReconnected` handlers (the banner reads the session again).
 */
export type LiveKind = DashboardEvent['kind'];
export type LiveEventOf<K extends LiveKind> = Extract<DashboardEvent, { kind: K }>;

export interface LiveChannelValue {
  status: ChannelStatus;
  /** "Tentar de novo": reopens the channel now. */
  retryNow(): void;
  subscribe<K extends LiveKind>(kind: K, handler: (event: LiveEventOf<K>) => void): () => void;
  onReconnected(handler: () => void): () => void;
  /**
   * A screen that needs the live events asks for the channel and gives it back when it leaves; the channel is open only
   * while someone asks (the dashboard, or the signing banner in the SIGNED mode). Opening it on every screen held one
   * server slot per screen and, with the limit of 5 channels per person, a new screen could get 429 (run 44014735480).
   */
  acquire(): () => void;
  /** The reacceptance of the terms is pending: the channel stays closed and the banner reads nothing. */
  termsPending: boolean;
  markTermsPending(): void;
}

/** The screens without a session, and the new acceptance itself, never open the channel. */
export const NO_CHANNEL_PATHS = [
  '/entrar',
  '/convite',
  '/termos',
  '/privacidade',
  REACCEPTANCE_PATH,
];
/** BR1.4: the screen that stays open without the new acceptance. */
export const WITHDRAWAL_PATH = '/real/deposito-saque';

const CLOSED: ChannelStatus = { state: 'CLOSED', outdated: false };

/** Outside the provider (screens rendered alone in tests) the channel is closed and nothing is ever delivered. */
const INERT: LiveChannelValue = {
  status: CLOSED,
  retryNow: () => undefined,
  subscribe: () => () => undefined,
  onReconnected: () => () => undefined,
  acquire: () => () => undefined,
  termsPending: false,
  markTermsPending: () => undefined,
};

const LiveChannelContext = createContext<LiveChannelValue>(INERT);

export function useLiveChannel(): LiveChannelValue {
  return useContext(LiveChannelContext);
}

function isNoChannelPath(pathname: string): boolean {
  return NO_CHANNEL_PATHS.some((path) => pathname === path || pathname.startsWith(`${path}/`));
}

type Handler = (event: DashboardEvent) => void;

export function LiveChannelProvider({
  children,
  fetchImpl,
}: {
  children: ReactNode;
  /** Tests give a fake fetch for the channel; the REST calls go through the API of the context. */
  fetchImpl?: typeof fetch;
}) {
  const api = useApi();
  const apiOrigin = useApiOrigin();
  const { status: session, expire } = useSession();
  const pathname = usePathname() ?? '/';
  const [status, setStatus] = useState<ChannelStatus>(CLOSED);
  const [termsPending, setTermsPending] = useState(false);
  const [demand, setDemand] = useState(0);
  const handlers = useRef(new Map<LiveKind, Set<Handler>>());
  const reconnected = useRef(new Set<() => void>());
  const client = useRef<StreamClient | null>(null);
  const pathRef = useRef(pathname);
  pathRef.current = pathname;
  const lost = useRef(false);
  const everOpen = useRef(false);

  const blockedPath = isNoChannelPath(pathname);
  const shouldOpen = session === 'authenticated' && !blockedPath && !termsPending && demand > 0;

  // Leaving the new acceptance is the end of the pending state: the next opening tells again.
  const wasOnReacceptance = useRef(false);
  useEffect(() => {
    const onIt = pathname === REACCEPTANCE_PATH;
    if (wasOnReacceptance.current && !onIt) {
      setTermsPending(false);
    }
    wasOnReacceptance.current = onIt;
  }, [pathname]);

  useEffect(() => {
    if (session !== 'authenticated') {
      setTermsPending(false);
    }
  }, [session]);

  // `data.status` is a snapshot sent when the channel opens and when a source changes. A screen that subscribes after
  // the channel is already open (the dashboard reached from another screen) gets the last one at once, as it did when
  // the dashboard opened its own channel.
  const lastSnapshot = useRef<DashboardEvent | null>(null);

  const deliver = useCallback((event: DashboardEvent) => {
    if (event.kind === 'data.status') {
      lastSnapshot.current = event;
    }
    handlers.current.get(event.kind)?.forEach((handler) => handler(event));
  }, []);

  const onStatus = useCallback((next: ChannelStatus) => {
    setStatus(next);
    if (next.state === 'OPEN') {
      if (lost.current && everOpen.current) {
        reconnected.current.forEach((handler) => handler());
      }
      lost.current = false;
      everOpen.current = true;
    } else if (everOpen.current && next.state !== 'CONNECTING' && next.state !== 'CLOSED') {
      lost.current = true;
    }
  }, []);

  useEffect(() => {
    if (!shouldOpen) {
      return undefined;
    }
    lost.current = false;
    everOpen.current = false;
    const stream = createStreamClient({
      url: `${apiOrigin}${STREAM_PATH}`,
      getToken: () => tokenStore.get(),
      renew: channelRenewal(api),
      onEvent: deliver,
      onStatus,
      onSessionExpired: expire,
      // u3-reacceptance (NFR2.4) and U6 (BR1.4): the same handler of the REST calls, except on the withdrawal screen.
      onTermsReacceptance: () => {
        setTermsPending(true);
        if (pathRef.current !== WITHDRAWAL_PATH) {
          api.notifyTermsReacceptance();
        }
      },
      fetchImpl,
    });
    client.current = stream;
    stream.start();
    return () => {
      stream.close();
      client.current = null;
      lastSnapshot.current = null;
      setStatus(CLOSED);
    };
  }, [shouldOpen, api, apiOrigin, expire, fetchImpl, deliver, onStatus]);

  const subscribe = useCallback(
    <K extends LiveKind>(kind: K, handler: (event: LiveEventOf<K>) => void) => {
      const set = handlers.current.get(kind) ?? new Set<Handler>();
      handlers.current.set(kind, set);
      const wrapped = handler as Handler;
      set.add(wrapped);
      const snapshot = lastSnapshot.current;
      if (kind === 'data.status' && snapshot !== null) {
        queueMicrotask(() => {
          if (set.has(wrapped)) {
            wrapped(snapshot);
          }
        });
      }
      return () => {
        set.delete(wrapped);
      };
    },
    [],
  );

  const onReconnected = useCallback((handler: () => void) => {
    reconnected.current.add(handler);
    return () => {
      reconnected.current.delete(handler);
    };
  }, []);

  const acquire = useCallback(() => {
    setDemand((count) => count + 1);
    let released = false;
    return () => {
      if (!released) {
        released = true;
        setDemand((count) => count - 1);
      }
    };
  }, []);

  const retryNow = useCallback(() => client.current?.retryNow(), []);
  const markTermsPending = useCallback(() => setTermsPending(true), []);

  const value = useMemo<LiveChannelValue>(
    () => ({
      status,
      retryNow,
      subscribe,
      onReconnected,
      acquire,
      termsPending,
      markTermsPending,
    }),
    [status, retryNow, subscribe, onReconnected, acquire, termsPending, markTermsPending],
  );
  return <LiveChannelContext.Provider value={value}>{children}</LiveChannelContext.Provider>;
}

/**
 * BR7.1 of the U9, moved here by the U6: the loss and the return of the automatic update are announced in the "Avisos"
 * region of the shell, on every screen with a session (heartbeats and full reads never are). Rendered by the AppShell.
 */
export function LiveChannelAnnouncer() {
  const { status } = useLiveChannel();
  const announce = useAnnounce();
  const lostAnnounced = useRef(false);
  useEffect(() => {
    const { state, outdated } = status;
    if (outdated && (state === 'WAITING' || state === 'STALE') && !lostAnnounced.current) {
      lostAnnounced.current = true;
      announce(dashboardMessages.notices.lost);
    } else if (state === 'OPEN' && lostAnnounced.current) {
      lostAnnounced.current = false;
      announce(dashboardMessages.notices.resumed);
    }
  }, [status, announce]);
  return null;
}
