import type {
  PaperModeBlock,
  PaperTrade,
  RealModeBlock,
  ReconciliationResult,
} from '@/lib/api/guest-api';
import type {
  BotStateEnvelope,
  BotSummary,
  SourceStatus,
  TradeNotice,
} from '@/features/dashboard/envelopes';
import type { ChannelState } from '@/features/dashboard/stream-client';
import type { BlockLoad, RealEmptyReason } from '@/features/pnl/PnlModeBlock';

/**
 * The single state of the dashboard S2 (frontend-components.md "DashboardScreen e DashboardProvider"): REST answers
 * and live events go through the SAME reducer, so the newest data wins whatever arrives first (BR1.4). Pure; the
 * provider does the calls.
 *
 * - `bots`: list and counts ONLY from the full load by state (BR1.5); `bot.state` updates the bots already shown and
 *   never the counts; a `bot.state` that arrives while the card is loading waits and is applied after the answer.
 * - `lastTrade`: the order with the greatest `createdAt` among every bot, from `trades?limit=1` or `trade.recorded`,
 *   never replaced by an older one (BR2.3).
 * - `reconciliation`: a failed new read keeps the values already shown (BR1.2). 261121-paper-real-unico U3 (BR7.1):
 *   the P&L of the UTC day (PnlCard) left the dashboard; the two cards by mode have their own slice, `modeCards`,
 *   below.
 * - `sources`: the state of each market source and the clock tick used to age them (BR4.1).
 * - `channel`: what the live channel client reports, plus the time of the last success (BR4.2, BR5.2).
 * - `pendingPauses`: the Idempotency-Key of each "Pausar" in flight or failed, reused by "Tentar de novo" (BR2.2).
 */
export type CardStatus = 'LOADING' | 'READY' | 'EMPTY' | 'ERROR';
export type LineStatus = 'LOADING' | 'READY' | 'ERROR';
export type BotState = BotSummary['state'];

export const IN_USE: readonly BotState[] = ['ACTIVE', 'PAUSED', 'CLOSING_PENDING'];
export const ALL_STATES: readonly BotState[] = [
  'ACTIVE',
  'PAUSED',
  'CLOSING_PENDING',
  'DRAFT',
  'VALIDATED',
  'STOPPED',
];

export interface BotsSlice {
  status: CardStatus;
  inUse: BotSummary[];
  activeCount: number;
  activeLimit: number;
  pausedCount: number;
  draftCount: number;
  stoppedCount: number;
  draftToContinueId: string | null;
  /** Names of every bot of the full load, stopped ones included, for the last order line. */
  names: Record<string, string>;
  /** BR1.4: `bot.state` received while the card was loading. */
  queued: BotStateEnvelope[];
  /** A full load already succeeded once (a later failure keeps what is shown). */
  loaded: boolean;
}

export type LastTrade =
  | { source: 'full'; botId: string; trade: PaperTrade }
  | { source: 'notice'; botId: string; trade: TradeNotice };

/** An order read through `GET /bots/{botId}/trades`: PaperTrade has no botId, the call gives it. */
export interface BotTrade {
  botId: string;
  trade: PaperTrade;
}

export interface LastTradeSlice {
  status: LineStatus;
  trade: LastTrade | null;
}

export interface ReconciliationSlice {
  status: LineStatus;
  result: ReconciliationResult | null;
}

export interface SourcesSlice {
  status: LineStatus;
  items: SourceStatus[];
  /** Clock tick (ISO instant) of the last age check, every 30 s (BR4.1, NFR1.2). */
  now: string;
}

export interface ChannelSlice {
  state: ChannelState;
  outdated: boolean;
  /** Last event received (any kind, heartbeat included): "Atualizado às HH:MM" (BR5.2). */
  lastEventAt: string | null;
  /** Last event or successful answer (BR4.2). */
  lastSuccessAt: string | null;
}

export type PauseStatus = 'SENDING' | 'FAILED' | 'CONFLICT';

export interface PendingPause {
  idempotencyKey: string;
  status: PauseStatus;
}

export interface DashboardState {
  bots: BotsSlice;
  lastTrade: LastTradeSlice;
  reconciliation: ReconciliationSlice;
  sources: SourcesSlice;
  channel: ChannelSlice;
  pendingPauses: Record<string, PendingPause>;
}

