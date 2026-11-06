'use client';

import { useRef, useState } from 'react';
import { browserTimeZone, dateTime } from '@/lib/format';
import { messages } from '@/lib/i18n/messages';
import { useFailure } from '@/features/auth/use-failure';
import { KillSwitchDialog } from '@/features/killswitch/KillSwitchDialog';
import { useKillSwitch } from '@/features/layout/KillSwitchContext';

const text = messages.killSwitch;

/**
 * The kill switch screen (F2, F3; BR2.7, BR7.2; AC7.2.1, AC7.2.4): the current state, who (masked wallet), when and
 * why; the pending bots and the reconciliation in progress; one action, "Acionar" or "Desligar", each behind its
 * confirmation dialog. The answer of the change replaces the state of the banner on every screen.
 */
export function KillSwitchPage({ timeZone = browserTimeZone() }: { timeZone?: string }) {
  const { killSwitch, failed, replace } = useKillSwitch();
  const handled = useFailure();
  const [dialog, setDialog] = useState<'ON' | 'OFF' | null>(null);
  const opener = useRef<HTMLButtonElement>(null);

  if (failed && !killSwitch) {
    return (
      <p role="alert" className="field-error" data-testid="kill-switch-error">
        {text.error}
      </p>
    );
  }
  if (!killSwitch) {
    return (
      <p role="status" data-testid="kill-switch-loading">
        {text.loading}
      </p>
    );
  }
  const on = killSwitch.state === 'ON';
  return (
    <section aria-labelledby="kill-switch-title" data-testid="kill-switch-page">
      <h1 id="kill-switch-title">{text.title}</h1>
      <p data-testid="kill-switch-state" className={on ? 'state-on' : 'state-off'}>
        <span aria-hidden="true">{on ? '🛑 ' : '✅ '}</span>
        {text.state(on ? text.stateOn : text.stateOff)}
      </p>
      {killSwitch.changedAt ? (
        <p data-testid="kill-switch-change">
          {text.changedBy(killSwitch.changedBy ?? '—')}{' '}
          {text.changedAt(dateTime(killSwitch.changedAt, timeZone))}
        </p>
      ) : (
        <p data-testid="kill-switch-change">{text.neverChanged}</p>
      )}
      {killSwitch.reason ? (
        <p data-testid="kill-switch-reason">{text.reason(killSwitch.reason)}</p>
      ) : null}
      {killSwitch.pendingBots > 0 ? (
        <p data-testid="kill-switch-pending">{text.pending(killSwitch.pendingBots)}</p>
      ) : null}
      {killSwitch.reconcileNeeded ? (
        <p data-testid="kill-switch-reconciling">{text.reconciling}</p>
      ) : null}
      <button
        type="button"
        ref={opener}
        className={on ? undefined : 'danger'}
        data-testid={on ? 'kill-switch-deactivate' : 'kill-switch-activate'}
        onClick={() => setDialog(on ? 'OFF' : 'ON')}
      >
        {on ? text.deactivate : text.activate}
      </button>
      {dialog ? (
        <KillSwitchDialog
          target={dialog}
          activeBots={killSwitch.activeBotsToPause ?? 0}
          returnFocusTo={opener.current}
          onClose={() => setDialog(null)}
          onFailure={handled}
          onChanged={(next) => {
            replace({ ...next, activeBotsToPause: killSwitch.activeBotsToPause });
            setDialog(null);
          }}
        />
      ) : null}
    </section>
  );
}
