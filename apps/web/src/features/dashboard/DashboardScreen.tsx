'use client';

import { useEffect, useRef } from 'react';
import { AppShell } from '@/components/shell/AppShell';
import { useRequireSession } from '@/features/auth/useRequireSession';
import { BotsCard } from '@/features/dashboard/BotsCard';
import { DashboardHeader } from '@/features/dashboard/DashboardHeader';
import { DashboardProvider } from '@/features/dashboard/DashboardProvider';
import { DataNoticesBanner } from '@/features/dashboard/DataNoticesBanner';
import { PnlCard } from '@/features/dashboard/PnlCard';

/**
 * S2 - Painel, route `/` (US5.3, US5.4; FR12.1, FR12.2; BR1.1, BR6.5). The first screen after the entry without a
 * pending origin. The three cards render in LOADING at once (no call blocks the first paint, NFR3.2), the focus goes
 * to the title of the first card, "Bots PAPER", and an anonymous or expired session goes to the entry with the return
 * to the dashboard (U3).
 */
export function DashboardScreen({
  now,
  timeZone,
  fetchImpl,
}: {
  now?: () => Date;
  timeZone?: string;
  fetchImpl?: typeof fetch;
}) {
  useRequireSession('/');
  const firstCardTitle = useRef<HTMLHeadingElement>(null);
  useEffect(() => firstCardTitle.current?.focus(), []);
  return (
    <AppShell current="dashboard" titleId="dashboard-title">
      <DashboardProvider now={now} timeZone={timeZone} fetchImpl={fetchImpl}>
        <DashboardHeader />
        <DataNoticesBanner />
        <div className="dashboard-cards">
          <BotsCard ref={firstCardTitle} />
          <PnlCard />
        </div>
      </DashboardProvider>
    </AppShell>
  );
}
