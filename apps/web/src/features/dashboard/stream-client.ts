import { parseEnvelope, type DashboardEvent } from '@/features/dashboard/envelopes';
import { OversizedEventError, SseParser } from '@/features/dashboard/sse-reader';
import { dashboardWarn, isEventKind } from '@/features/dashboard/warn';
import { TERMS_REACCEPTANCE_REQUIRED } from '@/lib/api/guest-api';

/**
 * Client of the live channel C7 of the dashboard (BR4.2, BR5.1 to BR5.6; NFR1.3, NFR2.1, NFR2.2, NFR6.1, NFR6.3,
 * NFR6.6). Plain TypeScript, no React, so it is tested alone with a fake `fetch` and Jest's fake clock.
 *
 * - Opens `GET /api/v1/stream/dashboard` with `fetch` in continuous reading, `Authorization` from the in-memory token
 *   store, `Last-Event-ID` of the last event and a fresh `X-Correlation-Id` (never `EventSource`, never the token in
 *   the URL; BR5.1, NFR6.1).
 * - The channel is OPEN on the `200` answer with `text/event-stream`, before any event: with equal marks the first
 *   event can take 15 s (BR5.3).
 * - 60 s without any event (heartbeat included) since the opening or the last event: STALE (BR5.4, NFR1.3).
 * - A drop waits 2, 4, 8, 16 and then 30 s between attempts, without limit; a `429` waits at least its
 *   `Retry-After` (NFR2.1); "Tentar de novo" reopens at once (BR5.3).
 * - `auth.expired`, or `401` at an opening, renews the token ONCE per attempt; a failed renewal, or a new `401` right
 *   after a renewal, closes the channel for good and calls the session-expired flow of U3 (BR5.5, NFR6.6).
 * - `close()` cancels the reading and every scheduled attempt (BR5.6). Nothing reacts to `visibilitychange` (NFR2.2).
 * - Events outside the contract are discarded through `dashboardWarn`, without closing the channel (BR5.2).
 */
export type ChannelState = 'CONNECTING' | 'OPEN' | 'STALE' | 'WAITING' | 'RENEWING' | 'CLOSED';

export interface ChannelStatus {
  state: ChannelState;
  /** BR4.2: the values on screen may be outdated (STALE, WAITING, CONNECTING after a drop, RENEWING past 10 s). */
  outdated: boolean;
}

export interface StreamClientOptions {
  /** Absolute URL of the channel (API origin + `/api/v1/stream/dashboard`). */
  url: string;
  getToken: () => string | null;
  /** Renews the access token through C1; resolves `true` when a new token is in the store. */
  renew: () => Promise<boolean>;
  onEvent: (event: DashboardEvent) => void;
  onStatus: (status: ChannelStatus) => void;
  /** BR5.5: the renewal failed or the channel got `401` again right after it. */
  onSessionExpired: () => void;
  /**
   * u3-reacceptance (BR3.7, NFR2.4): the opening got `403 TERMS_REACCEPTANCE_REQUIRED`. The channel closes for good
   * and the same handler of the REST calls sends the person to the new acceptance.
   */
  onTermsReacceptance?: () => void;
  fetchImpl?: typeof fetch;
  newId?: () => string;
}

export interface StreamClient {
  start(): void;
  /** "Tentar de novo": reopens now, cancelling the scheduled wait. */
  retryNow(): void;
  close(): void;
}

/** BR5.3: waits between attempts after a drop. */
export const BACKOFF_MS: readonly number[] = [2_000, 4_000, 8_000, 16_000, 30_000];
/** BR5.4: silence that marks the channel STALE. */
export const STALE_AFTER_MS = 60_000;
/** BR4.2 / F6 step 4: a renewal that takes longer than this shows the outdated notice. */
export const SLOW_RENEWAL_MS = 10_000;

export const STREAM_PATH = '/api/v1/stream/dashboard';

