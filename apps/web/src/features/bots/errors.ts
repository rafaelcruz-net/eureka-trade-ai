import { ApiError } from '@/lib/api/client';
import { formatDecimal } from '@/lib/format/format';
import { botMessages } from '@/features/bots/messages';

const text = botMessages.detail;

/** The refused fields of a 400 or 422 (C4), by field path, to show next to each field. */
export function fieldErrorsOf(failure: unknown): Record<string, string> {
  if (!(failure instanceof ApiError)) {
    return {};
  }
  const errors: Record<string, string> = {};
  for (const field of failure.fields) {
    errors[field.field] = field.message;
  }
  return errors;
}

/** The sentence of a refused lifecycle command (409 of C4), or the generic "tentar de novo" (BR6.2). */
export function commandErrorText(failure: unknown): string {
  if (failure instanceof ApiError) {
    switch (failure.code) {
      case 'ACTIVE_BOT_LIMIT':
        return text.resumeLimit;
      case 'PAUSED_BY_SYSTEM':
        return text.resumeSystem;
      case 'DAILY_LOSS_LIMIT_REACHED':
        return failure.reason ? text.resumeLoss(formatDecimal(failure.reason)) : text.commandError;
      case 'INVALID_TRANSITION':
        return text.invalidTransition;
      default:
        return text.commandError;
    }
  }
  return text.commandError;
}

/** Whether the failure is a 401: the screen hands over to the session expiry (F2 step 4). */
export function isUnauthenticated(failure: unknown): boolean {
  return failure instanceof ApiError && failure.status === 401;
}
