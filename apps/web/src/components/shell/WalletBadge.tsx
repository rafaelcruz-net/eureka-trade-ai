'use client';

import { useEffect, useState } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type { GuestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { navMessages } from '@/features/dashboard/messages';
import { useSession } from '@/features/auth/SessionProvider';

/**
 * The abbreviated wallet of the shell (BR6.4, NFR7.1): only `walletAddressMasked` of C3 (4 first and 4 last
 * characters), read ONCE per session and kept in memory; the full address never reaches this component. A failed read
 * shows "Carteira" without an address (the Account screen shows its own error).
 */
let cached: string | null | undefined;
let inflight: Promise<string | null> | null = null;

// A new session (sign-out, expiry) reads the account again; a renewed token keeps the same session.
tokenStore.subscribe((token) => {
  if (token === null) {
    resetWalletCache();
  }
});

/** Forgets the masked wallet of the session (sign-out; tests). */
export function resetWalletCache(): void {
  cached = undefined;
  inflight = null;
}

function readWallet(api: GuestApi): Promise<string | null> {
  if (cached !== undefined) {
    return Promise.resolve(cached);
  }
  if (!inflight) {
    inflight = api.getAccount().then(
      (account) => {
        cached = account.walletAddressMasked;
        return cached;
      },
      () => {
        inflight = null;
        return null;
      },
    );
  }
  return inflight;
}

export function WalletBadge() {
  const api = useApi();
  const { status } = useSession();
  const [masked, setMasked] = useState<string | null>(cached ?? null);

  useEffect(() => {
    if (status !== 'authenticated') {
      return undefined;
    }
    let active = true;
    void readWallet(api).then((value) => {
      if (active) {
        setMasked(value);
      }
    });
    return () => {
      active = false;
    };
  }, [api, status]);

  return (
    <span className="app-wallet" data-testid="app-wallet-badge">
      {masked ? navMessages.walletLabel(masked) : navMessages.wallet}
    </span>
  );
}
