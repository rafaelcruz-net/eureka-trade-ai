'use client';

import { useCallback, useRef, useState } from 'react';
import { RealError, toRealError } from '@/features/real/api';

/**
 * 261113-trading-real-bot U6 (BR6.2, BR6.3; U6-NFR3.3, U6-NFR4.2, U6-NFR7.4, U6-NFR7.6): one action with money.
 *
 * - The Idempotency-Key belongs to ONE exact body: it is created when the screen builds the body and reused only when
 *   the same body is sent again ("Tentar de novo" after a network failure, a timeout or a 5xx). Another body (a new
 *   challenge, another signature, another value) gets a new key. After `409 IDEMPOTENCY_CONFLICT` the next action gets a
 *   new key. `reset` (closing the dialog) and a success discard the key.
 * - "Enviando" is set synchronously on the click, before the request leaves; a second click while sending sends
 *   nothing.
 * - Nothing is ever sent again on its own: only {@link MoneyAction.retry} resends, with the same body and key.
 */
export type MoneyActionState<R> =
  | { kind: 'idle' }
  | { kind: 'sending' }
  | { kind: 'error'; error: RealError }
  | { kind: 'done'; result: R };

export interface MoneyAction<B, R> {
  state: MoneyActionState<R>;
  sending: boolean;
  submit(body: B): Promise<R | null>;
  retry(): Promise<R | null>;
  reset(): void;
}

export function useMoneyAction<B, R>(
  send: (body: B, idempotencyKey: string) => Promise<R>,
  options: { newKey?: () => string; maxCap?: bigint } = {},
): MoneyAction<B, R> {
  const newKey = options.newKey ?? (() => crypto.randomUUID());
  const [state, setState] = useState<MoneyActionState<R>>({ kind: 'idle' });
  const inFlight = useRef(false);
  const last = useRef<{ body: B; serialized: string; key: string } | null>(null);
  const sendRef = useRef(send);
  sendRef.current = send;
  const keyRef = useRef(newKey);
  keyRef.current = newKey;
  const maxCap = options.maxCap;

  const run = useCallback(
    async (body: B): Promise<R | null> => {
      if (inFlight.current) {
        return null;
      }
      inFlight.current = true;
      setState({ kind: 'sending' });
      const serialized = JSON.stringify(body);
      const key =
        last.current && last.current.serialized === serialized
          ? last.current.key
          : keyRef.current();
      last.current = { body, serialized, key };
      try {
        const result = await sendRef.current(body, key);
        last.current = null;
        setState({ kind: 'done', result });
        return result;
      } catch (failure) {
        const error = toRealError(failure, { maxCap });
        if (error.code === 'IDEMPOTENCY_CONFLICT') {
          last.current = null;
        }
        setState({ kind: 'error', error });
        return null;
      } finally {
        inFlight.current = false;
      }
    },
    [maxCap],
  );

  const retry = useCallback(async () => {
    const previous = last.current;
    return previous ? run(previous.body) : null;
  }, [run]);

  const reset = useCallback(() => {
    if (!inFlight.current) {
      last.current = null;
      setState({ kind: 'idle' });
    }
  }, []);

  return { state, sending: state.kind === 'sending', submit: run, retry, reset };
}
