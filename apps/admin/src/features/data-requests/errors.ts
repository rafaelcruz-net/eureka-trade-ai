import { ApiError } from '@/lib/api/client';
import { messages } from '@/lib/i18n/messages';

const text = messages.dataRequests;
const GENERIC = 'Não foi possível concluir agora. Tente de novo.';

/** u4-data-requests: the pt-BR reason of a failed call of P2, by its machine code (never the server text). */
export function reasonOf(failure: unknown): string {
  if (failure instanceof ApiError) {
    return text.errors[failure.code] ?? text.errors.GENERIC ?? GENERIC;
  }
  return text.errors.GENERIC ?? GENERIC;
}

/** Copies {@code value} to the clipboard; false when the browser refused (the screen says so). */
export async function copyText(value: string): Promise<boolean> {
  try {
    if (!navigator.clipboard) {
      return false;
    }
    await navigator.clipboard.writeText(value);
    return true;
  } catch (refused) {
    console.warn('clipboard write refused', refused instanceof Error ? refused.name : 'unknown');
    return false;
  }
}
