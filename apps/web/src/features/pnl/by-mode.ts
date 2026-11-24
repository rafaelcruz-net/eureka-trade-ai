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
 *
 * 261121-paper-real-unico U3 (BR3.1): with `market`, C4 answers only the block asked and OMITS the key of the other.
 * An absent key is not an error: the load is `undefined`, and the screen keeps the block as it was.
 */
export function paperLoad(result: PaperModeBlockResult): BlockLoad<PaperModeBlock>;
export function paperLoad(
  result: PaperModeBlockResult | undefined,
): BlockLoad<PaperModeBlock> | undefined;
export function paperLoad(
  result: PaperModeBlockResult | undefined,
): BlockLoad<PaperModeBlock> | undefined {
  if (result === undefined) {
    return undefined;
  }
  return result.status === 'UNAVAILABLE'
    ? { kind: 'error', code: result.correlationId }
    : { kind: 'ready', block: result };
}

export function realLoad(result: RealModeBlockResult): BlockLoad<RealModeBlock>;
export function realLoad(
  result: RealModeBlockResult | undefined,
): BlockLoad<RealModeBlock> | undefined;
export function realLoad(
  result: RealModeBlockResult | undefined,
): BlockLoad<RealModeBlock> | undefined {
  if (result === undefined) {
    return undefined;
  }
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
