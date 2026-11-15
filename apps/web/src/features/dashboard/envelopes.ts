import type { components } from '@/lib/api/schema';
import type { EventKind } from '@/features/dashboard/warn';

/**
 * The envelopes of the live channel C7, typed from the generated contract (team.md: never written by hand), and the
 * check every received event passes before it reaches the dashboard state (BR5.2, NFR6.5): each field of the
 * envelope must have the type of the contract; extra fields are dropped; anything else discards the event (the
 * channel stays open). The text is never turned into markup.
 */
type Schemas = components['schemas'];

export type BotSummary = Schemas['BotSummary'];
export type SourceStatus = Schemas['SourceStatus'];
export type BotStateEnvelope = Schemas['DashboardBotStateEvent'];
export type TradeNotice = Schemas['DashboardTradeNotice'];
export type TradeRecordedEnvelope = Schemas['DashboardTradeRecordedEvent'];
export type PnlUpdatedEnvelope = Schemas['DashboardPnlUpdatedEvent'];
export type DataStatusEnvelope = Schemas['DashboardDataStatusEvent'];
export type HeartbeatEnvelope = Schemas['DashboardHeartbeatEvent'];
export type AuthExpiredEnvelope = Schemas['DashboardAuthExpiredEvent'];

export type DashboardEvent =
  | { kind: 'bot.state'; data: BotStateEnvelope }
  | { kind: 'trade.recorded'; data: TradeRecordedEnvelope }
  | { kind: 'pnl.updated'; data: PnlUpdatedEnvelope }
  | { kind: 'data.status'; data: DataStatusEnvelope }
  | { kind: 'heartbeat'; data: HeartbeatEnvelope }
  | { kind: 'auth.expired'; data: AuthExpiredEnvelope };

const BOT_STATES: readonly BotSummary['state'][] = [
  'DRAFT',
  'VALIDATED',
  'ACTIVE',
  'PAUSED',
  'CLOSING_PENDING',
  'STOPPED',
];
const PAUSE_REASONS: readonly BotSummary['pauseReasons'][number][] = [
  'PERSON',
  'DAILY_LOSS',
  'DRAWDOWN',
  'SYSTEM',
  'ADMIN_RELEASED',
  'WAITING_ACTIVE_SLOT',
];
const SIDES: readonly TradeNotice['side'][] = ['BUY', 'SELL'];
const TRADE_STATUSES: readonly TradeNotice['status'][] = [
  'FILLED',
  'PARTIALLY_FILLED',
  'NOT_FILLED',
  'CLOSE_PENDING',
];
const SOURCE_STATES: readonly SourceStatus['status'][] = ['PENDING', 'OK', 'DEGRADED', 'FAILED'];

/** Raised inside the check; the caller turns it into a discarded event. */
class InvalidEnvelope extends Error {}

type Json = Record<string, unknown>;

function object(value: unknown): Json {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new InvalidEnvelope();
  }
  return value as Json;
}

function text(value: unknown): string {
  if (typeof value !== 'string') {
    throw new InvalidEnvelope();
  }
  return value;
}

function optionalText(value: unknown): string | undefined {
  return value === undefined || value === null ? undefined : text(value);
}

/** An integer count or mark sent as a JSON number (never money: money is always text in this contract). */
function integer(value: unknown): number {
  if (typeof value !== 'number' || !Number.isInteger(value)) {
    throw new InvalidEnvelope();
  }
  return value;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T {
  const candidate = text(value);
  if (!(allowed as readonly string[]).includes(candidate)) {
    throw new InvalidEnvelope();
  }
  return candidate as T;
}

function list<T>(value: unknown, item: (entry: unknown) => T): T[] {
  if (!Array.isArray(value)) {
    throw new InvalidEnvelope();
  }
  return value.map(item);
}

function botSummary(value: unknown): BotSummary {
  const raw = object(value);
  const bot: BotSummary = {
    botId: text(raw.botId),
    name: text(raw.name),
    mode: oneOf(raw.mode, ['PAPER'] as const),
    // 261113-trading-real-bot U4: the market of the bot (REAL or PAPER); never guessed when absent.
    market: oneOf(raw.market, ['PAPER', 'REAL'] as const),
    state: oneOf(raw.state, BOT_STATES),
    pauseReasons: list(raw.pauseReasons, (reason) => oneOf(reason, PAUSE_REASONS)),
  };
  const resumesAt = optionalText(raw.resumesAt);
  const updatedAt = optionalText(raw.updatedAt);
  const templateId = optionalText(raw.templateId);
  return {
    ...bot,
    ...(resumesAt === undefined ? {} : { resumesAt }),
    ...(updatedAt === undefined ? {} : { updatedAt }),
    ...(templateId === undefined ? {} : { templateId }),
  };
}

function tradeNotice(value: unknown): TradeNotice {
  const raw = object(value);
  return {
    orderId: text(raw.orderId),
    botId: text(raw.botId),
    side: oneOf(raw.side, SIDES),
    chain: text(raw.chain),
    tokenMint: text(raw.tokenMint),
    status: oneOf(raw.status, TRADE_STATUSES),
    createdAt: text(raw.createdAt),
  };
}

function sourceStatus(value: unknown): SourceStatus {
  const raw = object(value);
  const source = object(raw.source);
  if (typeof source.simulated !== 'boolean') {
    throw new InvalidEnvelope();
  }
  const lastUpdatedAt = optionalText(raw.lastUpdatedAt);
  return {
    source: {
      sourceId: text(source.sourceId),
      name: text(source.name),
      simulated: source.simulated,
    },
    status: oneOf(raw.status, SOURCE_STATES),
    ...(lastUpdatedAt === undefined ? {} : { lastUpdatedAt }),
  };
}

function check(kind: EventKind, raw: Json): DashboardEvent {
  switch (kind) {
    case 'bot.state':
      return {
        kind,
        data: { items: list(raw.items, botSummary), activeCount: integer(raw.activeCount) },
      };
    case 'trade.recorded':
      return { kind, data: { items: list(raw.items, tradeNotice) } };
    case 'pnl.updated':
      return { kind, data: { ledgerMark: integer(raw.ledgerMark) } };
    case 'data.status':
      return { kind, data: { items: list(raw.items, sourceStatus) } };
    case 'heartbeat':
      return { kind, data: { at: text(raw.at) } };
    case 'auth.expired':
      return { kind, data: { expiredAt: text(raw.expiredAt) } };
  }
}

/** The checked event, or `null` when the data is not JSON or does not match the envelope of its kind. */
export function parseEnvelope(kind: EventKind, data: string): DashboardEvent | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(data);
  } catch (malformed) {
    if (malformed instanceof SyntaxError) {
      return null;
    }
    throw malformed;
  }
  try {
    return check(kind, object(parsed));
  } catch (failure) {
    if (failure instanceof InvalidEnvelope) {
      return null;
    }
    throw failure;
  }
}
