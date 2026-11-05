'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type { BotCommand, BotPage, BotState, BotSummary } from '@/lib/api/guest-api';
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

  const fetchBots = useCallback(async () => {
    setLoad({ kind: 'loading' });
    try {
      setLoad({ kind: 'ready', page: await api.listBots({ state: filter || undefined }) });
    } catch (failure) {
      if (isUnauthenticated(failure)) {
        expire();
        return;
      }
      setLoad({ kind: 'error' });
    }
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

  return (
    <BotsShell title={text.listTitle} testId="bots">
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
          {load.page.items.length === 0 ? (
            <div data-testid="bots-empty">
              <p>{text.empty}</p>
              <Link href="/bots/new" data-testid="bots-create-first">
                {text.createFirst}
              </Link>
            </div>
          ) : (
            <ul data-testid="bots-list">
              {load.page.items.map((bot) => (
                <BotRow
                  key={bot.botId}
                  bot={bot}
                  zone={zone}
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

function BotRow({
  bot,
  zone,
  onCommand,
}: {
  bot: BotSummary;
  zone: string;
  onCommand: (command: BotCommand) => void;
}) {
  const restart = resumesAtText(bot.resumesAt, zone);
  const wizard = isWizardState(bot.state);
  return (
    <li data-testid={`bots-row-${bot.botId}`}>
      <strong>{bot.name}</strong> —{' '}
      <span data-testid={`bots-state-${bot.botId}`}>{stateText(bot.state)}</span>
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
