import {
  compareReducer,
  INITIAL_COMPARE,
  initialPnlScreenState,
  pnlScreenReducer,
  type CompareAction,
  type CompareState,
  type PnlScreenAction,
  type PnlScreenState,
} from '@/features/pnl/pnl-screen-state';
import { applyPeriod } from '@/features/pnl/PeriodSelector';
import { COMPARISON, PAPER_BLOCK, REAL_BLOCK, REPORT } from '@/features/pnl/fixtures';

function run(actions: PnlScreenAction[], start: PnlScreenState = initialPnlScreenState()) {
  return actions.reduce(pnlScreenReducer, start);
}

function runCompare(actions: CompareAction[], start: CompareState = INITIAL_COMPARE) {
  return actions.reduce(compareReducer, start);
}

const PAPER_READY = { kind: 'ready' as const, block: PAPER_BLOCK };
const REAL_READY = { kind: 'ready' as const, block: REAL_BLOCK };

/** 261121-paper-real-unico U3, Step 19 (decision F-3; BR3.2, BR3.4, BR5.5; NFR1.8, NFR4.11). */
describe('pnl-screen-state', () => {
  it('starts on "Desde o início" with both blocks loading and no table by token', () => {
    const state = initialPnlScreenState();

    expect(state.period.kind).toBe('ALL');
    expect(state.paper).toEqual({ kind: 'loading' });
    expect(state.real).toEqual({ kind: 'loading' });
    expect(state.tokens).toEqual({ kind: 'idle' });
  });

  it('an older answer of the slot byMode is discarded by its request id', () => {
    const state = run([
      { type: 'BY_MODE_REQUESTED', requestId: 1 },
      { type: 'BY_MODE_REQUESTED', requestId: 2 },
      {
        type: 'BY_MODE_LOADED',
        requestId: 1,
        paper: { kind: 'error', code: 'old' },
        real: { kind: 'error', code: 'old' },
      },
      { type: 'BY_MODE_LOADED', requestId: 2, paper: PAPER_READY, real: REAL_READY },
      { type: 'BY_MODE_FAILED', requestId: 1, code: 'late' },
    ]);

    expect(state.paper).toEqual(PAPER_READY);
    expect(state.real).toEqual(REAL_READY);
  });

  it('"Tentar de novo" puts only one block in loading and gives it the focus when it answers', () => {
    const loaded = run([
      { type: 'BY_MODE_REQUESTED', requestId: 1 },
      {
        type: 'BY_MODE_LOADED',
        requestId: 1,
        paper: PAPER_READY,
        real: { kind: 'error', code: 'c' },
      },
    ]);

    const retrying = pnlScreenReducer(loaded, {
      type: 'BLOCK_RETRY_REQUESTED',
      market: 'REAL',
      requestId: 2,
    });
    expect(retrying.real).toEqual({ kind: 'loading' });
    expect(retrying.paper).toBe(loaded.paper);

    const answered = pnlScreenReducer(retrying, {
      type: 'BLOCK_RETRY_LOADED',
      market: 'REAL',
      requestId: 2,
      load: REAL_READY,
    });
    expect(answered.real).toEqual(REAL_READY);
    expect(answered.paper).toBe(loaded.paper);
    expect(answered.focus).toBe('REAL');
  });

  it('a retry answer that is not the one of its slot, or an absent key, changes nothing', () => {
    const state = run([
      { type: 'BLOCK_RETRY_REQUESTED', market: 'PAPER', requestId: 3 },
      { type: 'BLOCK_RETRY_LOADED', market: 'PAPER', requestId: 2, load: PAPER_READY },
      { type: 'BLOCK_RETRY_LOADED', market: 'PAPER', requestId: 3, load: undefined },
    ]);

    expect(state.paper).toEqual({ kind: 'loading' });
    const failed = pnlScreenReducer(state, {
      type: 'BLOCK_RETRY_FAILED',
      market: 'PAPER',
      requestId: 3,
      code: 'corr-1',
    });
    expect(failed.paper).toEqual({ kind: 'error', code: 'corr-1' });
    expect(failed.focus).toBe('PAPER');
  });

  it('the table by token has its own slot; idle clears it', () => {
    const state = run([
      { type: 'TOKENS_REQUESTED', requestId: 4 },
      { type: 'TOKENS_REQUESTED', requestId: 5 },
      { type: 'TOKENS_FAILED', requestId: 4, code: 'old' },
      { type: 'TOKENS_LOADED', requestId: 5, report: REPORT },
    ]);
    expect(state.tokens).toEqual({ kind: 'ready', report: REPORT });
    expect(pnlScreenReducer(state, { type: 'TOKENS_IDLE' }).tokens).toEqual({ kind: 'idle' });
  });

  it('applying a period keeps its fixed UTC pair', () => {
    const applied = applyPeriod('TODAY', 'America/Sao_Paulo', new Date('2026-11-23T02:30:00Z'));
    if (!applied.ok) {
      throw new Error('a preset is valid');
    }
    const state = run([{ type: 'PERIOD_APPLIED', period: applied.period }]);

    expect(state.period.utc).toEqual({ from: '2026-11-22T03:00:00Z', to: '2026-11-23T03:00:00Z' });
  });
});

