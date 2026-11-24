'use client';

import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { useCallback, useEffect, useRef, useState } from 'react';
import { RequestCancelled } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type {
  BotCommand,
  BotPage,
  BotState,
  BotSummary,
  LamportsAmount,
  PnlAmount,
  PnlByMode,
} from '@/lib/api/guest-api';
import { SimulatedBadge } from '@/components/SimulatedBadge';
import { RealBadge } from '@/features/real/components/RealBadge';
import {
  BotModeFilter,
  parseModeFilter,
  type BotModeFilterValue,
} from '@/features/bots/BotModeFilter';
import { botListModeMessages as listText } from '@/features/bots/mode-messages';
import { modeMessages } from '@/features/pnl/mode-messages';
import { formatSol, formatUsd, solSign, usdSign } from '@/features/pnl/money-format';
import { useSession } from '@/features/auth/SessionProvider';
import { useRequireSession } from '@/features/auth/useRequireSession';
import { BotsShell } from '@/features/bots/BotsShell';
import { commandErrorText, isUnauthenticated } from '@/features/bots/errors';
import { botMessages } from '@/features/bots/messages';
import {
  isWizardState,
  reasonsText,
  resumesAtText,
  stateText,
  validCommands,
} from '@/features/bots/states';

const text = botMessages;
const STATES: BotState[] = ['DRAFT', 'VALIDATED', 'ACTIVE', 'PAUSED', 'CLOSING_PENDING', 'STOPPED'];
const QUICK: BotCommand[] = ['pause', 'resume'];

type Load = { kind: 'loading' } | { kind: 'error' } | { kind: 'ready'; page: BotPage };
/** The P&L by mode of the lines: `null` while loading or after a failure of the route (each line says so). */
type PnlLoad = { kind: 'loading' } | { kind: 'failed' } | { kind: 'ready'; byMode: PnlByMode };

function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

/** The address of the list for a filter (BR1.2): ALL takes `mode` out of it. */
function addressOf(mode: BotModeFilterValue): string {
  return mode === 'ALL' ? '/bots' : `/bots?mode=${mode}`;
}

/**
 * The bot list (US4.4, BR6.1, BR6.4): every bot of the person with its state and pause reasons in text, "volta a
 * operar às HH:MM" in the person's time zone, the valid actions only, "N de 3 bots ativos", drafts with "Retomar";
 * loading, error and empty are distinct states.
 *
 * 261121-paper-real-unico U3 (BR1.1 to BR1.7; AC2.3.1 to AC2.3.5): the filter by mode lives in `?mode=` (an invalid
 * value is "Todos" and leaves the address without a request of its own); a change replaces the address and reads
 * listBots and C4 with the same `market` (none in "Todos"), each in its own slot with its AbortController, so only the
 * answer of the last request reaches the screen. The screen never sorts nor counts: the order and "Mostrando N de M
 * bots." come from the API. The disclaimer of the simulated mode never shows with the filter Real.
 */
