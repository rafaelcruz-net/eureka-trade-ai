'use client';

import { useMemo } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import { realReads } from '@/features/real/api';
import { useLoad } from '@/features/real/hooks/useLoad';
import { useSessionReal } from '@/features/real/session-real';
import { RealPnlCard } from '@/features/real/components/RealPnlCard';

/**
 * 261113-trading-real-bot U6 (BR7.2, US4.2, S31; U6-NFR2.1): the P&L REAL on the dashboard, in its OWN card beside the
 * card of the PAPER, only for a person with an active bot wallet. It reads only `GET /real/pnl`; the dashboard never
 * adds the two.
 */
export function DashboardRealPnl() {
  const real = useSessionReal();
  if (!real?.mode) {
    return null;
  }
  return <Card />;
}

function Card() {
  const api = useApi();
  const reads = useMemo(() => realReads(api), [api]);
  const pnl = useLoad(reads.pnl);
  return <RealPnlCard load={pnl.load} onRetry={pnl.reload} />;
}
