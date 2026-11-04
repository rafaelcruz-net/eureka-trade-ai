import { messages } from '@/lib/i18n/messages';
import type { PnlAmount } from '@/lib/api/guest-api';
import { signedText, signOf } from '@/features/pnl/decimal';

const text = messages.pnl;

/** A signed USDC amount: sign, digits and a word, so color is never the only signal (BR10.1, accessibility). */
export function SignedValue({ value, testId }: { value: string; testId: string }) {
  const sign = signOf(value);
  const word = sign === 'gain' ? text.gain : sign === 'loss' ? text.loss : text.zero;
  return (
    <span className={`money money-${sign}`} data-testid={testId} data-sign={sign}>
      {signedText(value)} <span className="muted">({word})</span>
    </span>
  );
}

/** A P&L amount: the signed value, or "unknown" with its reason; never a zero standing for an unknown (BR6.3). */
export function PnlAmountView({ amount, testId }: { amount: PnlAmount; testId: string }) {
  if (amount.status === 'UNKNOWN') {
    return (
      <span className="money money-unknown" data-testid={testId} data-status="UNKNOWN">
        {text.unknown}{' '}
        <span className="muted">({text.unknownReason[amount.reason] ?? amount.reason})</span>
      </span>
    );
  }
  return <SignedValue value={amount.value} testId={testId} />;
}