export function BotList({ timeZone }: { timeZone?: string } = {}) {
  const zone = timeZone ?? browserTimeZone();
  const status = useRequireSession('/bots');
  const api = useApi();
  const router = useRouter();
  const search = useSearchParams();
  const { expire } = useSession();
  const requested = search?.get('mode') ?? null;
  const [mode, setMode] = useState<BotModeFilterValue>(() => parseModeFilter(requested));
  const [filter, setFilter] = useState<BotState | ''>('');
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [notice, setNotice] = useState<string | null>(null);
  const [pnl, setPnl] = useState<PnlLoad>({ kind: 'loading' });
  const [totalCount, setTotalCount] = useState<number | null>(null);
  const [focusAll, setFocusAll] = useState(0);
  const slots = useRef<{ list?: AbortController; byMode?: AbortController }>({});

  // BR1.7: an invalid `?mode` is "Todos" and leaves the address.
  useEffect(() => {
    if (requested !== null && parseModeFilter(requested) !== requested) {
      router.replace('/bots');
    }
    // Only the first address is checked; later changes are the screen's own.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(
    () => () => {
      slots.current.list?.abort();
      slots.current.byMode?.abort();
    },
    [],
  );

  const fetchPnl = useCallback(async () => {
    slots.current.byMode?.abort();
    const controller = new AbortController();
    slots.current.byMode = controller;
    setPnl({ kind: 'loading' });
    try {
      const byMode = await api.getPnlByMode(
        { market: mode === 'ALL' ? undefined : mode },
        { signal: controller.signal },
      );
      if (slots.current.byMode === controller) {
        setPnl({ kind: 'ready', byMode });
      }
    } catch (failure) {
      if (failure instanceof RequestCancelled || slots.current.byMode !== controller) {
        return;
      }
      if (!isUnauthenticated(failure)) {
        // F5, BR4.3: a failed P&L never blocks the list; each line says "P&L indisponível agora".
        setPnl({ kind: 'failed' });
      }
    }
  }, [api, mode]);

  const fetchList = useCallback(async () => {
    slots.current.list?.abort();
    const controller = new AbortController();
    slots.current.list = controller;
    setLoad({ kind: 'loading' });
    try {
      const page = await api.listBots(
        { state: filter || undefined, market: mode === 'ALL' ? undefined : mode },
        { signal: controller.signal },
      );
      if (slots.current.list === controller) {
        setLoad({ kind: 'ready', page });
        if (page.totalCount !== undefined) {
          setTotalCount(page.totalCount);
        }
      }
    } catch (failure) {
      if (failure instanceof RequestCancelled || slots.current.list !== controller) {
        return;
      }
      if (isUnauthenticated(failure)) {
        expire();
        return;
      }
      setLoad({ kind: 'error' });
    }
  }, [api, expire, filter, mode]);

  const fetchBots = useCallback(async () => {
    // 261121-paper-real-unico U1 (F5, BR4.3): the list and the P&L by mode in parallel.
    await Promise.all([fetchList(), fetchPnl()]);
  }, [fetchList, fetchPnl]);

  useEffect(() => {
    if (status === 'authenticated') {
      void fetchBots();
    }
  }, [status, fetchBots]);

  function changeMode(next: BotModeFilterValue) {
    if (next === mode) {
      return;
    }
    setMode(next);
    router.replace(addressOf(next));
  }

  function showAll() {
    changeMode('ALL');
    setFocusAll((value) => value + 1);
  }

  async function run(bot: BotSummary, command: BotCommand) {
    setNotice(null);
    try {
      await api.runBotCommand(bot.botId, command);
    } catch (failure) {
      setNotice(commandErrorText(failure));
    }
    await fetchBots();
  }

  const byMode = pnl.kind === 'ready' ? pnl.byMode : null;
  const simulatedVisible =
    mode !== 'REAL' &&
    load.kind === 'ready' &&
    load.page.items.some((bot) => bot.market === 'PAPER');
  const page = load.kind === 'ready' ? load.page : null;
  const hasBots = totalCount !== null && totalCount > 0;
  return (
    <BotsShell title={listText.title} testId="bots" tag={null}>
      <p className="muted">{text.listIntro}</p>
      <p>
        <Link href="/bots/new" data-testid="bots-create">
          {text.create}
        </Link>
      </p>
      {hasBots && (
        <BotModeFilter
          key={`filter-${focusAll}`}
          value={mode}
          onChange={changeMode}
          matchingCount={page?.matchingCount}
          totalCount={page?.totalCount}
          autoFocus
        />
      )}
      <label htmlFor="bots-filter">{text.stateFilter}</label>
      <select
        id="bots-filter"
        value={filter}
        data-testid="bots-filter"
        onChange={(event) => setFilter(event.target.value as BotState | '')}
      >
        <option value="">{text.allStates}</option>
        {STATES.map((state) => (
          <option key={state} value={state}>
            {stateText(state)}
          </option>
        ))}
      </select>
      {notice && (
        <p role="alert" className="notice notice-error" data-testid="bots-notice">
          {notice}
        </p>
      )}
      {load.kind === 'loading' && (
        <p role="status" data-testid="bots-loading">
          {text.loading}
        </p>
      )}
      {load.kind === 'error' && (
        <div role="alert" className="notice notice-error" data-testid="bots-error">
          <p>{text.error}</p>
          <button
            type="button"
            className="secondary"
            data-testid="bots-retry"
            onClick={() => void fetchList()}
          >
            {text.retry}
          </button>
        </div>
      )}
      {page && (
        <>
          <p data-testid="bots-counter">{text.activeCounter(page.activeCount, page.activeLimit)}</p>
          {simulatedVisible && (
            <p role="note" className="notice" data-testid="bots-disclaimer">
              {listText.disclaimer}
            </p>
          )}
          {pnl.kind === 'failed' && page.items.length > 0 && (
            <p className="notice" data-testid="bots-pnl-failed">
              {listText.pnlUnavailable}{' '}
              <button
                type="button"
                className="secondary"
                data-testid="bots-pnl-retry"
                onClick={() => void fetchPnl()}
              >
                {text.retry}
              </button>
            </p>
          )}
          {page.items.length === 0 ? (
            <EmptyList
              mode={mode}
              filtered={filter !== ''}
              totalCount={page.totalCount}
              onShowAll={showAll}
            />
          ) : (
            <ul data-testid="bots-list" className="bot-cards">
              {page.items.map((bot) => (
                <BotRow
                  key={bot.botId}
                  bot={bot}
                  zone={zone}
                  pnl={pnlOf(bot, byMode)}
                  onCommand={(command) => void run(bot, command)}
                />
              ))}
            </ul>
          )}
        </>
      )}
    </BotsShell>
  );
}

/**
 * BR1.3 (AC2.3.4): the person without any bot and the list empty by a filter have different texts; "Ver todos os
 * bots" brings the filter back to "Todos" (the focus goes to its radio).
 */
function EmptyList({
  mode,
  filtered,
  totalCount,
  onShowAll,
}: {
  mode: BotModeFilterValue;
  filtered: boolean;
  totalCount?: number;
  onShowAll: () => void;
}) {
  const none = totalCount === 0 || (totalCount === undefined && mode === 'ALL' && !filtered);
  if (none) {
    return (
      <div data-testid="bots-empty">
        <p>{text.empty}</p>
        <Link href="/bots/new" data-testid="bots-create-first">
          {text.createFirst}
        </Link>
      </div>
    );
  }
  if (mode === 'ALL') {
    return (
      <p data-testid="bots-empty-state" className="notice">
        {listText.emptyState}
      </p>
    );
  }
  return (
    <div data-testid="bots-empty-mode">
      <p>{mode === 'REAL' ? listText.emptyReal : listText.emptyPaper}</p>
      <button type="button" className="secondary" data-testid="bots-show-all" onClick={onShowAll}>
        {listText.showAll}
      </button>
    </div>
  );
}

/** The P&L of one line, as the list shows it: a draft has none, a failed block or route is unavailable. */
type LinePnl =
  | { kind: 'draft' }
  | { kind: 'unavailable' }
  | { kind: 'usd'; amount: PnlAmount }
  | { kind: 'sol'; amount: LamportsAmount };

/**
 * 261121-paper-real-unico U1 (BR4.3): the line of the bot in the block of ITS mode of C4, found by its id, without any
 * arithmetic. A PAPER bot shows US$, a REAL bot SOL, never the other way round.
 */
function pnlOf(bot: BotSummary, byMode: PnlByMode | null): LinePnl {
  if (isWizardState(bot.state)) {
    return { kind: 'draft' };
  }
  if (bot.market === 'REAL') {
    const real = byMode?.real;
    if (real === undefined || real.status !== 'AVAILABLE') {
      return { kind: 'unavailable' };
    }
    const line = real.bots.find((entry) => entry.botId === bot.botId);
    return line ? { kind: 'sol', amount: line.totalLamports } : { kind: 'unavailable' };
  }
  const paper = byMode?.paper;
  if (paper === undefined || paper.status !== 'AVAILABLE') {
    return { kind: 'unavailable' };
  }
  const line = paper.bots.find((entry) => entry.botId === bot.botId);
  return line ? { kind: 'usd', amount: line.total } : { kind: 'unavailable' };
}

function LinePnlView({ pnl, botId }: { pnl: LinePnl; botId: string }) {
  const testId = `bots-pnl-${botId}`;
  if (pnl.kind === 'draft') {
    return <span data-testid={testId}>{listText.draftPnl}</span>;
  }
  if (pnl.kind === 'unavailable') {
    return <span data-testid={testId}>{listText.pnlUnavailable}</span>;
  }
  if (pnl.amount.status === 'UNKNOWN') {
    return (
      <span data-testid={testId} data-status="UNKNOWN">
        {modeMessages.unknown(modeMessages.unknownReasons[pnl.amount.reason] ?? pnl.amount.reason)}
      </span>
    );
  }
  const value = pnl.amount.value;
  const sign = pnl.kind === 'usd' ? usdSign(value) : solSign(value);
  const word =
    sign === 'gain' ? modeMessages.gain : sign === 'loss' ? modeMessages.loss : modeMessages.zero;
  return (
    <span className={`money money-${sign}`} data-testid={testId} data-sign={sign}>
      {pnl.kind === 'usd' ? formatUsd(value) : formatSol(value)}{' '}
      <span className="muted">({word})</span>
    </span>
  );
}

function BotRow({
  bot,
  zone,
  pnl,
  onCommand,
}: {
  bot: BotSummary;
  zone: string;
  pnl: LinePnl;
  onCommand: (command: BotCommand) => void;
}) {
  const restart = resumesAtText(bot.resumesAt, zone);
  const wizard = isWizardState(bot.state);
  return (
    <li data-testid={`bots-row-${bot.botId}`} className="bot-card">
      <strong>{bot.name}</strong>{' '}
      <span data-testid={`bots-mode-${bot.botId}`}>
        {listText.modeColumn}:{' '}
        {bot.market === 'REAL' ? (
          <RealBadge testId={`bots-mode-badge-${bot.botId}`} />
        ) : (
          <SimulatedBadge testId={`bots-mode-badge-${bot.botId}`} />
        )}
      </span>{' '}
      <span>
        {listText.pnlColumn}: <LinePnlView pnl={pnl} botId={bot.botId} />
      </span>{' '}
      — <span data-testid={`bots-state-${bot.botId}`}>{stateText(bot.state)}</span>
      {bot.pauseReasons.length > 0 && (
        <span data-testid={`bots-reasons-${bot.botId}`}>
          : {reasonsText(bot.pauseReasons).join('; ')}
        </span>
      )}
      {restart && <span data-testid={`bots-restart-${bot.botId}`}> ({restart})</span>}{' '}
      <Link
        href={wizard ? `/bots/new/${bot.botId}` : `/bots/${bot.botId}`}
        data-testid={`bots-open-${bot.botId}`}
      >
        {wizard ? text.resumeDraft : text.open}
      </Link>
      {validCommands(bot.state)
        .filter((command) => QUICK.includes(command))
        .map((command) => (
          <button
            key={command}
            type="button"
            className="secondary"
            data-testid={`bots-${command}-${bot.botId}`}
            onClick={() => onCommand(command)}
          >
            {command === 'pause' ? botMessages.detail.pause : botMessages.detail.resume}
          </button>
        ))}
    </li>
  );
}
