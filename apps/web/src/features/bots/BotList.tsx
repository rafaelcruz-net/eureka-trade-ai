'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
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

function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

/**
 * The bot list (US4.4, BR6.1, BR6.4): every bot of the person with its state and pause reasons in text, "volta a
 * operar às HH:MM" in the person's time zone, the valid actions only, "N de 3 bots ativos", drafts with "Retomar";
 * loading, error and empty are distinct states.
 */
export function BotList({ timeZone }: { timeZone?: string } = {}) {
  const zone = timeZone ?? browserTimeZone();
  const status = useRequireSession('/bots');
  const api = useApi();
  const { expire } = useSession();
  const [filter, setFilter] = useState<BotState | ''>('');
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [notice, setNotice] = useState<string | null>(null);
  const [byMode, setByMode] = useState<PnlByMode | null>(null);

  const fetchBots = useCallback(async () => {
    setLoad({ kind: 'loading' });
    // 261121-paper-real-unico U1 (F5, BR4.3): the list and the P&L by mode in parallel; a failed P&L never blocks
    // the list, each line just says "P&L indisponível agora".
    const pnl = api.getPnlByMode().then(
      (answer) => setByMode(answer),
      (failure: unknown) => {
        if (!isUnauthenticated(failure)) {
          setByMode(null);
        }
      },
    );
    try {
      setLoad({ kind: 'ready', page: await api.listBots({ state: filter || undefined }) });
    } catch (failure) {
      if (isUnauthenticated(failure)) {
        expire();
        return;
      }
      setLoad({ kind: 'error' });
    }
    await pnl;
  }, [api, expire, filter]);

  useEffect(() => {
    if (status === 'authenticated') {
      void fetchBots();
    }
  }, [status, fetchBots]);

  async function run(bot: BotSummary, command: BotCommand) {
    setNotice(null);
    try {
      await api.runBotCommand(bot.botId, command);
    } catch (failure) {
      setNotice(commandErrorText(failure));
    }
    await fetchBots();
  }

  const simulatedVisible =
    load.kind === 'ready' && load.page.items.some((bot) => bot.market === 'PAPER');
  return (
    <BotsShell title={listText.title} testId="bots" tag={null}>
      <p className="muted">{text.listIntro}</p>
      <p>
        <Link href="/bots/new" data-testid="bots-create">
          {text.create}
        </Link>
      </p>
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
            onClick={() => void fetchBots()}
          >
            {text.retry}
          </button>
        </div>
      )}
      {load.kind === 'ready' && (
        <>
          <p data-testid="bots-counter">
            {text.activeCounter(load.page.activeCount, load.page.activeLimit)}
          </p>
          {simulatedVisible && (
            <p role="note" className="notice" data-testid="bots-disclaimer">
              {listText.disclaimer}
            </p>
          )}
          {load.page.items.length === 0 ? (
            <div data-testid="bots-empty">
              <p>{text.empty}</p>
              <Link href="/bots/new" data-testid="bots-create-first">
                {text.createFirst}
              </Link>
            </div>
          ) : (
            <ul data-testid="bots-list" className="bot-cards">
              {load.page.items.map((bot) => (
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
    if (byMode === null || byMode.real.status !== 'AVAILABLE') {
      return { kind: 'unavailable' };
    }
    const line = byMode.real.bots.find((entry) => entry.botId === bot.botId);
    return line ? { kind: 'sol', amount: line.totalLamports } : { kind: 'unavailable' };
  }
  if (byMode === null || byMode.paper.status !== 'AVAILABLE') {
    return { kind: 'unavailable' };
  }
  const line = byMode.paper.bots.find((entry) => entry.botId === bot.botId);
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
