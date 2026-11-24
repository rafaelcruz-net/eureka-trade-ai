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
  /**
   * 261121-paper-real-unico U3 (BR7.5): the cards by mode listen here to the events that change the result of their
   * mode; the returned function stops listening.
   */
  subscribeModeCards(listener: ModeCardListener): () => void;
}

/** A card by mode to read again, because an event of its mode arrived (or the channel came back). */
export type ModeCardListener = (market: 'PAPER' | 'REAL') => void;

/**
 * BR7.5: `real.order.state` changes the REAL result only when the order ended on the network (CONFIRMED or FAILED,
 * with its sale, buy or fee); the other states and `real.signing.requested` never read the card again.
 */
const REAL_FINAL_STATES: readonly string[] = ['CONFIRMED', 'FAILED'];

const DashboardContext = createContext<DashboardContextValue | null>(null);

export function useDashboard(): DashboardContextValue {
  const value = useContext(DashboardContext);
  if (!value) {
    throw new Error('useDashboard must be used inside DashboardProvider');
  }
  return value;
}

/**
 * The kinds of the live channel the dashboard reads. 261121-paper-real-unico U3 (BR7.5): also `real.order.state` and
 * `real.conditional.state`, for the card "Resultado real" (the channel does not change).
 */
const DASHBOARD_KINDS: readonly LiveKind[] = [
  'bot.state',
  'trade.recorded',
  'pnl.updated',
  'data.status',
  'heartbeat',
  'real.order.state',
  'real.conditional.state',
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
  const modeCardListeners = useRef(new Set<ModeCardListener>());

  const notifyModeCards = useCallback((market: 'PAPER' | 'REAL') => {
    modeCardListeners.current.forEach((listener) => listener(market));
  }, []);

  const subscribeModeCards = useCallback((listener: ModeCardListener) => {
    modeCardListeners.current.add(listener);
    return () => {
      modeCardListeners.current.delete(listener);
    };
  }, []);

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
          // U3 (BR7.5): the PAPER ledger changed; the card "Resultado simulado" reads C4 again.
          notifyModeCards('PAPER');
          void loader.refreshReconciliation();
          return;
        case 'real.order.state':
          if (REAL_FINAL_STATES.includes(event.data.state)) {
            notifyModeCards('REAL');
          }
          return;
        case 'real.conditional.state':
          notifyModeCards('REAL');
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
    [announce, loader, notifyModeCards],
  );

  // F1: the three cards load and the subscriptions to the live channel start together once the session is known.
  useEffect(() => {
    if (status !== 'authenticated') {
      return undefined;
    }
    void loader.reloadBots(true);
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

  // 261121-paper-real-unico U3 (BR2.8, BR7.1): the cards by mode are "desde o início"; nothing is read at 00:00 UTC.

  useBotAnnouncements(state, announce, announcedByPause.current);
  useSourceAnnouncements(state, announce, timeZone);

  const retryChannel = useCallback(() => {
    retryNow();
    void loader.reloadBots();
    void loader.refreshReconciliation();
    notifyModeCards('PAPER');
    notifyModeCards('REAL');
  }, [loader, notifyModeCards, retryNow]);

  const value = useMemo<DashboardContextValue>(
    () => ({
      state,
      dispatch,
      loader,
      now: () => clock.current(),
      timeZone,
      retryChannel,
      announcedByPause: announcedByPause.current,
      subscribeModeCards,
    }),
    [state, loader, timeZone, retryChannel, subscribeModeCards],
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
