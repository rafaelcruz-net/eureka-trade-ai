'use client';

import { useCallback, useRef, useState } from 'react';
import { AdminRealError, toAdminRealError } from '@/features/real/errors';

/**
 * 261113-trading-real-bot U6 (BR9.2 with the rule of BR6.2; U6-NFR3.3, U6-NFR7.6): one action of the administrator.
 * The Idempotency-Key belongs to ONE exact body: reused only when the same body is sent again ("Tentar de novo"), new for
 * another body, discarded on success, on reset (closing the dialog) and after IDEMPOTENCY_CONFLICT. While sending, a
 * second submission sends nothing; nothing is ever resent on its own.
 */
export type AdminActionState<R> =
  | { kind: 'idle' }
  | { kind: 'sending' }
  | { kind: 'error'; error: AdminRealError }
  | { kind: 'done'; result: R };

export function useAdminAction<B, R>(
  send: (body: B, idempotencyKey: string) => Promise<R>,
  newKey: () => string,
) {
  const [state, setState] = useState<AdminActionState<R>>({ kind: 'idle' });
  const inFlight = useRef(false);
  const last = useRef<{ serialized: string; key: string } | null>(null);
  const sendRef = useRef(send);
  sendRef.current = send;
  const keyRef = useRef(newKey);
  keyRef.current = newKey;

  const submit = useCallback(async (body: B): Promise<R | null> => {
    if (inFlight.current) {
      return null;
    }
    inFlight.current = true;
    setState({ kind: 'sending' });
    const serialized = JSON.stringify(body);
    const key = last.current?.serialized === serialized ? last.current.key : keyRef.current();
    last.current = { serialized, key };
    try {
      const result = await sendRef.current(body, key);
      last.current = null;
      setState({ kind: 'done', result });
      return result;
    } catch (failure) {
      const error = toAdminRealError(failure);
      if (error.code === 'IDEMPOTENCY_CONFLICT') {
        last.current = null;
      }
      setState({ kind: 'error', error });
      return null;
    } finally {
      inFlight.current = false;
    }
  }, []);

  const reset = useCallback(() => {
    if (!inFlight.current) {
      last.current = null;
      setState({ kind: 'idle' });
    }
  }, []);

  return { state, sending: state.kind === 'sending', submit, reset };
}
