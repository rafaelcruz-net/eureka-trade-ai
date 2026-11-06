'use client';

import { messages } from '@/lib/i18n/messages';
import { useKillSwitch } from '@/features/layout/KillSwitchContext';

/**
 * BR7.1, BR2.3: on every administrative screen while the kill switch is ON, "Kill switch ACIONADO" in text and icon
 * (never only color), plus the pending bots and the reconciliation in progress when there are any.
 */
export function KillSwitchBanner() {
  const { killSwitch } = useKillSwitch();
  if (!killSwitch || killSwitch.state !== 'ON') {
    return null;
  }
  return (
    <div role="status" className="kill-switch-banner" data-testid="kill-switch-banner">
      <p>
        <span aria-hidden="true">🛑 </span>
        {messages.banner.on}
      </p>
      {killSwitch.pendingBots > 0 ? (
        <p data-testid="kill-switch-banner-pending">
          {messages.banner.pending(killSwitch.pendingBots)}
        </p>
      ) : null}
      {killSwitch.reconcileNeeded ? (
        <p data-testid="kill-switch-banner-reconciling">{messages.banner.reconciling}</p>
      ) : null}
    </div>
  );
}
