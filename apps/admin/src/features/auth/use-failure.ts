'use client';

import { useCallback } from 'react';
import { ApiError } from '@/lib/api/client';
import { useSession } from '@/features/auth/AdminSessionProvider';

/**
 * What every screen does with the failure of an administrative call: 403 shows "Acesso restrito" (BR7.4), a 401 that
 * survived the renewal returns to the entry; anything else stays on the screen, which says it in its own words.
 * Returns true when the failure was handled here.
 */
export function useFailure(): (failure: unknown) => boolean {
  const session = useSession();
  return useCallback(
    (failure: unknown) => {
      if (failure instanceof ApiError && failure.status === 403) {
        session.restrict();
        return true;
      }
      if (failure instanceof ApiError && failure.status === 401) {
        session.expire();
        return true;
      }
      return false;
    },
    [session],
  );
}
