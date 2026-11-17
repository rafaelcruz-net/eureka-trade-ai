'use client';

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useReducer,
  useRef,
  type Dispatch,
  type ReactNode,
} from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import { useSession } from '@/features/auth/SessionProvider';
import { reasonsText, stateText } from '@/features/bots/states';
import { useAnnounce } from '@/components/shell/NoticesRegion';
import { createDashboardLoader, type DashboardLoader } from '@/features/dashboard/dashboard-loader';
import {
  dashboardReducer,
  initialDashboardState,
  sourceNotices,
  type DashboardAction,
  type DashboardState,
} from '@/features/dashboard/dashboard-reducer';
import type { DashboardEvent } from '@/features/dashboard/envelopes';
import { dashboardMessages as text } from '@/features/dashboard/messages';
import { msUntilNextUtcMidnight } from '@/features/dashboard/period';
import { useLiveChannel, type LiveKind } from '@/features/live/LiveChannelProvider';
import { formatClock } from '@/lib/format/format';

/**
 * State and live channel of the dashboard S2 (frontend-components.md; BR1.2 to BR1.5, BR2.1, BR2.3, BR3.1 to BR3.4,
 * BR4.1, BR5.1 to BR5.6, BR7.1; NFR1.2, NFR3.1 to NFR3.4). The three cards load in parallel, each from its own routes
 * (no `Promise.all` between them), the live channel opens at the same time, and REST answers and events go through
 * the same reducer. Announcements go to the "Avisos" region of the shell only for what matters (BR7.1).
 *
 * 261113-trading-real-bot U6 (logical-components.md "Canal ao vivo no layout raiz"): the live channel is the ONE of the
 * root layout ({@link useLiveChannel}). The dashboard subscribes to its five kinds and reads the status of the channel;
 * leaving the screen cancels only its subscriptions (BR5.6 as changed by the U6), and the connection stays for the
 * signing banner. The loss and return of the channel are announced by the provider (LiveChannelAnnouncer).
 */
export const SOURCES_FALLBACK_MS = 5_000;
export const SOURCES_TICK_MS = 30_000;

export interface DashboardContextValue {
  state: DashboardState;
  dispatch: Dispatch<DashboardAction>;
  loader: DashboardLoader;
  now: () => Date;
  timeZone?: string;
  /** "Tentar de novo" of the outdated notice: reopens the channel now and reads the three cards again (BR5.3). */
  retryChannel(): void;
  /** Bot ids whose next state change is announced by "Pausar" itself, not by the list difference. */
  announcedByPause: Set<string>;
}

const DashboardContext = createContext<DashboardContextValue | null>(null);

export function useDashboard(): DashboardContextValue {
  const value = useContext(DashboardContext);
  if (!value) {
    throw new Error('useDashboard must be used inside DashboardProvider');
  }
  return value;
}

/** The kinds of the live channel the dashboard reads (the REAL events are for the signing banner). */
const DASHBOARD_KINDS: readonly LiveKind[] = [
  'bot.state',
  'trade.recorded',
  'pnl.updated',
  'data.status',
  'heartbeat',
];

