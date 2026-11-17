'use client';

import { useCallback, useRef, useState } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type { RealAdminKillSwitch } from '@/lib/api/admin-api';
import { browserTimeZone, dateTime } from '@/lib/format';
import { useAdminAction } from '@/features/real/hooks/useAdminAction';
import { useAdminLoad } from '@/features/real/hooks/useAdminLoad';
import { adminRealMessages as text } from '@/features/real/messages';
import { AdminActionDialog } from '@/features/real/components/AdminActionDialog';
import { RealLoadError, RealSection } from '@/features/real/components/RealSection';

const copy = text.killSwitch;

/**
 * 261113-trading-real-bot U6, A1 `/real/kill-switch` (US8.1; BR9.2, BR9.4; S35): the kill switch of the bot wallets on
 * its own screen titled "Kill switch REAL", apart from the page `kill-switch` of the PAPER. On, it says "Novas ordens
 * REAL bloqueadas para todos; saques liberados". Switching it on or off asks the reason and the confirmation.
 */
export function RealKillSwitchScreen({ timeZone }: { timeZone?: string }) {
  const api = useApi();
  const zone = timeZone ?? browserTimeZone();
  const { load, reload } = useAdminLoad(useCallback(() => api.getRealKillSwitch(), [api]));
  const [current, setCurrent] = useState<RealAdminKillSwitch | null>(null);
  const [open, setOpen] = useState(false);
  const opener = useRef<HTMLButtonElement>(null);
  const shown = current ?? (load.kind === 'ready' ? load.data : null);
  const active = Boolean(shown?.active);
  const change = useAdminAction(
    (body: { reason: string; activate: boolean }, key: string) =>
      body.activate
        ? api.activateRealKillSwitch({ reason: body.reason }, key)
        : api.deactivateRealKillSwitch({ reason: body.reason }, key),
    api.newKey,
  );

  return (
    <RealSection title={copy.title} testId="admin-real-kill-switch">
      <p className="note">{copy.separate}</p>
      {load.kind === 'loading' && !shown && <p role="status">{text.loading}</p>}
      {load.kind === 'error' && !shown && (
        <RealLoadError
          error={load.error}
          message={copy.error}
          onRetry={reload}
          testId="admin-real-kill-switch-error"
        />
      )}
      {shown && (
        <>
          <p role="status" data-testid="admin-real-kill-switch-state">
            <span aria-hidden="true">{active ? '⛔ ' : '○ '}</span>
            {active ? copy.on : copy.off} · {active ? copy.onEffect : copy.offEffect}
          </p>
          {active && shown.activatedAt && <p>{copy.since(dateTime(shown.activatedAt, zone))}</p>}
          {shown.reason && <p>{shown.reason}</p>}
          {typeof shown.cancelledOrders === 'number' && shown.cancelledOrders > 0 && (
            <p>{copy.cancelled(shown.cancelledOrders)}</p>
          )}
          <button
            ref={opener}
            type="button"
            onClick={() => setOpen(true)}
            data-testid="admin-real-kill-switch-toggle"
          >
            {active ? copy.deactivate : copy.activate}
          </button>
        </>
      )}
      {open && (
        <AdminActionDialog
          title={active ? copy.deactivateTitle : copy.activateTitle}
          effect={active ? copy.offEffect : copy.onEffect}
          testId="admin-real-kill-switch-dialog"
          state={change.state}
          returnFocusTo={opener.current}
          onClose={() => {
            change.reset();
            setOpen(false);
          }}
          onSubmit={(reason) =>
            void change.submit({ reason, activate: !active }).then((result) => {
              if (result) {
                setCurrent(result);
                setOpen(false);
              }
            })
          }
        />
      )}
    </RealSection>
  );
}
