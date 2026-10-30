'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useSession } from '@/features/auth/SessionProvider';

/**
 * Protected screens send an anonymous person to the entry, keeping the screen of origin; an expired session also
 * shows the "sessão expirada" notice (F2 step 4).
 */
export function useRequireSession(origin: string) {
  const { status } = useSession();
  const router = useRouter();

  useEffect(() => {
    if (status === 'anonymous' || status === 'expired') {
      const query = new URLSearchParams({ origem: origin });
      if (status === 'expired') {
        query.set('expirada', '1');
      }
      router.replace(`/entrar?${query.toString()}`);
    }
  }, [status, origin, router]);

  return status;
}