describe('compare slice', () => {
  const paperSide = { kind: 'ready' as const, side: COMPARISON.paper as never };
  const realSide = { kind: 'ready' as const, side: COMPARISON.real as never };

  it('the side without error never changes when the other side is retried', () => {
    const loaded = runCompare([
      { type: 'COMPARE_REQUESTED', requestId: 1 },
      {
        type: 'COMPARE_LOADED',
        requestId: 1,
        paper: paperSide,
        real: { kind: 'error', code: 'c' },
      },
    ]);
    const retried = compareReducer(loaded, {
      type: 'COMPARE_SIDE_RETRIED',
      side: 'REAL',
      requestId: 2,
    });
    expect(retried.paper).toBe(loaded.paper);
    expect(retried.real).toEqual({ kind: 'loading' });

    const answered = compareReducer(retried, {
      type: 'COMPARE_LOADED',
      requestId: 2,
      paper: { kind: 'error', code: 'would-change' },
      real: realSide,
    });
    expect(answered.paper).toBe(loaded.paper);
    expect(answered.real).toEqual(realSide);
  });

  it('an older answer of C6 is discarded', () => {
    const state = runCompare([
      { type: 'COMPARE_REQUESTED', requestId: 1 },
      { type: 'COMPARE_REQUESTED', requestId: 2 },
      { type: 'COMPARE_LOADED', requestId: 1, paper: paperSide, real: realSide },
    ]);

    expect(state.paper).toEqual({ kind: 'loading' });
  });

  it('a 422 has no value of P&L; a 404 marks only its side', () => {
    const mismatch = runCompare([
      { type: 'COMPARE_REQUESTED', requestId: 1 },
      { type: 'COMPARE_REFUSED', requestId: 1, problem: { kind: 'templateMismatch' } },
    ]);
    expect(mismatch.problem).toEqual({ kind: 'templateMismatch' });
    expect(mismatch.paper).toEqual({ kind: 'idle' });
    expect(mismatch.real).toEqual({ kind: 'idle' });

    const notFound = runCompare([
      { type: 'COMPARE_REQUESTED', requestId: 2 },
      { type: 'COMPARE_NOT_FOUND', requestId: 2, side: 'REAL' },
    ]);
    expect(notFound.real).toEqual({ kind: 'notFound' });
    expect(notFound.paper).toEqual({ kind: 'idle' });
  });

  it('a failed retry of one side keeps the other side', () => {
    const state = runCompare([
      { type: 'COMPARE_REQUESTED', requestId: 1 },
      {
        type: 'COMPARE_LOADED',
        requestId: 1,
        paper: paperSide,
        real: { kind: 'error', code: 'a' },
      },
      { type: 'COMPARE_SIDE_RETRIED', side: 'REAL', requestId: 2 },
      { type: 'COMPARE_REFUSED', requestId: 2, problem: { kind: 'error', code: 'b' } },
    ]);

    expect(state.paper).toEqual(paperSide);
    expect(state.real).toEqual({ kind: 'error', code: 'b' });
  });
});
