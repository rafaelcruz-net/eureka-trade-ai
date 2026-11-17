'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { usePathname } from 'next/navigation';
import { useQuietApi } from '@/lib/api/ApiProvider';
import { useSession } from '@/features/auth/SessionProvider';
import { useLiveChannel } from '@/features/live/LiveChannelProvider';
import { realReads, toRealError, type Mode, type SigningSession } from '@/features/real/api';

/**
 * 261113-trading-real-bot U6 (BR3.5; U6-NFR3.1, U6-NFR3.5, U6-NFR4.1, U6-NFR4.5): the proposal waiting for the signature
 * of the person, for the banner. Only in the SIGNED mode, only with a session and never with the reacceptance pending.
 *
 * - The event `real.signing.requested` sets the proposal in its own handler, without waiting for any read.
 * - On load, on every route change and right after every reconnection it reads `GET /orders?state=QUOTED&limit=1`
 *   and, with an order, its session: two calls at most. `404` or `409` on the session means it no longer waits.
 * - It reads through the API WITHOUT the handler of the reacceptance lock: its own `403 TERMS_REACCEPTANCE_REQUIRED`
 *   only marks the reacceptance pending (no redirect), and from then on nothing is read.
 */
export interface Proposal {
  realOrderId: string;
  deadline: string;
  /** The session read from the server; null while only the event is known. */
  session: SigningSession | null;
  /** The browser clock when the session arrived, for the difference of clocks. */
  receivedAtMs: number;
}

export function useSigningSession(mode: Mode | null) {
  const api = useQuietApi();
  const reads = useMemo(() => realReads(api), [api]);
  const { status } = useSession();
  const { subscribe, onReconnected, acquire, termsPending, markTermsPending } = useLiveChannel();
  const pathname = usePathname();
  const [proposal, setProposal] = useState<Proposal | null>(null);
  const active = status === 'authenticated' && mode === 'SIGNED' && !termsPending;
  const generation = useRef(0);

  const reread = useCallback(async () => {
    const mine = ++generation.current;
    try {
      const page = await reads.orders({ state: 'QUOTED', limit: 1 });
      const order = page.items[0];
      if (!order) {
        if (mine === generation.current) {
          setProposal(null);
        }
        return;
      }
      const session = await reads.signingSession(order.id);
      if (mine === generation.current) {
        setProposal({
          realOrderId: order.id,
          deadline: session.deadline,
          session,
          receivedAtMs: Date.now(),
        });
      }
    } catch (failure) {
      const error = toRealError(failure);
      if (mine !== generation.current) {
        return;
      }
      if (error.code === 'TERMS_REACCEPTANCE_REQUIRED') {
        markTermsPending();
      }
      if (error.status === 404 || error.status === 409 || error.status === 403) {
        setProposal(null);
      }
      // Any other failure keeps what is shown; the next route change or reconnection reads again.
    }
  }, [reads, markTermsPending]);

  useEffect(() => {
    if (!active) {
      generation.current += 1;
      setProposal(null);
      return;
    }
    void reread();
  }, [active, pathname, reread]);

  useEffect(() => {
    if (!active) {
      return undefined;
    }
    const offEvent = subscribe('real.signing.requested', (event) => {
      generation.current += 1;
      setProposal({
        realOrderId: event.data.realOrderId,
        deadline: event.data.deadline,
        session: null,
        receivedAtMs: Date.now(),
      });
    });
    const offState = subscribe('real.order.state', (event) => {
      setProposal((current) =>
        current && current.realOrderId === event.data.realOrderId && event.data.state !== 'QUOTED'
          ? null
          : current,
      );
    });
    const offReconnected = onReconnected(() => void reread());
    // Only the SIGNED mode keeps the channel open outside the dashboard.
    const release = acquire();
    return () => {
      offEvent();
      offState();
      offReconnected();
      release();
    };
  }, [active, subscribe, onReconnected, acquire, reread]);

  const clear = useCallback(() => {
    generation.current += 1;
    setProposal(null);
  }, []);

  return { proposal, clear, api, reads };
}
