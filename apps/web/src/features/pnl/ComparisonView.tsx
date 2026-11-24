'use client';

import { useCallback, useEffect, useId, useReducer, useRef, useState } from 'react';
import { ApiError, RequestCancelled } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type {
  BotSummary,
  PaperComparisonSide,
  PnlComparison,
  PnlPeriodRequest,
  RealComparisonSide,
} from '@/lib/api/guest-api';
import { formatDayMonth } from '@/lib/format/format';
import { failureCode } from '@/features/pnl/by-mode';
import { modeMessages } from '@/features/pnl/mode-messages';
import type { AppliedPeriod } from '@/features/pnl/PeriodSelector';
import { PnlModeBlock } from '@/features/pnl/PnlModeBlock';
import {
  compareReducer,
  INITIAL_COMPARE,
  type CompareSideLoad,
} from '@/features/pnl/pnl-screen-state';

const text = modeMessages.compare;
const PAGE = 50;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** BR5.6: the pair of the comparison, from `?comparar=<paper>,<real>`; either may still be missing. */
export interface ComparisonSelection {
  paperBotId: string | null;
  realBotId: string | null;
}

export const EMPTY_SELECTION: ComparisonSelection = { paperBotId: null, realBotId: null };

/** `?comparar=` of the address; anything that is not two bot ids (or one and an empty part) is an empty selection. */
export function parseComparison(value: string | null | undefined): ComparisonSelection | null {
  if (value === null || value === undefined) {
    return null;
  }
  const [paper = '', real = '', ...rest] = value.split(',');
  const valid = (id: string) => id === '' || UUID.test(id);
  if (rest.length > 0 || !valid(paper) || !valid(real)) {
    return EMPTY_SELECTION;
  }
  return { paperBotId: paper || null, realBotId: real || null };
}

export function comparisonAddress(selection: ComparisonSelection): string {
  return `${selection.paperBotId ?? ''},${selection.realBotId ?? ''}`;
}

/** The period as C6 receives it: the same applied pair as the blocks (BR2.6). */
export function periodRequest(period: AppliedPeriod): PnlPeriodRequest {
  return period.utc ? { kind: 'RANGE', from: period.utc.from, to: period.utc.to } : { kind: 'ALL' };
}

/** BR5.1: the REAL bots that may be compared with a PAPER bot: the same templateId. */
function pairsOf(paper: BotSummary | undefined, bots: BotSummary[]): BotSummary[] {
  if (!paper || !paper.templateId) {
    return [];
  }
  return bots.filter((bot) => bot.market === 'REAL' && bot.templateId === paper.templateId);
}

type BotsLoad = { kind: 'loading' } | { kind: 'error' } | { kind: 'ready'; bots: BotSummary[] };

function sideOf<S>(
  result: { status: string } & Partial<{ correlationId: string }>,
): CompareSideLoad<S> {
  return result.status === 'UNAVAILABLE'
    ? { kind: 'error', code: result.correlationId ?? 'SERVICE_UNAVAILABLE' }
    : { kind: 'ready', side: result as unknown as S };
}

/**
 * 261121-paper-real-unico U3 (BR5.1 to BR5.6, BR2.6, BR6.1 to BR6.3, BR9.2, BR9.3; AC8.1.1 to AC8.1.4): one PAPER bot
 * next to one REAL bot of the same strategy (templateId), each in its own currency, the disclaimer only on the
 * simulated side, no difference, ranking, highlight nor general total. The button opens and closes the region (the
 * focus goes to "Bot simulado" and comes back to the button); the pair lives in `?comparar=`. C6 is read with the
 * period in force; a side UNAVAILABLE has its own "Tentar de novo" that updates only that side; a 422 explains the
 * different strategies and clears the real bot; a 404 of a side says "Bot não encontrado." there and clears its
 * select. With more than 50 bots, the next pages of listBots are read before the options are built.
 */
