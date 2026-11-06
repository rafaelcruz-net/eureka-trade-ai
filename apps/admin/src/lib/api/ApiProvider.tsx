'use client';

import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { createApiClient } from '@/lib/api/client';
import { adminApi, type AdminApi } from '@/lib/api/admin-api';

const ApiContext = createContext<AdminApi | null>(null);

/**
 * Provides the typed administrative API. The API origin is read on the SERVER from API_ORIGIN and passed down as a
 * prop (infrastructure-specification.md R-04), never a NEXT_PUBLIC_* build-time variable.
 */
export function ApiProvider({
  apiOrigin,
  api,
  children,
}: {
  apiOrigin: string;
  api?: AdminApi;
  children: ReactNode;
}) {
  const value = useMemo(
    () => api ?? adminApi(createApiClient({ baseUrl: apiOrigin })),
    [api, apiOrigin],
  );
  return <ApiContext.Provider value={value}>{children}</ApiContext.Provider>;
}

export function useApi(): AdminApi {
  const api = useContext(ApiContext);
  if (!api) {
    throw new Error('useApi must be used inside ApiProvider');
  }
  return api;
}
