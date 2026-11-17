'use client';

import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { createApiClient } from '@/lib/api/client';
import { guestApi, type GuestApi } from '@/lib/api/guest-api';
import { ReacceptanceRedirect } from '@/features/reacceptance/ReacceptanceRedirect';

const ApiContext = createContext<GuestApi | null>(null);
const QuietApiContext = createContext<GuestApi | null>(null);
const ApiOriginContext = createContext<string | null>(null);

/**
 * Provides the typed API. The API origin is read on the SERVER from API_ORIGIN and passed down as a prop, so it is
 * runtime configuration and never a NEXT_PUBLIC_* build-time variable. U9: the origin is also available to the live
 * channel client of the dashboard, which opens its own `fetch` to the same origin.
 */
export function ApiProvider({
  apiOrigin,
  api,
  redirectOnTermsReacceptance = false,
  children,
}: {
  apiOrigin: string;
  api?: GuestApi;
  /**
   * u3-reacceptance (BR3.7, NFR2.4): registers the handler of `403 TERMS_REACCEPTANCE_REQUIRED`, which sends the
   * person to `/novo-aceite` with `router.replace`. On in the root layout of the app; off where no App Router exists.
   */
  redirectOnTermsReacceptance?: boolean;
  children: ReactNode;
}) {
  // 261113-trading-real-bot U6: a second instance on the same client, sharing the single-flight renewal of the first,
  // without the handler of the reacceptance lock. A test double given as `api` serves both.
  const { value, quiet } = useMemo(() => {
    if (api) {
      return { value: api, quiet: api };
    }
    const client = createApiClient({ baseUrl: apiOrigin });
    const main = guestApi(client);
    return { value: main, quiet: guestApi(client, { renewSession: () => main.refreshSession() }) };
  }, [api, apiOrigin]);
  return (
    <ApiOriginContext.Provider value={apiOrigin}>
      <ApiContext.Provider value={value}>
        <QuietApiContext.Provider value={quiet}>
          {redirectOnTermsReacceptance ? <ReacceptanceRedirect api={value} /> : null}
          {children}
        </QuietApiContext.Provider>
      </ApiContext.Provider>
    </ApiOriginContext.Provider>
  );
}

export function useApi(): GuestApi {
  const api = useContext(ApiContext);
  if (!api) {
    throw new Error('useApi must be used inside ApiProvider');
  }
  return api;
}

/**
 * 261113-trading-real-bot U6 (BR1.4, BR3.5, U6-NFR4.5): the API without the handler of the reacceptance lock, for the
 * signing banner and the screen of deposit and withdrawal; its 403 TERMS_REACCEPTANCE_REQUIRED reaches only the caller.
 */
export function useQuietApi(): GuestApi {
  const api = useContext(QuietApiContext);
  if (!api) {
    throw new Error('useQuietApi must be used inside ApiProvider');
  }
  return api;
}

/** The API origin (same site as the app; already allowed by the connect-src of the CSP). */
export function useApiOrigin(): string {
  const origin = useContext(ApiOriginContext);
  if (origin === null) {
    throw new Error('useApiOrigin must be used inside ApiProvider');
  }
  return origin;
}
