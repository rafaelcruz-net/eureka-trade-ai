import type {
  PaperComparisonSide,
  PaperModeBlock,
  PnlReport,
  RealComparisonSide,
  RealModeBlock,
} from '@/lib/api/guest-api';
import type { BlockLoad } from '@/features/pnl/PnlModeBlock';
import { SINCE_START, type AppliedPeriod } from '@/features/pnl/PeriodSelector';

/**
 * 261121-paper-real-unico U3 (decision F-3; BR2.1 to BR2.9, BR3.2 to BR3.4, BR5.5; NFR1.8, NFR4.8, NFR4.11): the state
 * of /pnl as a PURE reducer. Every request has a slot (`byMode`, `retry:PAPER`, `retry:REAL`, `tokens`, and `compare`
 * in the comparison) and a request id; a `*_LOADED` whose id is not the one of its slot is an older answer and is
 * ignored, so only the last request of each slot reaches the screen. The screen does the calls and aborts them.
 */
export type ByModeSlot = 'byMode' | 'retry:PAPER' | 'retry:REAL';
export type PnlSlot = ByModeSlot | 'tokens';

/** The table by token of the PAPER (`GET /api/v1/pnl`), only with a concrete period (BR2.9). */
export type TokensLoad =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; code: string }
  | { kind: 'ready'; report: PnlReport };

export interface PnlScreenState {
  period: AppliedPeriod;
  paper: BlockLoad<PaperModeBlock>;
  real: BlockLoad<RealModeBlock>;
  tokens: TokensLoad;
  /** The block whose title receives the focus after its "Tentar de novo" (BR3.4). */
  focus: 'PAPER' | 'REAL' | null;
  slots: Record<PnlSlot, number>;
}

export type PnlScreenAction =
  | { type: 'PERIOD_APPLIED'; period: AppliedPeriod }
  | { type: 'BY_MODE_REQUESTED'; requestId: number }
  | {
      type: 'BY_MODE_LOADED';
      requestId: number;
      paper?: BlockLoad<PaperModeBlock>;
      real?: BlockLoad<RealModeBlock>;
    }
  | { type: 'BY_MODE_FAILED'; requestId: number; code: string }
  | { type: 'BLOCK_RETRY_REQUESTED'; market: 'PAPER' | 'REAL'; requestId: number }
  | {
      type: 'BLOCK_RETRY_LOADED';
      market: 'PAPER' | 'REAL';
      requestId: number;
      load: BlockLoad<PaperModeBlock> | BlockLoad<RealModeBlock> | undefined;
    }
  | { type: 'BLOCK_RETRY_FAILED'; market: 'PAPER' | 'REAL'; requestId: number; code: string }
  | { type: 'TOKENS_REQUESTED'; requestId: number }
  | { type: 'TOKENS_LOADED'; requestId: number; report: PnlReport }
  | { type: 'TOKENS_FAILED'; requestId: number; code: string }
  | { type: 'TOKENS_IDLE' };

export function initialPnlScreenState(period: AppliedPeriod = SINCE_START): PnlScreenState {
  return {
    period,
    paper: { kind: 'loading' },
    real: { kind: 'loading' },
    tokens: { kind: 'idle' },
    focus: null,
    slots: { byMode: 0, 'retry:PAPER': 0, 'retry:REAL': 0, tokens: 0 },
  };
}

function current(state: PnlScreenState, slot: PnlSlot, requestId: number): boolean {
  return state.slots[slot] === requestId;
}

export function pnlScreenReducer(state: PnlScreenState, action: PnlScreenAction): PnlScreenState {
  switch (action.type) {
    case 'PERIOD_APPLIED':
      return { ...state, period: action.period, focus: null };
    case 'BY_MODE_REQUESTED':
      // A new reading of both blocks also supersedes a pending "Tentar de novo" of either.
      return {
        ...state,
        paper: { kind: 'loading' },
        real: { kind: 'loading' },
        slots: {
          ...state.slots,
          byMode: action.requestId,
          'retry:PAPER': 0,
          'retry:REAL': 0,
        },
      };
    case 'BY_MODE_LOADED':
      if (!current(state, 'byMode', action.requestId)) {
        return state;
      }
      return {
        ...state,
        paper: action.paper ?? state.paper,
        real: action.real ?? state.real,
      };
    case 'BY_MODE_FAILED':
      if (!current(state, 'byMode', action.requestId)) {
        return state;
      }
      return {
        ...state,
        paper: { kind: 'error', code: action.code },
        real: { kind: 'error', code: action.code },
      };
    case 'BLOCK_RETRY_REQUESTED': {
      const slot: ByModeSlot = `retry:${action.market}`;
      return action.market === 'PAPER'
        ? {
            ...state,
            paper: { kind: 'loading' },
            focus: null,
            slots: { ...state.slots, [slot]: action.requestId },
          }
        : {
            ...state,
            real: { kind: 'loading' },
            focus: null,
            slots: { ...state.slots, [slot]: action.requestId },
          };
    }
    case 'BLOCK_RETRY_LOADED': {
      if (!current(state, `retry:${action.market}`, action.requestId) || !action.load) {
        return state;
      }
      return action.market === 'PAPER'
        ? { ...state, paper: action.load as BlockLoad<PaperModeBlock>, focus: 'PAPER' }
        : { ...state, real: action.load as BlockLoad<RealModeBlock>, focus: 'REAL' };
    }
    case 'BLOCK_RETRY_FAILED': {
      if (!current(state, `retry:${action.market}`, action.requestId)) {
        return state;
      }
      const load = { kind: 'error' as const, code: action.code };
      return action.market === 'PAPER'
        ? { ...state, paper: load, focus: 'PAPER' }
        : { ...state, real: load, focus: 'REAL' };
    }
    case 'TOKENS_REQUESTED':
      return {
        ...state,
        tokens: { kind: 'loading' },
        slots: { ...state.slots, tokens: action.requestId },
      };
    case 'TOKENS_LOADED':
      return current(state, 'tokens', action.requestId)
        ? { ...state, tokens: { kind: 'ready', report: action.report } }
        : state;
    case 'TOKENS_FAILED':
      return current(state, 'tokens', action.requestId)
        ? { ...state, tokens: { kind: 'error', code: action.code } }
        : state;
    case 'TOKENS_IDLE':
      return { ...state, tokens: { kind: 'idle' }, slots: { ...state.slots, tokens: 0 } };
    default:
      return state;
  }
}

