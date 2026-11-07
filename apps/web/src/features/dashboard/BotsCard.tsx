'use client';

import Link from 'next/link';
import { forwardRef } from 'react';
import { useDashboard } from '@/features/dashboard/DashboardProvider';
import { BotRow } from '@/features/dashboard/BotRow';
import { CardError } from '@/features/dashboard/CardError';
import type { BotsSlice } from '@/features/dashboard/dashboard-reducer';
import { LastTradeLine } from '@/features/dashboard/LastTradeLine';
import { dashboardMessages } from '@/features/dashboard/messages';

const text = dashboardMessages.bots;

/**
 * The "Bots PAPER" card (US5.3; BR1.2, BR1.3, BR2.1 to BR2.4, BR6.5, BR6.6): its own loading, error with "Tentar de
 * novo" and empty state; with bots in use, "Ativos: N de 3" and "Pausados: N", one row per bot in use, the last order,
 * and the counts of drafts and stopped bots with a link to the list. The title says "MODO PAPER (simulado)" and gets
 * the first focus of the screen.
 */
export const BotsCard = forwardRef<HTMLHeadingElement>(function BotsCard(_, title) {
  const { state, loader, timeZone } = useDashboard();
  const bots = state.bots;
  const zone = timeZone ?? Intl.DateTimeFormat().resolvedOptions().timeZone;
  return (
    <section
      aria-labelledby="dashboard-bots-title"
      aria-busy={bots.status === 'LOADING'}
      className="dashboard-card"
      data-testid="dashboard-bots-card"
    >
      <h2 id="dashboard-bots-title" ref={title} tabIndex={-1}>
        {text.title} <span className="tag">{dashboardMessages.paperLabel}</span>
      </h2>
      {bots.status === 'LOADING' && (
        <p role="status" data-testid="dashboard-bots-loading">
          {text.loading}
        </p>
      )}
      {bots.status === 'ERROR' && (
        <CardError card="bots" message={text.error} onRetry={() => void loader.reloadBots(true)} />
      )}
      {bots.status === 'EMPTY' && <BotsEmptyState bots={bots} />}
      {bots.status === 'READY' && (
        <>
          <p data-testid="dashboard-bots-counts">
            {text.counts(bots.activeCount, bots.activeLimit)} · {text.paused(bots.pausedCount)}
          </p>
          <ul className="dashboard-bot-rows">
            {bots.inUse.map((bot) => (
              <BotRow key={bot.botId} bot={bot} timeZone={zone} />
            ))}
          </ul>
          <LastTradeLine slice={state.lastTrade} names={bots.names} timeZone={zone} />
          <OtherBots bots={bots} />
        </>
      )}
    </section>
  );
});

/** Drafts and stopped bots appear only as counts with a link to the list (BR2.1). */
function OtherBots({ bots }: { bots: BotsSlice }) {
  if (bots.draftCount === 0 && bots.stoppedCount === 0) {
    return null;
  }
  const parts = [
    bots.draftCount > 0 ? text.draftsCount(bots.draftCount) : null,
    bots.stoppedCount > 0 ? text.stopped(bots.stoppedCount) : null,
  ].filter(Boolean);
  return (
    <p data-testid="dashboard-bots-others">
      {parts.join(' · ')} —{' '}
      <Link href="/bots" data-testid="dashboard-all-bots-link">
        {text.allBots}
      </Link>
    </p>
  );
}

/**
 * No bot in use (BR1.3, AC5.3.1): "Abrir scanner" and "Criar bot PAPER"; with drafts, "Você tem N rascunhos" and
 * "Continuar rascunho" (the first item of state=DRAFT, else of state=VALIDATED, in the order of the route); with
 * stopped bots, their count with a link to the list.
 */
function BotsEmptyState({ bots }: { bots: BotsSlice }) {
  return (
    <div data-testid="dashboard-bots-empty">
      <p>{text.empty}</p>
      <p>
        <Link href="/scanner" data-testid="dashboard-open-scanner-link">
          {text.openScanner}
        </Link>{' '}
        ·{' '}
        <Link href="/bots/new" data-testid="dashboard-create-bot-link">
          {text.createBot}
        </Link>
      </p>
      {bots.draftCount > 0 && bots.draftToContinueId && (
        <p data-testid="dashboard-bots-drafts">
          {text.drafts(bots.draftCount)} —{' '}
          <Link
            href={`/bots/new/${bots.draftToContinueId}`}
            data-testid="dashboard-continue-draft-link"
          >
            {text.continueDraft}
          </Link>
        </p>
      )}
      {bots.stoppedCount > 0 && (
        <p data-testid="dashboard-bots-stopped">
          <Link href="/bots" data-testid="dashboard-all-bots-link">
            {text.stopped(bots.stoppedCount)}
          </Link>
        </p>
      )}
    </div>
  );
}
