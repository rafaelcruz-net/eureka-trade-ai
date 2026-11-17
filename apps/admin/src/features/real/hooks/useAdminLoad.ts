'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { AdminRealError, toAdminRealError } from '@/features/real/errors';

/** 261113-trading-real-bot U6 (BR8.1): loading, ready, or error with "Tentar de novo", in text. */
export type AdminLoad<T> =
  | { kind: 'loading' }
  | { kind: 'ready'; data: T }
  | { kind: 'error'; error: AdminRealError };

export function useAdminLoad<T>(read: () => Promise<T>) {
  const [load, setLoad] = useState<AdminLoad<T>>({ kind: 'loading' });
  const readRef = useRef(read);
  readRef.current = read;
  const generation = useRef(0);

  const reload = useCallback(() => {
    const mine = ++generation.current;
    setLoad({ kind: 'loading' });
    readRef.current().then(
      (data) => mine === generation.current && setLoad({ kind: 'ready', data }),
      (failure: unknown) =>
        mine === generation.current && setLoad({ kind: 'error', error: toAdminRealError(failure) }),
    );
  }, []);

  useEffect(() => {
    reload();
    return () => {
      generation.current += 1;
    };
  }, [reload]);

  return { load, reload };
}