// ---------------- The comparison (C6), the slot `compare` ----------------

/** One side of C6 as the screen shows it. */
export type CompareSideLoad<S> =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'error'; code: string }
  | { kind: 'notFound' }
  | { kind: 'ready'; side: S };

export type CompareProblem = { kind: 'templateMismatch' } | { kind: 'error'; code: string } | null;

export interface CompareState {
  paper: CompareSideLoad<PaperComparisonSide>;
  real: CompareSideLoad<RealComparisonSide>;
  problem: CompareProblem;
  /** The slot of C6: the request id of the last reading (both sides or a retried side). */
  slot: number;
  /** The side whose retry is in flight; the other side never changes with its answer (BR5.5). */
  retrying: 'PAPER' | 'REAL' | null;
}

export type CompareAction =
  | { type: 'COMPARE_RESET' }
  | { type: 'COMPARE_REQUESTED'; requestId: number }
  | {
      type: 'COMPARE_LOADED';
      requestId: number;
      paper: CompareSideLoad<PaperComparisonSide>;
      real: CompareSideLoad<RealComparisonSide>;
    }
  | { type: 'COMPARE_REFUSED'; requestId: number; problem: Exclude<CompareProblem, null> }
  | { type: 'COMPARE_NOT_FOUND'; requestId: number; side: 'PAPER' | 'REAL' }
  | { type: 'COMPARE_SIDE_RETRIED'; side: 'PAPER' | 'REAL'; requestId: number };

export const INITIAL_COMPARE: CompareState = {
  paper: { kind: 'idle' },
  real: { kind: 'idle' },
  problem: null,
  slot: 0,
  retrying: null,
};

export function compareReducer(state: CompareState, action: CompareAction): CompareState {
  switch (action.type) {
    case 'COMPARE_RESET':
      return { ...INITIAL_COMPARE, slot: state.slot };
    case 'COMPARE_REQUESTED':
      return {
        paper: { kind: 'loading' },
        real: { kind: 'loading' },
        problem: null,
        slot: action.requestId,
        retrying: null,
      };
    case 'COMPARE_SIDE_RETRIED':
      return action.side === 'PAPER'
        ? { ...state, paper: { kind: 'loading' }, slot: action.requestId, retrying: 'PAPER' }
        : { ...state, real: { kind: 'loading' }, slot: action.requestId, retrying: 'REAL' };
    case 'COMPARE_LOADED':
      if (state.slot !== action.requestId) {
        return state;
      }
      if (state.retrying === 'PAPER') {
        return { ...state, paper: action.paper, retrying: null };
      }
      if (state.retrying === 'REAL') {
        return { ...state, real: action.real, retrying: null };
      }
      return { ...state, paper: action.paper, real: action.real, problem: null };
    case 'COMPARE_REFUSED':
      if (state.slot !== action.requestId) {
        return state;
      }
      if (action.problem.kind === 'error' && state.retrying !== null) {
        const failed = { kind: 'error' as const, code: action.problem.code };
        return state.retrying === 'PAPER'
          ? { ...state, paper: failed, retrying: null }
          : { ...state, real: failed, retrying: null };
      }
      return {
        paper: { kind: 'idle' },
        real: { kind: 'idle' },
        problem: action.problem,
        slot: state.slot,
        retrying: null,
      };
    case 'COMPARE_NOT_FOUND':
      if (state.slot !== action.requestId) {
        return state;
      }
      return {
        paper: action.side === 'PAPER' ? { kind: 'notFound' } : { kind: 'idle' },
        real: action.side === 'REAL' ? { kind: 'notFound' } : { kind: 'idle' },
        problem: null,
        slot: state.slot,
        retrying: null,
      };
    default:
      return state;
  }
}
