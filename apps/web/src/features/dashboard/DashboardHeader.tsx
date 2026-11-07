'use client';

import { formatClock } from '@/lib/format/format';
import { useDashboard } from '@/features/dashboard/DashboardProvider';
import { dashboardMessages as text } from '@/features/dashboard/messages';

/**
 * h1 "Painel" (equal to the menu item, BR6.2) and "Atualizado às HH:MM", the local time of the last event received
 * (BR5.2). The time is NOT a live region: it changes with every heartbeat and is never announced (BR7.1).
 */
export function DashboardHeader() {
  const { state, timeZone } = useDashboard();
  const last = state.channel.lastEventAt;
  return (
    <div className="dashboard-header">
      <h1 id="dashboard-title" data-testid="dashboard-title">
        {text.title}
      </h1>
      <p className="muted" data-testid="dashboard-updated-at">
        {last ? text.updatedAt(formatClock(new Date(last), timeZone)) : text.notYetUpdated}
      </p>
    </div>
  );
}