export function ComparisonView({
  selection,
  onSelect,
  period,
  timeZone,
}: {
  selection: ComparisonSelection | null;
  onSelect: (selection: ComparisonSelection | null) => void;
  period: AppliedPeriod;
  timeZone?: string;
}) {
  const api = useApi();
  const regionId = useId();
  const [open, setOpen] = useState(selection !== null);
  const [bots, setBots] = useState<BotsLoad>({ kind: 'loading' });
  const [state, dispatch] = useReducer(compareReducer, INITIAL_COMPARE);
  const button = useRef<HTMLButtonElement>(null);
  const paperSelect = useRef<HTMLSelectElement>(null);
  const realSelect = useRef<HTMLSelectElement>(null);
  const requestIds = useRef(0);
  const slot = useRef<AbortController | null>(null);
  const [focus, setFocus] = useState<{ target: 'paper' | 'real' | 'button'; at: number } | null>(
    null,
  );
  const current = selection ?? EMPTY_SELECTION;
  /** The single pair is chosen for the person only when they open the region (an address keeps what it says). */
  const autoPick = useRef(selection === null);

  useEffect(() => () => slot.current?.abort(), []);

  useEffect(() => {
    if (focus === null) {
      return;
    }
    const target =
      focus.target === 'paper'
        ? paperSelect.current
        : focus.target === 'real'
          ? realSelect.current
          : button.current;
    target?.focus();
  }, [focus]);

  // The bots of the person (every page) once the region opens.
  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const controller = new AbortController();
    setBots({ kind: 'loading' });
    void (async () => {
      try {
        const all: BotSummary[] = [];
        let cursor: string | null = null;
        do {
          const page = await api.listBots({ cursor, limit: PAGE }, { signal: controller.signal });
          all.push(...page.items);
          cursor = page.nextCursor;
        } while (cursor);
        setBots({ kind: 'ready', bots: all });
      } catch (failure) {
        if (!(failure instanceof RequestCancelled)) {
          setBots({ kind: 'error' });
        }
      }
    })();
    return () => controller.abort();
  }, [api, open]);

  // BR5.1: with a single possible pair, both come chosen.
  useEffect(() => {
    if (
      bots.kind !== 'ready' ||
      !autoPick.current ||
      current.paperBotId !== null ||
      current.realBotId !== null
    ) {
      return;
    }
    autoPick.current = false;
    const pairs = bots.bots
      .filter((bot) => bot.market === 'PAPER')
      .flatMap((paper) => pairsOf(paper, bots.bots).map((real) => [paper, real] as const));
    const only = pairs.length === 1 ? pairs[0] : undefined;
    if (only) {
      onSelect({ paperBotId: only[0].botId, realBotId: only[1].botId });
    }
    // Only when the bots arrive.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bots]);

  const read = useCallback(
    async (paperBotId: string, realBotId: string, side?: 'PAPER' | 'REAL') => {
      slot.current?.abort();
      const controller = new AbortController();
      slot.current = controller;
      requestIds.current += 1;
      const requestId = requestIds.current;
      dispatch(
        side
          ? { type: 'COMPARE_SIDE_RETRIED', side, requestId }
          : { type: 'COMPARE_REQUESTED', requestId },
      );
      try {
        const answer: PnlComparison = await api.comparePnl(
          { paperBotId, realBotId, period: periodRequest(period) },
          { signal: controller.signal },
        );
        dispatch({
          type: 'COMPARE_LOADED',
          requestId,
          paper: sideOf(answer.paper),
          real: sideOf(answer.real),
        });
      } catch (failure) {
        if (failure instanceof RequestCancelled) {
          return;
        }
        if (failure instanceof ApiError && failure.status === 422) {
          dispatch({ type: 'COMPARE_REFUSED', requestId, problem: { kind: 'templateMismatch' } });
          onSelect({ paperBotId, realBotId: null });
          setFocus({ target: 'real', at: requestId });
          return;
        }
        if (failure instanceof ApiError && failure.status === 404) {
          const notFound = failure.reason === 'PAPER_BOT' ? 'PAPER' : 'REAL';
          dispatch({ type: 'COMPARE_NOT_FOUND', requestId, side: notFound });
          onSelect(
            notFound === 'PAPER'
              ? { paperBotId: null, realBotId: null }
              : { paperBotId, realBotId: null },
          );
          setFocus({ target: notFound === 'PAPER' ? 'paper' : 'real', at: requestId });
          return;
        }
        dispatch({
          type: 'COMPARE_REFUSED',
          requestId,
          problem: { kind: 'error', code: failureCode(failure) },
        });
      }
    },
    // onSelect changes the address only; the period is read when the reading starts.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [api, period],
  );

  // C6 with the pair and the period in force (BR2.6).
  useEffect(() => {
    if (!open || current.paperBotId === null || current.realBotId === null) {
      return;
    }
    void read(current.paperBotId, current.realBotId);
  }, [open, current.paperBotId, current.realBotId, read]);

  function toggle() {
    if (open) {
      setOpen(false);
      slot.current?.abort();
      onSelect(null);
      setFocus({ target: 'button', at: Date.now() });
      return;
    }
    setOpen(true);
    autoPick.current = true;
    onSelect(EMPTY_SELECTION);
    setFocus({ target: 'paper', at: Date.now() });
  }

  function choosePaper(botId: string) {
    const list = bots.kind === 'ready' ? bots.bots : [];
    const paper = list.find((bot) => bot.botId === botId);
    const keepReal =
      current.realBotId !== null &&
      pairsOf(paper, list).some((bot) => bot.botId === current.realBotId);
    // A new PAPER bot without a REAL of its strategy keeps no real bot (BR5.1).
    const pairs = pairsOf(paper, list);
    const real = keepReal
      ? current.realBotId
      : pairs.length === 1
        ? (pairs[0]?.botId ?? null)
        : null;
    dispatch({ type: 'COMPARE_RESET' });
    onSelect({ paperBotId: botId || null, realBotId: botId ? real : null });
  }

  function chooseReal(botId: string) {
    dispatch({ type: 'COMPARE_RESET' });
    onSelect({ paperBotId: current.paperBotId, realBotId: botId || null });
  }

  const list = bots.kind === 'ready' ? bots.bots : [];
  const paperBots = list.filter((bot) => bot.market === 'PAPER');
  const paperBot = paperBots.find((bot) => bot.botId === current.paperBotId);
  const realOptions = pairsOf(paperBot, list);
  const anyPair = paperBots.some((paper) => pairsOf(paper, list).length > 0);
  const nameOf = (botId: string | null, fallback: string) =>
    list.find((bot) => bot.botId === botId)?.name ?? fallback;
  return (
    <section className="comparison" data-testid="compare">
      <button
        ref={button}
        type="button"
        className="secondary"
        aria-expanded={open}
        aria-controls={regionId}
        data-testid="compare-toggle"
        onClick={toggle}
      >
        {open ? text.close : text.open}
      </button>
      <div id={regionId} role="region" aria-labelledby={`${regionId}-title`} hidden={!open}>
        {open && (
          <>
            <h2 id={`${regionId}-title`}>{text.heading}</h2>
            {bots.kind === 'loading' && (
              <p role="status" data-testid="compare-bots-loading">
                {text.loadingBots}
              </p>
            )}
            {bots.kind === 'error' && (
              <p role="alert" className="notice notice-error" data-testid="compare-bots-error">
                {text.botsError}
              </p>
            )}
            {bots.kind === 'ready' && !anyPair && (
              <p className="notice" data-testid="compare-no-pair">
                {text.noPair}
              </p>
            )}
            <div className="comparison-selects">
              <div>
                <label htmlFor={`${regionId}-paper`}>{text.paperSelect}</label>
                <select
                  id={`${regionId}-paper`}
                  ref={paperSelect}
                  value={current.paperBotId ?? ''}
                  data-testid="compare-paper-select"
                  onChange={(event) => choosePaper(event.target.value)}
                >
                  <option value="">{text.choose}</option>
                  {paperBots.map((bot) => (
                    <option key={bot.botId} value={bot.botId}>
                      {bot.name}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label htmlFor={`${regionId}-real`}>{text.realSelect}</label>
                <select
                  id={`${regionId}-real`}
                  ref={realSelect}
                  value={current.realBotId ?? ''}
                  disabled={current.paperBotId === null}
                  aria-describedby={
                    current.paperBotId === null ? `${regionId}-real-help` : undefined
                  }
                  data-testid="compare-real-select"
                  onChange={(event) => chooseReal(event.target.value)}
                >
                  <option value="">{text.choose}</option>
                  {realOptions.map((bot) => (
                    <option key={bot.botId} value={bot.botId}>
                      {bot.name}
                    </option>
                  ))}
                </select>
                {current.paperBotId === null && (
                  <p className="muted" id={`${regionId}-real-help`}>
                    {text.choosePaperFirst}
                  </p>
                )}
              </div>
            </div>
            {state.problem?.kind === 'templateMismatch' && (
              <p
                role="alert"
                className="notice notice-error"
                data-testid="compare-template-mismatch"
              >
                {text.templateMismatch}
              </p>
            )}
            {state.problem?.kind === 'error' && (
              <div role="alert" className="notice notice-error" data-testid="compare-error">
                <p>{text.error}</p>
                <p className="muted">{modeMessages.supportCode(state.problem.code)}</p>
              </div>
            )}
            <div className="comparison-sides" data-testid="compare-sides">
              <ComparisonSide
                market="PAPER"
                load={state.paper}
                name={nameOf(current.paperBotId, '')}
                timeZone={timeZone}
                onRetry={() =>
                  current.paperBotId &&
                  current.realBotId &&
                  void read(current.paperBotId, current.realBotId, 'PAPER')
                }
              />
              <ComparisonSide
                market="REAL"
                load={state.real}
                name={nameOf(current.realBotId, '')}
                timeZone={timeZone}
                onRetry={() =>
                  current.paperBotId &&
                  current.realBotId &&
                  void read(current.paperBotId, current.realBotId, 'REAL')
                }
              />
            </div>
            {(state.paper.kind === 'ready' || state.real.kind === 'ready') && (
              <p className="muted" data-testid="compare-currencies">
                {text.currencies}
              </p>
            )}
          </>
        )}
      </div>
    </section>
  );
}

function ComparisonSide({
  market,
  load,
  name,
  timeZone,
  onRetry,
}: {
  market: 'PAPER' | 'REAL';
  load: CompareSideLoad<unknown>;
  name: string;
  timeZone?: string;
  onRetry: () => void;
}) {
  const testId = `compare-${market}`;
  if (load.kind === 'idle') {
    return null;
  }
  if (load.kind === 'notFound') {
    return (
      <p role="alert" className="notice notice-error" data-testid={`${testId}-not-found`}>
        {text.notFound}
      </p>
    );
  }
  if (market === 'PAPER') {
    const side = load.kind === 'ready' ? (load as { side: PaperComparisonSide }).side : null;
    const label = side?.line.name ?? name;
    return (
      <PnlModeBlock
        variant="bot"
        market="PAPER"
        testId={testId}
        headingLevel={3}
        heading={text.paperSide(label)}
        totalLabel={text.paperTotal}
        disclaimer={side?.disclaimer}
        onRetry={onRetry}
        load={
          side
            ? { kind: 'ready', block: side.line }
            : load.kind === 'error'
              ? { kind: 'error', code: load.code }
              : { kind: 'loading' }
        }
      >
        {side && (
          <p className="muted" data-testid={`${testId}-created`}>
            {text.createdAt(formatDayMonth(side.createdAt, timeZone))}
          </p>
        )}
      </PnlModeBlock>
    );
  }
  const side = load.kind === 'ready' ? (load as { side: RealComparisonSide }).side : null;
  const label = side?.line.name ?? name;
  return (
    <PnlModeBlock
      variant="bot"
      market="REAL"
      testId={testId}
      headingLevel={3}
      heading={text.realSide(label)}
      totalLabel={text.realTotal}
      onRetry={onRetry}
      load={
        side
          ? { kind: 'ready', block: side.line }
          : load.kind === 'error'
            ? { kind: 'error', code: load.code }
            : { kind: 'loading' }
      }
    >
      {side && (
        <p className="muted" data-testid={`${testId}-created`}>
          {text.createdAt(formatDayMonth(side.createdAt, timeZone))}
        </p>
      )}
    </PnlModeBlock>
  );
}
