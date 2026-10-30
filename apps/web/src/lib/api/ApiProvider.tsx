'use client';

import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { createApiClient } from '@/lib/api/client';
import { guestApi, type GuestApi } from '@/lib/api/guest-api';

const ApiContext = createContext<GuestApi | null>(null);

/**
 * Provides the typed API. The API origin is read on the SERVER from API_ORIGIN and passed down as a prop, so it is
 * runtime configuration and never a NEXT_PUBLIC_* build-time variable.
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
  return <ApiContext.Provider value={value}>{children}</ApiContext.Provider>;
}

export function useApi(): GuestApi {
  const api = useContext(ApiContext);
  if (!api) {
    throw new Error('useApi must be used inside ApiProvider');
  }
  return api;
}
