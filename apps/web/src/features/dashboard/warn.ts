/**
 * The single console warning point of the dashboard (NFR6.3, security-design.md): it accepts only the kind of the
 * event and a reason from a CLOSED list, so no `data`, event `id`, token, wallet or value can ever reach the console.
 */
export const EVENT_KINDS = [
  'bot.state',
  'trade.recorded',
  'pnl.updated',
  'data.status',
  'heartbeat',
  'auth.expired',
  // 261113-trading-real-bot U6 (BR3.5, logical-components.md "Eventos novos"): the REAL events of the same channel,
  // for the signing banner; they used to be discarded as UNKNOWN_EVENT.
  'real.signing.requested',
  'real.order.state',
] as const;

export type EventKind = (typeof EVENT_KINDS)[number];

/** `unknown` for an event name outside the contract; `stream` for the channel itself. */
export type WarnKind = EventKind | 'unknown' | 'stream';

export type WarnReason = 'INVALID_ENVELOPE' | 'UNKNOWN_EVENT' | 'OVERSIZED_EVENT' | 'NETWORK';

export function isEventKind(value: string): value is EventKind {
  return (EVENT_KINDS as readonly string[]).includes(value);
}

export function dashboardWarn(kind: WarnKind, reason: WarnReason): void {
  console.warn(`Dashboard live channel: ${kind} ${reason}`);
}
