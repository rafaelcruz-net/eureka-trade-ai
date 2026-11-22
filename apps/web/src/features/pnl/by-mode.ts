import { ApiError } from '@/lib/api/client';
import type {
  PaperModeBlock,
  PaperModeBlockResult,
  RealModeBlock,
  RealModeBlockResult,
} from '@/lib/api/guest-api';
import type { BlockLoad } from '@/features/pnl/PnlModeBlock';

/**
 * 261121-paper-real-unico U1 (BR5.3, BR4.3): one block of C4 as the screen loads it. An UNAVAILABLE block is an error
 * with its correlation id as the code for the support; the other block is never affected.
 */
export function paperLoad(result: PaperModeBlockResult): BlockLoad<PaperModeBlock> {
  return result.status === 'UNAVAILABLE'
    ? { kind: 'error', code: result.correlationId }
    : { kind: 'ready', block: result };
}

export function realLoad(result: RealModeBlockResult): BlockLoad<RealModeBlock> {
  return result.status === 'UNAVAILABLE'
    ? { kind: 'error', code: result.correlationId }
    : { kind: 'ready', block: result };
}

/** The code for the support of a failed route: its correlation id, or its machine code without one. */
export function failureCode(failure: unknown): string {
  if (failure instanceof ApiError) {
    return failure.correlationId ?? failure.code;
  }
  return 'NETWORK';
}