export function createStreamClient(options: StreamClientOptions): StreamClient {
  const doFetch = options.fetchImpl ?? ((...args: Parameters<typeof fetch>) => fetch(...args));
  const newId = options.newId ?? (() => crypto.randomUUID());

  let state: ChannelState = 'CLOSED';
  let outdated = false;
  let closed = true;
  /** Bumped on every opening and on close: answers of an older attempt are ignored. */
  let generation = 0;
  let failures = 0;
  let renewedThisAttempt = false;
  /** A drop happened since the last successful opening (BR4.2: CONNECTING after a drop is outdated). */
  let dropped = false;
  let lastEventId: string | undefined;
  let controller: AbortController | null = null;
  let retryTimer: ReturnType<typeof setTimeout> | null = null;
  let staleTimer: ReturnType<typeof setTimeout> | null = null;
  let slowRenewalTimer: ReturnType<typeof setTimeout> | null = null;

  function setStatus(next: ChannelState, nextOutdated: boolean) {
    if (next === state && nextOutdated === outdated) {
      return;
    }
    state = next;
    outdated = nextOutdated;
    options.onStatus({ state, outdated });
  }

  function clearTimer(timer: ReturnType<typeof setTimeout> | null) {
    if (timer !== null) {
      clearTimeout(timer);
    }
  }

  function clearAllTimers() {
    clearTimer(retryTimer);
    clearTimer(staleTimer);
    clearTimer(slowRenewalTimer);
    retryTimer = null;
    staleTimer = null;
    slowRenewalTimer = null;
  }

  function abortReading() {
    generation += 1;
    controller?.abort();
    controller = null;
  }

  function armStaleTimer() {
    clearTimer(staleTimer);
    staleTimer = setTimeout(() => {
      staleTimer = null;
      if (state === 'OPEN') {
        setStatus('STALE', true);
      }
    }, STALE_AFTER_MS);
  }

  function headers(): Record<string, string> {
    const values: Record<string, string> = {
      Accept: 'text/event-stream',
      'X-Correlation-Id': newId(),
    };
    const token = options.getToken();
    if (token) {
      values.Authorization = `Bearer ${token}`;
    }
    if (lastEventId) {
      values['Last-Event-ID'] = lastEventId;
    }
    return values;
  }

  function connect() {
    if (closed) {
      return;
    }
    abortReading();
    clearTimer(retryTimer);
    retryTimer = null;
    const mine = generation;
    controller = new AbortController();
    setStatus('CONNECTING', dropped);
    const init: RequestInit = {
      method: 'GET',
      headers: headers(),
      credentials: 'omit',
      cache: 'no-store',
      signal: controller.signal,
    };
    // A fetch that throws synchronously is a failed opening like any other.
    new Promise<Response>((resolve) => resolve(doFetch(options.url, init))).then(
      (response) => {
        if (mine === generation) {
          void handleResponse(response, mine);
        }
      },
      () => {
        if (mine === generation) {
          dashboardWarn('stream', 'NETWORK');
          drop(0);
        }
      },
    );
  }

  async function handleResponse(response: Response, mine: number) {
    if (response.status === 401) {
      renewOrExpire();
      return;
    }
    if (response.status === 403 && (await isTermsLock(response))) {
      if (mine === generation) {
        close();
        options.onTermsReacceptance?.();
      }
      return;
    }
    if (response.status === 429) {
      drop(retryAfterMs(response));
      return;
    }
    const type = response.headers?.get?.('Content-Type') ?? '';
    if (response.status !== 200 || !type.startsWith('text/event-stream') || !response.body) {
      drop(0);
      return;
    }
    failures = 0;
    dropped = false;
    renewedThisAttempt = false;
    setStatus('OPEN', false);
    armStaleTimer();
    await read(response.body, mine);
  }

  async function read(body: ReadableStream<Uint8Array>, mine: number) {
    const reader = body.getReader();
    const parser = new SseParser();
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (mine !== generation) {
          return;
        }
        if (done) {
          parser.end();
          drop(0);
          return;
        }
        for (const event of parser.push(value)) {
          if (mine !== generation) {
            return;
          }
          receive(event.event, event.data, event.id);
        }
      }
    } catch (failure) {
      if (mine !== generation) {
        return;
      }
      if (failure instanceof OversizedEventError) {
        dashboardWarn('stream', 'OVERSIZED_EVENT');
        void reader.cancel().catch(() => dashboardWarn('stream', 'NETWORK'));
      } else {
        dashboardWarn('stream', 'NETWORK');
      }
      drop(0);
    }
  }

  function receive(name: string, data: string, id: string | undefined) {
    if (id !== undefined) {
      lastEventId = id;
    }
    armStaleTimer();
    if (state === 'STALE') {
      setStatus('OPEN', false);
    }
    if (!isEventKind(name)) {
      dashboardWarn('unknown', 'UNKNOWN_EVENT');
      return;
    }
    const event = parseEnvelope(name, data);
    if (!event) {
      dashboardWarn(name, 'INVALID_ENVELOPE');
      return;
    }
    if (event.kind === 'auth.expired') {
      // The server closes right after this event; the end of the body of this attempt is not a drop.
      abortReading();
      renewOrExpire();
      return;
    }
    options.onEvent(event);
  }

  /** A drop: wait the growing delay (at least {@code minimumMs}) and open again. */
  function drop(minimumMs: number) {
    if (closed) {
      return;
    }
    abortReading();
    clearTimer(staleTimer);
    staleTimer = null;
    dropped = true;
    const wait = Math.max(
      BACKOFF_MS[Math.min(failures, BACKOFF_MS.length - 1)] as number,
      minimumMs,
    );
    failures += 1;
    setStatus('WAITING', true);
    retryTimer = setTimeout(() => {
      retryTimer = null;
      connect();
    }, wait);
  }

  function renewOrExpire() {
    if (renewedThisAttempt) {
      expire();
      return;
    }
    renewedThisAttempt = true;
    abortReading();
    clearTimer(staleTimer);
    staleTimer = null;
    const mine = generation;
    setStatus('RENEWING', false);
    clearTimer(slowRenewalTimer);
    slowRenewalTimer = setTimeout(() => {
      slowRenewalTimer = null;
      if (state === 'RENEWING') {
        setStatus('RENEWING', true);
      }
    }, SLOW_RENEWAL_MS);
    options.renew().then(
      (renewed) => {
        if (mine !== generation || closed) {
          return;
        }
        clearTimer(slowRenewalTimer);
        slowRenewalTimer = null;
        if (renewed) {
          connect();
        } else {
          expire();
        }
      },
      () => {
        if (mine === generation && !closed) {
          expire();
        }
      },
    );
  }

  function expire() {
    close();
    options.onSessionExpired();
  }

  function close() {
    closed = true;
    abortReading();
    clearAllTimers();
    setStatus('CLOSED', false);
  }

  return {
    start() {
      if (!closed) {
        return;
      }
      closed = false;
      failures = 0;
      dropped = false;
      renewedThisAttempt = false;
      connect();
    },
    retryNow() {
      if (closed) {
        return;
      }
      failures = 0;
      connect();
    },
    close,
  };
}

/** u3-reacceptance: a `403` whose problem is the reacceptance lock (any other 403 is a drop like before). */
async function isTermsLock(response: Response): Promise<boolean> {
  try {
    const problem = (await response.json()) as { code?: unknown } | null;
    return problem?.code === TERMS_REACCEPTANCE_REQUIRED;
  } catch {
    // A 403 without a readable problem is not the lock: the caller treats it as a drop and warns once.
    dashboardWarn('stream', 'NETWORK');
    return false;
  }
}

/** Milliseconds of the `Retry-After` header of a 429 (seconds), or 0 when absent or not a whole number. */
function retryAfterMs(response: Response): number {
  const value = response.headers?.get?.('Retry-After');
  if (!value || !/^\d+$/.test(value)) {
    return 0;
  }
  return parseInt(value, 10) * 1_000;
}