export type BotLists = Record<BotState, BotSummary[]>;

export type DashboardAction =
  | { type: 'BOTS_LOADING' }
  | { type: 'BOTS_LOADED'; lists: BotLists; activeLimit: number; at: string }
  | { type: 'BOTS_FAILED' }
  | { type: 'BOT_STATE_EVENT'; data: BotStateEnvelope }
  | { type: 'BOT_REMOVED'; botId: string }
  | { type: 'LAST_TRADE_LOADING' }
  | { type: 'LAST_TRADES_LOADED'; trades: BotTrade[]; failed: boolean; at: string }
  | { type: 'TRADE_RECORDED_EVENT'; items: TradeNotice[] }
  | { type: 'TRADE_DETAIL'; botId: string; trade: PaperTrade; at: string }
  | { type: 'RECONCILIATION_REQUESTED'; showLoading: boolean }
  | { type: 'RECONCILIATION_LOADED'; result: ReconciliationResult; at: string }
  | { type: 'RECONCILIATION_FAILED' }
  | { type: 'SOURCES_LOADED'; items: SourceStatus[]; at: string }
  | { type: 'SOURCES_FAILED' }
  | { type: 'SOURCES_TICK'; now: string }
  | { type: 'CHANNEL_STATUS'; state: ChannelState; outdated: boolean }
  | { type: 'EVENT_RECEIVED'; at: string }
  | { type: 'PAUSE_SENDING'; botId: string; idempotencyKey: string }
  | { type: 'PAUSE_SUCCEEDED'; bot: BotSummary; at: string }
  | { type: 'PAUSE_FAILED'; botId: string }
  | { type: 'PAUSE_CONFLICT'; botId: string };

export function initialDashboardState(now: Date): DashboardState {
  return {
    bots: {
      status: 'LOADING',
      inUse: [],
      activeCount: 0,
      activeLimit: 3,
      pausedCount: 0,
      draftCount: 0,
      stoppedCount: 0,
      draftToContinueId: null,
      names: {},
      queued: [],
      loaded: false,
    },
    lastTrade: { status: 'LOADING', trade: null },
    reconciliation: { status: 'LOADING', result: null },
    sources: { status: 'LOADING', items: [], now: now.toISOString() },
    channel: { state: 'CLOSED', outdated: false, lastEventAt: null, lastSuccessAt: null },
    pendingPauses: {},
  };
}

function instant(value: string | undefined): number {
  return value ? new Date(value).getTime() : Number.NaN;
}

/** The incoming summary is at least as new as the shown one (no `updatedAt` on either side: the incoming wins). */
function isNotOlder(incoming: BotSummary, shown: BotSummary): boolean {
  const next = instant(incoming.updatedAt);
  const current = instant(shown.updatedAt);
  return Number.isNaN(next) || Number.isNaN(current) || next >= current;
}

const GROUP_ORDER: Record<string, number> = { ACTIVE: 0, PAUSED: 1, CLOSING_PENDING: 2 };

/** Active first, then paused, then closing pending, each group by name (F2 step 2). */
export function sortInUse(bots: BotSummary[]): BotSummary[] {
  return [...bots].sort(
    (a, b) =>
      (GROUP_ORDER[a.state] ?? 9) - (GROUP_ORDER[b.state] ?? 9) ||
      a.name.localeCompare(b.name, 'pt-BR'),
  );
}

function applyBotState(inUse: BotSummary[], data: BotStateEnvelope): BotSummary[] {
  const incoming = new Map(data.items.map((bot) => [bot.botId, bot]));
  return inUse.map((bot) => {
    const next = incoming.get(bot.botId);
    return next && isNotOlder(next, bot) ? next : bot;
  });
}

function recount(bots: BotsSlice, inUse: BotSummary[]): BotsSlice {
  return {
    ...bots,
    inUse,
    activeCount: inUse.filter((bot) => bot.state === 'ACTIVE').length,
    pausedCount: inUse.filter((bot) => bot.state === 'PAUSED').length,
    status: bots.status === 'ERROR' ? 'ERROR' : inUse.length > 0 ? 'READY' : 'EMPTY',
  };
}

