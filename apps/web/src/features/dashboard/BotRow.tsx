'use client';

import Link from 'next/link';
import { useLayoutEffect, useRef } from 'react';
import type { BotSummary } from '@/lib/api/guest-api';
import { reasonsText, resumesAtText, stateText } from '@/features/bots/states';
import { dashboardMessages } from '@/features/dashboard/messages';
import { PauseButton } from '@/features/dashboard/PauseButton';

const text = dashboardMessages.bots;

/**
 * One bot in use (BR2.1, BR2.4, BR7.3): the name (link to S5), the state and every pause reason in TEXT with the same
 * words as the bot screen of U5, the automatic restart time in the person's time zone, and "Pausar" only for an ACTIVE
 * bot. When "Pausar" succeeds the button disappears and the focus goes to the state text of this row.
 */
export function BotRow({ bot, timeZone }: { bot: BotSummary; timeZone: string }) {
  const stateRef = useRef<HTMLSpanElement>(null);
  const focusState = useRef(false);
  const reasons = reasonsText(bot.pauseReasons);
  const resumes = resumesAtText(bot.resumesAt, timeZone);

  useLayoutEffect(() => {
    if (focusState.current && bot.state !== 'ACTIVE') {
      focusState.current = false;
      stateRef.current?.focus();
    }
  }, [bot.state]);

  return (
    <li className="dashboard-bot-row" data-testid={`dashboard-bot-row-${bot.botId}`}>
      <Link href={`/bots/${bot.botId}`}>{bot.name}</Link>{' '}
      <span ref={stateRef} tabIndex={-1} data-testid={`dashboard-bot-state-${bot.botId}`}>
        {text.state}: {stateText(bot.state)}
      </span>
      {reasons.length > 0 && (
        <span className="muted" data-testid={`dashboard-bot-reasons-${bot.botId}`}>
          {' '}
          — {reasons.join('; ')}
        </span>
      )}
      {resumes && (
        <span className="muted" data-testid={`dashboard-bot-resumes-${bot.botId}`}>
          {' '}
          — {resumes}
        </span>
      )}{' '}
      {bot.state === 'ACTIVE' && (
        <PauseButton
          botId={bot.botId}
          botName={bot.name}
          onPaused={() => {
            focusState.current = true;
          }}
        />
      )}
    </li>
  );
}
