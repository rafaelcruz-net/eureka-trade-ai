import { ApiError } from '@/lib/api/client';
import { adminRealMessages as text } from '@/features/real/messages';

/**
 * 261113-trading-real-bot U6 (BR9.3, U6-NFR5.1): a failure of an action of the REAL section: its code, its text (the
 * text of the code, else the `detail` of the ProblemDetail) and the id for support. Never a stack trace.
 */
export class AdminRealError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly text: string,
    readonly correlationId: string | null,
  ) {
    super(`${status} ${code}`);
    this.name = 'AdminRealError';
  }
}

export function toAdminRealError(failure: unknown): AdminRealError {
  if (failure instanceof AdminRealError) {
    return failure;
  }
  if (!(failure instanceof ApiError)) {
    return new AdminRealError(0, 'UNKNOWN', text.genericError, null);
  }
  const message = text.errors[failure.code] ?? failure.detail ?? text.genericError;
  return new AdminRealError(failure.status, failure.code, message, failure.correlationId ?? null);
}
