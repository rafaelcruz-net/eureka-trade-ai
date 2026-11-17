import type { RealError } from '@/features/real/api';
import { realMessages as text } from '@/features/real/messages';

/**
 * 261113-trading-real-bot U6 (BR8.1, BR8.8, U6-NFR5.1): the error of a part of a REAL screen in text, with the
 * `correlationId` for support and, when given, "Tentar de novo". Never a stack trace, never the console.
 */
export function RealErrorNotice({
  error,
  message,
  onRetry,
  testId,
}: {
  error: RealError;
  /** The text of the part ("Não foi possível carregar…"); the text of the error follows it. */
  message?: string;
  onRetry?: () => void;
  testId: string;
}) {
  return (
    <div role="alert" className="notice notice-error" data-testid={testId}>
      {message && <p>{message}</p>}
      <p data-testid={`${testId}-text`}>{error.text}</p>
      {error.correlationId && (
        <p className="muted" data-testid={`${testId}-correlation`}>
          {text.supportCode(error.correlationId)}
        </p>
      )}
      {onRetry && (
        <button type="button" onClick={onRetry} data-testid={`${testId}-retry`}>
          {text.retry}
        </button>
      )}
    </div>
  );
}
