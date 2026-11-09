'use client';

import { useEffect, useState } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type { GuestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { useSession } from '@/features/auth/SessionProvider';

/**
 * What the shell shows of the account of the session (BR6.4, NFR7.1; u1-legal-documents BR1.11): the masked wallet
 * (`walletAddressMasked` of C3, never the full address) and the accepted version of the terms. Read ONCE per session
 * and kept in memory; a new session (sign-out, expiry) reads it again, a renewed token keeps it.
 */
export interface SessionAccount {
  walletMasked: string;
  acceptedVersion: string | null;
}

let cached: SessionAccount | undefined;
let inflight: Promise<SessionAccount | null> | null = null;

tokenStore.subscribe((token) => {
  if (token === null) {
    resetSessionAccountCache();
  }
});

/** Forgets the account of the session (sign-out; tests). */
export function resetSessionAccountCache(): void {
  cached = undefined;
  inflight = null;
}

/** The account of the session, read once; a failed read answers null and is tried again on the next screen. */
export function readSessionAccount(api: GuestApi): Promise<SessionAccount | null> {
  if (cached !== undefined) {
    return Promise.resolve(cached);
  }
  if (!inflight) {
    inflight = api.getAccount().then(
      (account) => {
        cached = {
          walletMasked: account.walletAddressMasked,
          acceptedVersion: account.acceptedTerms?.version ?? null,
        };
        return cached;
      },
      (failure: unknown) => {
        inflight = null;
        // The screens that need the account show their own error; the shell only falls back to its labels.
        console.warn(
          'Account of the session could not be read',
          failure instanceof Error ? failure.name : 'unknown',
        );
        return null;
      },
    );
  }
  return inflight;
}

/** The account of the session for the shell; null while anonymous, loading or after a failed read. */
export function useSessionAccount(): SessionAccount | null {
  const api = useApi();
  const { status } = useSession();
  const [account, setAccount] = useState<SessionAccount | null>(cached ?? null);

  useEffect(() => {
    if (status !== 'authenticated') {
      return undefined;
    }
    let active = true;
    void readSessionAccount(api).then((value) => {
      if (active) {
        setAccount(value);
      }
    });
    return () => {
      active = false;
    };
  }, [api, status]);

  return status === 'authenticated' ? account : null;
}
