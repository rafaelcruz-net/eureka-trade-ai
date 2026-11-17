import { formatSolAmount } from '@/features/real/money';
import { realMessages as text } from '@/features/real/messages';

/** interaction-spec.md OrderStateBadge: always text and icon, never color alone (BR8.4). */
const ICONS: Record<string, string> = {
  PROPOSED: '○',
  QUOTED: '◔',
  SIGNED: '◑',
  SENT: '◕',
  CONFIRMED: '✔',
  FAILED: '✖',
  AMBIGUOUS: '⟳',
  CANCELLED: '⊘',
};

/**
 * 261113-trading-real-bot U6 (BR8.4, AC4.1.2): the state of a REAL order. An ambiguous order is "Em conferência", with
 * the reserved amount when the screen knows it.
 */
export function OrderStateBadge({
  state,
  reservedLamports,
  testId,
}: {
  state: string;
  reservedLamports?: bigint;
  testId: string;
}) {
  return (
    <span className={`order-state order-state-${state.toLowerCase()}`} data-testid={testId}>
      <span aria-hidden="true">{ICONS[state] ?? '•'} </span>
      {text.orderStates[state] ?? state}
      {state === 'AMBIGUOUS' && reservedLamports !== undefined && (
        <span className="muted"> · {text.orders.reserved(formatSolAmount(reservedLamports))}</span>
      )}
    </span>
  );
}
