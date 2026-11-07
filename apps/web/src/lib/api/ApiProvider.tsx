'use client';

import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { createApiClient } from '@/lib/api/client';
import { guestApi, type GuestApi } from '@/lib/api/guest-api';

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
  children,
}: {
  apiOrigin: string;
  api?: GuestApi;
  children: ReactNode;
}) {
  const value = useMemo(
    () => api ?? guestApi(createApiClient({ baseUrl: apiOrigin })),
    [api, apiOrigin],
  );
  return (
    <ApiOriginContext.Provider value={apiOrigin}>
      <ApiContext.Provider value={value}>{children}</ApiContext.Provider>
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
