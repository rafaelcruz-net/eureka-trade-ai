'use client';

import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { createApiClient } from '@/lib/api/client';
import { guestApi, type GuestApi } from '@/lib/api/guest-api';
import { ReacceptanceRedirect } from '@/features/reacceptance/ReacceptanceRedirect';

const ApiContext = createContext<GuestApi | null>(null);
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
  const value = useMemo(
    () => api ?? guestApi(createApiClient({ baseUrl: apiOrigin })),
    [api, apiOrigin],
  );
  return (
    <ApiOriginContext.Provider value={apiOrigin}>
      <ApiContext.Provider value={value}>
        {redirectOnTermsReacceptance ? <ReacceptanceRedirect api={value} /> : null}
        {children}
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

/** The API origin (same site as the app; already allowed by the connect-src of the CSP). */
export function useApiOrigin(): string {
  const origin = useContext(ApiOriginContext);
  if (origin === null) {
    throw new Error('useApiOrigin must be used inside ApiProvider');
  }
  return origin;
}
