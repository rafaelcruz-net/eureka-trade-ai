'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import type { GuestApi } from '@/lib/api/guest-api';
import { REACCEPTANCE_PATH, reacceptanceHref } from '@/features/reacceptance/return-path';

/** The screen where the lock was met: path and query of the address bar (never the origin or the hash). */
export function currentScreen(
  location: Pick<Location, 'pathname' | 'search'> = window.location,
): string {
  return `${location.pathname}${location.search}`;
}

/**
 * u3-reacceptance (BR3.7, NFR2.4; `ReacceptanceRedirect` of frontend-components.md): registers on the API layer THE
 * handler of `403 TERMS_REACCEPTANCE_REQUIRED`. It sends the person to `/novo-aceite?voltar=<screen>` with
 * `router.replace` (the locked screen does not stay in the history); on the screen of the new acceptance itself it
 * does nothing. Rendered once by the {@link ApiProvider} of the app.
 */
export function ReacceptanceRedirect({
  api,
  screen = currentScreen,
}: {
  api: Pick<GuestApi, 'setTermsReacceptanceHandler'>;
  screen?: () => string;
}) {
  const router = useRouter();
  useEffect(
    () =>
      api.setTermsReacceptanceHandler(() => {
        const current = screen();
        if (current === REACCEPTANCE_PATH || current.startsWith(`${REACCEPTANCE_PATH}?`)) {
          return;
        }
        router.replace(reacceptanceHref(current));
      }),
    [api, router, screen],
  );
  return null;
}
