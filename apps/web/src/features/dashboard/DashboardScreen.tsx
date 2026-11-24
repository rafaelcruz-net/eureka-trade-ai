'use client';

import { useEffect, useRef } from 'react';
import { AppShell } from '@/components/shell/AppShell';
import { useRequireSession } from '@/features/auth/useRequireSession';
import { BotsCard } from '@/features/dashboard/BotsCard';
import { DashboardHeader } from '@/features/dashboard/DashboardHeader';
import { DashboardModeCards } from '@/features/dashboard/DashboardModeCards';
import { DashboardProvider } from '@/features/dashboard/DashboardProvider';
import { DataNoticesBanner } from '@/features/dashboard/DataNoticesBanner';

/**
 * S2 - Painel, route `/` (US5.3, US5.4; FR12.1, FR12.2; BR1.1, BR6.5). The first screen after the entry without a
 * pending origin. The cards render in LOADING at once (no call blocks the first paint, NFR3.2), the focus goes to the
 * title of the first card, "Bots PAPER", and an anonymous or expired session goes to the entry with the return to the
 * dashboard (U3).
 *
 * 261121-paper-real-unico U3 (BR7.1): the two cards by mode ("Resultado simulado" and "Resultado real", since the
 * start, never added) replace the PnlCard of the UTC day and the DashboardRealPnl; the BotsCard stays.
 */
export function DashboardScreen({ now, timeZone }: { now?: () => Date; timeZone?: string }) {
  useRequireSession('/');
  const firstCardTitle = useRef<HTMLHeadingElement>(null);
  useEffect(() => firstCardTitle.current?.focus(), []);
  return (
    <AppShell current="dashboard" titleId="dashboard-title">
      <DashboardProvider now={now} timeZone={timeZone}>
        <DashboardHeader />
        <DataNoticesBanner />
        <div className="dashboard-cards">
          <BotsCard ref={firstCardTitle} />
          <DashboardModeCards />
        </div>
      </DashboardProvider>
    </AppShell>
  );
}
