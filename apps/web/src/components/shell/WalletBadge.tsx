'use client';

import { navMessages } from '@/features/dashboard/messages';
import { resetSessionAccountCache, useSessionAccount } from '@/components/shell/session-account';

/**
 * The abbreviated wallet of the shell (BR6.4, NFR7.1): only `walletAddressMasked` of C3 (4 first and 4 last
 * characters), read ONCE per session and kept in memory (session-account.ts, shared with the footer); the full address
 * never reaches this component. A failed read shows "Carteira" without an address (the Account screen shows its own
 * error).
 */

/** Forgets the masked wallet of the session (sign-out; tests). */
export function resetWalletCache(): void {
  resetSessionAccountCache();
}

export function WalletBadge() {
  const account = useSessionAccount();
  return (
    <span className="app-wallet" data-testid="app-wallet-badge">
      {account ? navMessages.walletLabel(account.walletMasked) : navMessages.wallet}
    </span>
  );
}
