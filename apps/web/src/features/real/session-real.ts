'use client';

import { useEffect, useState } from 'react';
import { useQuietApi } from '@/lib/api/ApiProvider';
import type { GuestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { useSession } from '@/features/auth/SessionProvider';
import { toBotWallet, toRealError, type Mode } from '@/features/real/api';

/**
 * 261113-trading-real-bot U6 (BR1.1, BR3.5, BR7.1; logical-components.md "Menu"): what the shell needs of the REAL,
 * read ONCE per session through the API WITHOUT the handler of the reacceptance lock (so the menu never sends anyone
 * to the new acceptance) and kept in memory only, never in the browser storage (U6-NFR1.2):
 *
 * - `available`: the REAL is on (`GET /real/release` answered, or answered the lock of the terms); `403 REAL_DISABLED`
 *   or a `404` of an older API hide the menu item;
 * - `mode`: the mode of the active bot wallet, or null without one; the banner exists only in SIGNED.
 *
 * A new session reads it again; the screen of the mode updates it after a change.
 */
export interface SessionReal {
  available: boolean;
  mode: Mode | null;
}

let cached: SessionReal | undefined;
let inflight: Promise<SessionReal> | null = null;
const listeners = new Set<(value: SessionReal) => void>();

tokenStore.subscribe((token) => {
  if (token === null) {
    resetSessionReal();
  }
});

export function resetSessionReal(): void {
  cached = undefined;
  inflight = null;
}

function publish(value: SessionReal) {
  cached = value;
  listeners.forEach((listener) => listener(value));
}

/** The screens of the REAL tell the shell about a new mode or a new wallet. */
export function updateSessionReal(change: Partial<SessionReal>): void {
  publish({ available: true, mode: null, ...cached, ...change });
}

async function readAvailable(api: GuestApi): Promise<boolean> {
  try {
    await api.getRealRelease();
    return true;
  } catch (failure) {
    const error = toRealError(failure);
    // With the terms to accept again the REAL may still be on: the item stays, so the withdrawal stays reachable.
    return error.code === 'TERMS_REACCEPTANCE_REQUIRED';
  }
}

async function readMode(api: GuestApi): Promise<Mode | null> {
  try {
    return toBotWallet(await api.getBotWallet()).mode;
  } catch {
    // No wallet (404), or a failed read: no banner and the PAPER tag alone; the screens of the REAL show their errors.
    return null;
  }
}

export function readSessionReal(api: GuestApi): Promise<SessionReal> {
  if (cached !== undefined) {
    return Promise.resolve(cached);
  }
  if (!inflight) {
    inflight = Promise.all([readAvailable(api), readMode(api)]).then(([available, mode]) => {
      const value = { available, mode };
      publish(value);
      return value;
    });
  }
  return inflight;
}

/** The REAL of the session for the shell; null while anonymous or loading. */
export function useSessionReal(): SessionReal | null {
  const api = useQuietApi();
  const { status } = useSession();
  const [value, setValue] = useState<SessionReal | null>(cached ?? null);

  useEffect(() => {
    if (status !== 'authenticated') {
      return undefined;
    }
    let active = true;
    const listener = (next: SessionReal) => {
      if (active) {
        setValue(next);
      }
    };
    listeners.add(listener);
    void readSessionReal(api).then(listener);
    return () => {
      active = false;
      listeners.delete(listener);
    };
  }, [api, status]);

  return status === 'authenticated' ? value : null;
}
