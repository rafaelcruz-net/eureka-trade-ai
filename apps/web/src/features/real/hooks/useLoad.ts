'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { RealError, toRealError } from '@/features/real/api';

/**
 * 261113-trading-real-bot U6 (BR8.1; U6-NFR4.3, U6-NFR4.7): one part of a REAL screen with its own state: loading,
 * ready, or error with "Tentar de novo". A failing part shows its error only in itself and never takes the others down.
 * The read starts when `enabled` and runs again on `reload`.
 */
export type Load<T> =
  | { kind: 'loading' }
  | { kind: 'ready'; data: T }
  | { kind: 'error'; error: RealError };

export function useLoad<T>(
  read: () => Promise<T>,
  enabled = true,
): { load: Load<T>; reload(): void; set(data: T): void } {
  const [load, setLoad] = useState<Load<T>>({ kind: 'loading' });
  const readRef = useRef(read);
  readRef.current = read;
  const generation = useRef(0);

  const reload = useCallback(() => {
    const mine = ++generation.current;
    setLoad({ kind: 'loading' });
    readRef.current().then(
      (data) => {
        if (mine === generation.current) {
          setLoad({ kind: 'ready', data });
        }
      },
      (failure: unknown) => {
        if (mine === generation.current) {
          setLoad({ kind: 'error', error: toRealError(failure) });
        }
      },
    );
  }, []);

  useEffect(() => {
    if (enabled) {
      reload();
    }
    return () => {
      generation.current += 1;
    };
  }, [enabled, reload]);

  const set = useCallback((data: T) => {
    generation.current += 1;
    setLoad({ kind: 'ready', data });
  }, []);

  return { load, reload, set };
}