function loadBots(state: DashboardState, lists: BotLists, activeLimit: number): BotsSlice {
  let inUse = sortInUse(IN_USE.flatMap((botState) => lists[botState] ?? []));
  for (const queued of state.bots.queued) {
    inUse = applyBotState(inUse, queued);
  }
  const names: Record<string, string> = { ...state.bots.names };
  for (const botState of ALL_STATES) {
    for (const bot of lists[botState] ?? []) {
      names[bot.botId] = bot.name;
    }
  }
  const drafts = lists.DRAFT ?? [];
  const validated = lists.VALIDATED ?? [];
  return {
    status: inUse.length > 0 ? 'READY' : 'EMPTY',
    inUse,
    activeCount: (lists.ACTIVE ?? []).length,
    activeLimit,
    pausedCount: (lists.PAUSED ?? []).length,
    draftCount: drafts.length + validated.length,
    stoppedCount: (lists.STOPPED ?? []).length,
    draftToContinueId: drafts[0]?.botId ?? validated[0]?.botId ?? null,
    names,
    queued: [],
    loaded: true,
  };
}

function createdAt(trade: LastTrade): number {
  return instant(trade.trade.createdAt);
}

/** The newest of the two orders; the full order wins over the notice of the SAME order (it has the reason). */
export function newestTrade(current: LastTrade | null, incoming: LastTrade): LastTrade {
  if (!current) {
    return incoming;
  }
  if (incoming.trade.orderId === current.trade.orderId) {
    return incoming.source === 'full' || current.source === 'notice' ? incoming : current;
  }
  return createdAt(incoming) > createdAt(current) ? incoming : current;
}

function withSuccess(state: DashboardState, at: string): ChannelSlice {
  return { ...state.channel, lastSuccessAt: at };
}

function removePending(
  pending: Record<string, PendingPause>,
  botId: string,
): Record<string, PendingPause> {
  const next = { ...pending };
  delete next[botId];
  return next;
}

