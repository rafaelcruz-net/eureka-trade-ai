'use client';

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type { AdminSession } from '@/lib/api/admin-api';
import { tokenStore } from '@/lib/auth/token-store';

export type SessionStatus = 'loading' | 'anonymous' | 'signed' | 'restricted';

interface SessionValue {
  status: SessionStatus;
  wallet: string | null;
  signedIn(session: AdminSession): void;
  restrict(): void;
  /** The session could not be renewed (401 after the renewal): back to the entry, nothing to revoke. */
  expire(): void;
  signOut(): Promise<void>;
}

const SessionContext = createContext<SessionValue | null>(null);

/** Renew one minute before the access token ends (BR1.3); never before 5 s. */
const RENEW_BEFORE_MS = 60_000;

/**
 * The administrator session (F1, BR1.3, BR1.6): the access token only in memory; on load the app tries the HttpOnly
 * cookie of the administrator entry once; while signed it renews the token before it ends. A renewal that fails (cookie
 * reused, session over 8 hours, wallet removed from the list) returns to the entry. "Sair" revokes the session on the
 * server and clears the token.
 */
export function AdminSessionProvider({ children }: { children: ReactNode }) {
  const api = useApi();
  const [status, setStatus] = useState<SessionStatus>('loading');
  const [wallet, setWallet] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const schedule = useCallback(
    (session: AdminSession, renew: () => void) => {
      if (timer.current) {
        clearTimeout(timer.current);
      }
      const wait = Date.parse(session.accessTokenExpiresAt) - Date.now() - RENEW_BEFORE_MS;
      timer.current = setTimeout(renew, Math.max(5_000, wait));
    },
    [timer],
  );

  const renew = useCallback(async () => {
    try {
      const session = await api.refreshSession();
      tokenStore.set(session.accessToken);
      setStatus('signed');
      schedule(session, () => void renew());
    } catch {
      tokenStore.clear();
      setStatus('anonymous');
    }
  }, [api, schedule]);

  useEffect(() => {
    void renew();
    return () => {
      if (timer.current) {
        clearTimeout(timer.current);
      }
    };
  }, [renew]);

  const value = useMemo<SessionValue>(
    () => ({
      status,
      wallet,
      signedIn(session) {
        tokenStore.set(session.accessToken);
        setWallet(session.walletMasked ?? null);
        setStatus('signed');
        schedule(session, () => void renew());
      },
      restrict() {
        tokenStore.clear();
        setStatus('restricted');
      },
      expire() {
        tokenStore.clear();
        setStatus('anonymous');
      },
      async signOut() {
        try {
          await api.logout();
        } catch (failure) {
          // The session is closed locally anyway; the server one ends by itself (8 hours at most).
          console.warn(
            'Administrator logout failed',
            failure instanceof Error ? failure.name : 'unknown',
          );
        }
        if (timer.current) {
          clearTimeout(timer.current);
        }
        tokenStore.clear();
        setWallet(null);
        setStatus('anonymous');
      },
    }),
    [api, renew, schedule, status, wallet],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const session = useContext(SessionContext);
  if (!session) {
    throw new Error('useSession must be used inside AdminSessionProvider');
  }
  return session;
}
