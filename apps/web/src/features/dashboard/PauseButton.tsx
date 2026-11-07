'use client';

import { useRef } from 'react';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import { useSession } from '@/features/auth/SessionProvider';
import { useAnnounce } from '@/components/shell/NoticesRegion';
import { useDashboard } from '@/features/dashboard/DashboardProvider';
import { dashboardMessages } from '@/features/dashboard/messages';

const text = dashboardMessages.bots;

/**
 * "Pausar" of an ACTIVE bot (BR2.2, BR7.3; NFR4.1, NFR5.2; AC4.8.1): the C4 `pause` command without confirmation,
 * with a NEW Idempotency-Key for each click and the SAME key when "Tentar de novo" repeats that click, so a repeated
 * attempt never creates a second transition. Disabled with "Pausando…" while it waits (a second click sends nothing).
 * `200` shows the confirmed state and moves the focus to the state text of the row (the button is gone); `409`
 * reads the list again; `404` takes the bot off the screen; a technical failure offers "Tentar de novo".
 */
export function PauseButton({
  botId,
  botName,
  onPaused,
  newKey = () => crypto.randomUUID(),
}: {
  botId: string;
  botName: string;
  onPaused: () => void;
  newKey?: () => string;
}) {
  const api = useApi();
  const { expire } = useSession();
  const announce = useAnnounce();
  const { state, dispatch, loader, now, announcedByPause } = useDashboard();
  const pending = state.pendingPauses[botId];
  const sending = useRef(false);

  async function pause() {
    if (sending.current) {
      return;
    }
    sending.current = true;
    const key = pending?.status === 'FAILED' ? pending.idempotencyKey : newKey();
    dispatch({ type: 'PAUSE_SENDING', botId, idempotencyKey: key });
    try {
      const bot = await api.runBotCommand(botId, 'pause', key);
      announcedByPause.add(botId);
      onPaused();
      dispatch({ type: 'PAUSE_SUCCEEDED', bot, at: now().toISOString() });
      announce(text.paused_(botName));
    } catch (failure) {
      handleFailure(failure);
    } finally {
      sending.current = false;
    }
  }

  function handleFailure(failure: unknown) {
    if (failure instanceof ApiError && failure.status === 401) {
      expire();
    } else if (failure instanceof ApiError && failure.status === 409) {
      dispatch({ type: 'PAUSE_CONFLICT', botId });
      void loader.reloadBots();
    } else if (failure instanceof ApiError && failure.status === 404) {
      dispatch({ type: 'BOT_REMOVED', botId });
      void loader.reloadBots();
    } else {
      dispatch({ type: 'PAUSE_FAILED', botId });
    }
  }

  if (pending?.status === 'CONFLICT') {
    return (
      <p className="muted" data-testid={`dashboard-bot-pause-conflict-${botId}`}>
        {text.pauseConflict}
      </p>
    );
  }
  if (pending?.status === 'FAILED') {
    return (
      <span role="alert" data-testid={`dashboard-bot-pause-failed-${botId}`}>
        {text.pauseFailed}{' '}
        <button
          type="button"
          className="secondary"
          data-testid={`dashboard-bot-pause-retry-${botId}`}
          onClick={() => void pause()}
        >
          {dashboardMessages.retry}
        </button>
      </span>
    );
  }
  const isSending = pending?.status === 'SENDING';
  return (
    <button
      type="button"
      className="secondary"
      aria-label={isSending ? text.pausing : text.pauseLabel(botName)}
      disabled={isSending}
      data-testid={`dashboard-bot-pause-button-${botId}`}
      onClick={() => void pause()}
    >
      {isSending ? text.pausing : text.pause}
    </button>
  );
}