export function dashboardReducer(state: DashboardState, action: DashboardAction): DashboardState {
  switch (action.type) {
    case 'BOTS_LOADING':
      return { ...state, bots: { ...state.bots, status: 'LOADING' } };
    case 'BOTS_LOADED':
      return {
        ...state,
        bots: loadBots(state, action.lists, action.activeLimit),
        channel: withSuccess(state, action.at),
      };
    case 'BOTS_FAILED':
      // BR1.2: a failed new read keeps the bots already shown.
      return state.bots.loaded
        ? { ...state, bots: { ...state.bots, status: state.bots.inUse.length ? 'READY' : 'EMPTY' } }
        : { ...state, bots: { ...state.bots, status: 'ERROR', queued: [] } };
    case 'BOT_STATE_EVENT':
      if (state.bots.status === 'LOADING') {
        return { ...state, bots: { ...state.bots, queued: [...state.bots.queued, action.data] } };
      }
      return {
        ...state,
        bots: { ...state.bots, inUse: applyBotState(state.bots.inUse, action.data) },
      };
    case 'BOT_REMOVED':
      return {
        ...state,
        bots: recount(
          state.bots,
          state.bots.inUse.filter((bot) => bot.botId !== action.botId),
        ),
        pendingPauses: removePending(state.pendingPauses, action.botId),
      };
    case 'LAST_TRADE_LOADING':
      return { ...state, lastTrade: { ...state.lastTrade, status: 'LOADING' } };
    case 'LAST_TRADES_LOADED': {
      let trade = state.lastTrade.trade;
      for (const loaded of action.trades) {
        trade = newestTrade(trade, { source: 'full', botId: loaded.botId, trade: loaded.trade });
      }
      return {
        ...state,
        lastTrade: { status: action.failed && !trade ? 'ERROR' : 'READY', trade },
        channel: withSuccess(state, action.at),
      };
    }
    case 'TRADE_RECORDED_EVENT': {
      let trade = state.lastTrade.trade;
      for (const notice of action.items) {
        trade = newestTrade(trade, { source: 'notice', botId: notice.botId, trade: notice });
      }
      const status = state.lastTrade.status === 'ERROR' && trade ? 'READY' : state.lastTrade.status;
      return { ...state, lastTrade: { status, trade } };
    }
    case 'TRADE_DETAIL':
      return {
        ...state,
        lastTrade: {
          ...state.lastTrade,
          trade: newestTrade(state.lastTrade.trade, {
            source: 'full',
            botId: action.botId,
            trade: action.trade,
          }),
        },
        channel: withSuccess(state, action.at),
      };
    case 'RECONCILIATION_REQUESTED':
      return action.showLoading
        ? { ...state, reconciliation: { ...state.reconciliation, status: 'LOADING' } }
        : state;
    case 'RECONCILIATION_LOADED':
      return {
        ...state,
        reconciliation: { status: 'READY', result: action.result },
        channel: withSuccess(state, action.at),
      };
    case 'RECONCILIATION_FAILED':
      return {
        ...state,
        reconciliation: {
          ...state.reconciliation,
          status: state.reconciliation.result ? 'READY' : 'ERROR',
        },
      };
    case 'SOURCES_LOADED':
      return {
        ...state,
        sources: { ...state.sources, status: 'READY', items: action.items },
        channel: withSuccess(state, action.at),
      };
    case 'SOURCES_FAILED':
      return {
        ...state,
        sources: { ...state.sources, status: state.sources.items.length ? 'READY' : 'ERROR' },
      };
    case 'SOURCES_TICK':
      return { ...state, sources: { ...state.sources, now: action.now } };
    case 'CHANNEL_STATUS':
      return {
        ...state,
        channel: { ...state.channel, state: action.state, outdated: action.outdated },
      };
    case 'EVENT_RECEIVED':
      return {
        ...state,
        channel: { ...state.channel, lastEventAt: action.at, lastSuccessAt: action.at },
      };
    case 'PAUSE_SENDING':
      return {
        ...state,
        pendingPauses: {
          ...state.pendingPauses,
          [action.botId]: { idempotencyKey: action.idempotencyKey, status: 'SENDING' },
        },
      };
    case 'PAUSE_SUCCEEDED':
      return {
        ...state,
        bots: recount(
          state.bots,
          state.bots.inUse.map((bot) => (bot.botId === action.bot.botId ? action.bot : bot)),
        ),
        pendingPauses: removePending(state.pendingPauses, action.bot.botId),
        channel: withSuccess(state, action.at),
      };
    case 'PAUSE_FAILED':
    case 'PAUSE_CONFLICT': {
      const pending = state.pendingPauses[action.botId];
      if (!pending) {
        return state;
      }
      return {
        ...state,
        pendingPauses: {
          ...state.pendingPauses,
          [action.botId]: {
            ...pending,
            status: action.type === 'PAUSE_FAILED' ? 'FAILED' : 'CONFLICT',
          },
        },
      };
    }
  }
}

/** Five minutes: a source older than this is late (BR4.1). */
export const SOURCE_MAX_AGE_MS = 5 * 60_000;

export type SourceNotice =
  | { kind: 'stale'; name: string; lastUpdatedAt: string }
  | { kind: 'failed'; name: string; lastUpdatedAt: string | null }
  | { kind: 'never'; name: string };

/** BR4.1: every source not OK, or OK but older than 5 minutes on the app clock, becomes a notice. */
export function sourceNotices(items: SourceStatus[], now: string): SourceNotice[] {
  const reference = new Date(now).getTime();
  const notices: SourceNotice[] = [];
  for (const source of items) {
    const name = source.source.name;
    const updated = source.lastUpdatedAt;
    if (source.status === 'FAILED') {
      notices.push({ kind: 'failed', name, lastUpdatedAt: updated ?? null });
    } else if (!updated) {
      notices.push({ kind: 'never', name });
    } else if (
      source.status !== 'OK' ||
      reference - new Date(updated).getTime() > SOURCE_MAX_AGE_MS
    ) {
      notices.push({ kind: 'stale', name, lastUpdatedAt: updated });
    }
  }
  return notices;
}

// ---------------- 261121-paper-real-unico U3: the two cards by mode (BR7.1 to BR7.5) ----------------

/** BR7.5: a new total is announced at most once every 30 s per card, never on the first load. */
export const MODE_CARD_ANNOUNCE_MS = 30_000;

