'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * 261113-trading-real-bot U6 (U6-NFR3.4, U6-NFR3.5b, U6-NFR4.6): follows a withdrawal or a liquidation by reading its
 * state every 3 s, for at most 2 minutes, with ONE read in flight at a time: the next read is scheduled only after the
 * previous one ended. At most 40 reads. After 2 minutes it stops with the state `TIMEOUT` ("Ainda processando; acompanhe
 * em Ordens"); a failed read is tried again at the next tick, never sooner.
 */
export const TRACKING_INTERVAL_MS = 3_000;
export const TRACKING_LIMIT_MS = 120_000;

export type Tracking<T> =
  | { kind: 'idle' }
  | { kind: 'tracking'; latest: T | null }
  | { kind: 'done'; latest: T }
  | { kind: 'timeout'; latest: T | null };

export function useTracking<T>(
  read: () => Promise<T>,
  isDone: (value: T) => boolean,
): { tracking: Tracking<T>; start(): void; stop(): void } {
  const [tracking, setTracking] = useState<Tracking<T>>({ kind: 'idle' });
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const generation = useRef(0);
  const readRef = useRef(read);
  readRef.current = read;
  const doneRef = useRef(isDone);
  doneRef.current = isDone;

  const stop = useCallback(() => {
    generation.current += 1;
    if (timer.current !== null) {
      clearTimeout(timer.current);
      timer.current = null;
    }
  }, []);

  const start = useCallback(() => {
    stop();
    const mine = generation.current;
    const startedAt = Date.now();
    let latest: T | null = null;
    setTracking({ kind: 'tracking', latest: null });
    const tick = async () => {
      timer.current = null;
      try {
        latest = await readRef.current();
      } catch {
        // A failed read keeps the last known state; the next tick reads again (no burst of retries).
      }
      if (mine !== generation.current) {
        return;
      }
      if (latest !== null && doneRef.current(latest)) {
        setTracking({ kind: 'done', latest });
        return;
      }
      if (Date.now() - startedAt + TRACKING_INTERVAL_MS > TRACKING_LIMIT_MS) {
        setTracking({ kind: 'timeout', latest });
        return;
      }
      setTracking({ kind: 'tracking', latest });
      timer.current = setTimeout(() => void tick(), TRACKING_INTERVAL_MS);
    };
    timer.current = setTimeout(() => void tick(), TRACKING_INTERVAL_MS);
  }, [stop]);

  useEffect(() => stop, [stop]);

  return { tracking, start, stop };
}