export function DashboardProvider({
  children,
  now = () => new Date(),
  timeZone,
}: {
  children: ReactNode;
  now?: () => Date;
  timeZone?: string;
}) {
  const api = useApi();
  const { subscribe, retryNow, acquire, status: channel } = useLiveChannel();
  const { status, expire } = useSession();
  const announce = useAnnounce();
  const [state, dispatch] = useReducer(dashboardReducer, undefined, () =>
    initialDashboardState(now()),
  );
  const clock = useRef(now);
  clock.current = now;
  const stateRef = useRef(state);
  stateRef.current = state;
  const announcedByPause = useRef(new Set<string>());
  const sourcesSeen = useRef(false);

  const loader = useMemo(
    () =>
      createDashboardLoader({
        api,
        dispatch,
        now: () => clock.current(),
        onUnauthenticated: expire,
      }),
    [api, expire],
  );

  const handleEvent = useCallback(
    (event: DashboardEvent) => {
      dispatch({ type: 'EVENT_RECEIVED', at: clock.current().toISOString() });
      switch (event.kind) {
        case 'bot.state':
          dispatch({ type: 'BOT_STATE_EVENT', data: event.data });
          void loader.reloadBots();
          return;
        case 'trade.recorded': {
          const newest = [...event.data.items].sort((a, b) =>
            b.createdAt.localeCompare(a.createdAt),
          )[0];
          if (newest) {
            const name = stateRef.current.bots.names[newest.botId] ?? text.lastTrade.unknownBot;
            announce(
              text.lastTrade.announced(name, text.lastTrade.sides[newest.side] ?? newest.side),
            );
          }
          void loader.tradeRecorded(event.data.items);
          return;
        }
        case 'pnl.updated':
          void loader.refreshPnl();
          void loader.refreshReconciliation();
          return;
        case 'data.status':
          sourcesSeen.current = true;
          dispatch({
            type: 'SOURCES_LOADED',
            items: event.data.items,
            at: clock.current().toISOString(),
          });
          return;
        default:
          return;
      }
    },
    [announce, loader],
  );

  // F1: the three cards load and the subscriptions to the live channel start together once the session is known.
  useEffect(() => {
    if (status !== 'authenticated') {
      return undefined;
    }
    void loader.reloadBots(true);
    void loader.refreshPnl(true);
    void loader.refreshReconciliation(true);
    const fallback = setTimeout(() => {
      if (!sourcesSeen.current) {
        void loader.loadSources();
      }
    }, SOURCES_FALLBACK_MS);
    const subscriptions = DASHBOARD_KINDS.map((kind) => subscribe(kind, handleEvent));
    const release = acquire();
    return () => {
      clearTimeout(fallback);
      // BR5.6: the dashboard ends its subscriptions and gives the channel back; it closes unless the signing banner
      // of the SIGNED mode still asks for it.
      subscriptions.forEach((unsubscribe) => unsubscribe());
      release();
    };
  }, [status, loader, subscribe, acquire, handleEvent]);

  // The status of the ONE channel of the root layout feeds the same reducer as before.
  const { state: channelState, outdated: channelOutdated } = channel;
  useEffect(() => {
    dispatch({ type: 'CHANNEL_STATUS', state: channelState, outdated: channelOutdated });
  }, [channelState, channelOutdated]);

  // BR4.1, NFR1.2: the age of the sources is checked every 30 s on the app clock, without a new call.
  useEffect(() => {
    const tick = setInterval(
      () => dispatch({ type: 'SOURCES_TICK', now: clock.current().toISOString() }),
      SOURCES_TICK_MS,
    );
    return () => clearInterval(tick);
  }, []);

  // BR3.1: at 00:00 UTC the period of the P&L is computed again and read again.
  useEffect(() => {
    if (status !== 'authenticated') {
      return undefined;
    }
    let timer: ReturnType<typeof setTimeout>;
    const schedule = () => {
      timer = setTimeout(() => {
        void loader.refreshPnl();
        schedule();
      }, msUntilNextUtcMidnight(clock.current()));
    };
    schedule();
    return () => clearTimeout(timer);
  }, [status, loader]);

  useBotAnnouncements(state, announce, announcedByPause.current);
  useSourceAnnouncements(state, announce, timeZone);

  const retryChannel = useCallback(() => {
    retryNow();
    void loader.reloadBots();
    void loader.refreshPnl();
    void loader.refreshReconciliation();
  }, [loader, retryNow]);

  const value = useMemo<DashboardContextValue>(
    () => ({
      state,
      dispatch,
      loader,
      now: () => clock.current(),
      timeZone,
      retryChannel,
      announcedByPause: announcedByPause.current,
    }),
    [state, loader, timeZone, retryChannel],
  );
  return <DashboardContext.Provider value={value}>{children}</DashboardContext.Provider>;
}

/** BR7.1: a bot that changed state against the previous list, with its new state and reasons in text. */
function useBotAnnouncements(
  state: DashboardState,
  announce: (text: string) => void,
  skip: Set<string>,
) {
  const previous = useRef<Map<string, string> | null>(null);
  useEffect(() => {
    if (!state.bots.loaded) {
      return;
    }
    const current = new Map(
      state.bots.inUse.map((bot) => [bot.botId, `${bot.state}:${bot.pauseReasons.join(',')}`]),
    );
    const before = previous.current;
    previous.current = current;
    if (!before) {
      return;
    }
    for (const bot of state.bots.inUse) {
      const was = before.get(bot.botId);
      if (was === undefined || was === current.get(bot.botId)) {
        continue;
      }
      if (skip.delete(bot.botId)) {
        continue;
      }
      announce(
        text.bots.stateChanged(
          bot.name,
          stateText(bot.state),
          reasonsText(bot.pauseReasons).join(', '),
        ),
      );
    }
  }, [state.bots.inUse, state.bots.loaded, announce, skip]);
}

/** BR7.1: a source that became late or came back. */
function useSourceAnnouncements(
  state: DashboardState,
  announce: (text: string) => void,
  timeZone: string | undefined,
) {
  const previous = useRef<Set<string> | null>(null);
  useEffect(() => {
    if (state.sources.status !== 'READY') {
      return;
    }
    const notices = sourceNotices(state.sources.items, state.sources.now);
    const late = new Set(notices.map((notice) => notice.name));
    const before = previous.current;
    previous.current = late;
    if (!before) {
      return;
    }
    for (const notice of notices) {
      if (before.has(notice.name)) {
        continue;
      }
      if (notice.kind === 'never' || notice.lastUpdatedAt === null) {
        announce(text.notices.sourceNever(notice.name));
      } else {
        const time = formatClock(new Date(notice.lastUpdatedAt), timeZone);
        announce(
          notice.kind === 'failed'
            ? text.notices.sourceFailed(notice.name, time)
            : text.notices.sourceStale(notice.name, time),
        );
      }
    }
    for (const name of before) {
      if (!late.has(name)) {
        announce(text.notices.sourceBack(name));
      }
    }
  }, [state.sources, announce, timeZone]);
}