export interface ModeCard<B> {
  load: BlockLoad<B>;
  /** The slot `card:<market>`: an answer that is not the one of the last request is ignored. */
  requestId: number;
  /** When the total was last announced (epoch ms), or null when never. */
  lastAnnouncedAt: number | null;
  /** Whether the total in screen may be announced (its region is polite) or not (off). */
  announce: boolean;
}

export interface ModeCardsState {
  PAPER: ModeCard<PaperModeBlock>;
  REAL: ModeCard<RealModeBlock> & { realUnavailable: RealEmptyReason };
}

export type ModeCardsAction =
  | { type: 'MODE_CARDS_REQUESTED'; requestId: number }
  | {
      type: 'MODE_CARD_REQUESTED';
      market: 'PAPER' | 'REAL';
      requestId: number;
      showLoading: boolean;
    }
  | {
      type: 'MODE_CARD_LOADED';
      market: 'PAPER' | 'REAL';
      requestId: number;
      load: BlockLoad<PaperModeBlock> | BlockLoad<RealModeBlock>;
      /** epoch ms of the answer; `initial` answers are never announced. */
      at: number;
      initial: boolean;
    }
  | {
      type: 'MODE_CARD_FAILED';
      market: 'PAPER' | 'REAL';
      requestId: number;
      code: string;
      /** A reading by event that failed keeps the values already shown (BR1.2 of the dashboard). */
      background?: boolean;
    }
  | { type: 'REAL_ELIGIBILITY_LOADED'; realUnavailable: RealEmptyReason };

export const INITIAL_MODE_CARDS: ModeCardsState = {
  PAPER: { load: { kind: 'loading' }, requestId: 0, lastAnnouncedAt: null, announce: false },
  REAL: {
    load: { kind: 'loading' },
    requestId: 0,
    lastAnnouncedAt: null,
    announce: false,
    realUnavailable: { kind: 'unknown' },
  },
};

function withCard(
  state: ModeCardsState,
  market: 'PAPER' | 'REAL',
  change: Partial<ModeCard<PaperModeBlock>> | Partial<ModeCard<RealModeBlock>>,
): ModeCardsState {
  return market === 'PAPER'
    ? { ...state, PAPER: { ...state.PAPER, ...(change as Partial<ModeCard<PaperModeBlock>>) } }
    : { ...state, REAL: { ...state.REAL, ...(change as Partial<ModeCard<RealModeBlock>>) } };
}

/** The pure reducer of the two cards; the component does the calls (C4 and C2) and the windows of 30 s. */
export function modeCardsReducer(state: ModeCardsState, action: ModeCardsAction): ModeCardsState {
  switch (action.type) {
    case 'MODE_CARDS_REQUESTED':
      return {
        PAPER: { ...state.PAPER, load: { kind: 'loading' }, requestId: action.requestId },
        REAL: { ...state.REAL, load: { kind: 'loading' }, requestId: action.requestId },
      };
    case 'MODE_CARD_REQUESTED':
      return withCard(state, action.market, {
        requestId: action.requestId,
        ...(action.showLoading ? { load: { kind: 'loading' as const } } : {}),
      });
    case 'MODE_CARD_LOADED': {
      const card = state[action.market];
      if (card.requestId !== action.requestId) {
        return state;
      }
      const announce =
        !action.initial &&
        (card.lastAnnouncedAt === null ||
          action.at - card.lastAnnouncedAt >= MODE_CARD_ANNOUNCE_MS);
      return withCard(state, action.market, {
        load: action.load,
        announce,
        lastAnnouncedAt: announce ? action.at : card.lastAnnouncedAt,
      } as Partial<ModeCard<PaperModeBlock>>);
    }
    case 'MODE_CARD_FAILED': {
      const card = state[action.market];
      if (
        card.requestId !== action.requestId ||
        (action.background && card.load.kind === 'ready')
      ) {
        return state;
      }
      return withCard(state, action.market, {
        load: { kind: 'error', code: action.code },
        announce: false,
      });
    }
    case 'REAL_ELIGIBILITY_LOADED':
      return { ...state, REAL: { ...state.REAL, realUnavailable: action.realUnavailable } };
    default:
      return state;
  }
}
