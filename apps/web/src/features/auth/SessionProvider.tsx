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
import type { GuestSession } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';

export type SessionStatus = 'checking' | 'authenticated' | 'anonymous' | 'expired';

interface SessionContextValue {
  status: SessionStatus;
  signIn(session: GuestSession): void;
  signOut(): Promise<void>;
  /** Called when an authenticated call answers 401: the token is dropped and the person must sign in again. */
  expire(): void;
}

const SessionContext = createContext<SessionContextValue | null>(null);

/**
 * Keeps the access token only in memory. On load (or reload) it renews the session once through the HttpOnly
 * refresh cookie (F2); a failed renewal leaves the person anonymous.
 */
export function SessionProvider({ children }: { children: ReactNode }) {
  const api = useApi();
  const [status, setStatus] = useState<SessionStatus>(
    tokenStore.get() ? 'authenticated' : 'checking',
  );
  const renewed = useRef(false);

  useEffect(() => {
    if (renewed.current || tokenStore.get()) {
      return;
    }
    renewed.current = true;
    api.refreshSession().then(
      (session) => {
        tokenStore.set(session.accessToken);
        setStatus('authenticated');
      },
      () => setStatus('anonymous'),
    );
  }, [api]);

  const signIn = useCallback((session: GuestSession) => {
    tokenStore.set(session.accessToken);
    setStatus('authenticated');
  }, []);

  const expire = useCallback(() => {
    tokenStore.clear();
    setStatus('expired');
  }, []);

  const signOut = useCallback(async () => {
    try {
      await api.logout();
    } catch (failure) {
      // The local token is dropped anyway; the server session expires on its own deadlines.
      console.warn('Logout request failed', failure instanceof Error ? failure.name : 'unknown');
    } finally {
      tokenStore.clear();
      setStatus('anonymous');
    }
  }, [api]);

  const value = useMemo(
    () => ({ status, signIn, signOut, expire }),
    [status, signIn, signOut, expire],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const session = useContext(SessionContext);
  if (!session) {
    throw new Error('useSession must be used inside SessionProvider');
  }
  return session;
}
