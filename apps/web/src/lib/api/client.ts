import { tokenStore } from '@/lib/auth/token-store';
import type { components } from '@/lib/api/schema';

export type Problem = components['schemas']['Problem'];

export type FieldError = { field: string; message: string };
export type SourceStatusProblem = components['schemas']['SourceStatus'];

/**
 * An API failure: HTTP status plus the RFC 7807 code and reason when the server sent them, the refused fields of a
 * 400 and, for 503 SOURCE_UNAVAILABLE, the state of each market source.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly reason?: string,
    readonly correlationId?: string,
    readonly fields: FieldError[] = [],
    readonly sources: SourceStatusProblem[] = [],
    /** Seconds of the Retry-After header of a 429 (U3 invitation lookup), when the server sent one. */
    readonly retryAfterSeconds?: number,
    /**
     * u3-reacceptance (NFR1.1): the effective current version of the terms, sent with TERMS_REACCEPTANCE_REQUIRED and
     * TERMS_VERSION_OUTDATED.
     */
    readonly currentVersion?: string,
  ) {
    super(`${status} ${code}`);
    this.name = 'ApiError';
  }
}

export interface RequestOptions {
  /** U5 adds PUT for the steps of the bot wizard (C4 {@code PUT /bots/{botId}/draft}). */
  method?: 'GET' | 'POST' | 'PUT';
  body?: unknown;
  /** Adds a fresh Idempotency-Key (UUID); such requests may be retried. */
  idempotent?: boolean;
  /**
   * U8 (BR6.4, NFR10.5): a FIXED Idempotency-Key chosen by the screen (the feedback dialog generates it when it opens
   * and reuses it on every new attempt). Such requests may be retried like the idempotent ones.
   */
  idempotencyKey?: string;
  /** Sends the HttpOnly refresh cookie (only verify, refresh and logout need it). */
  credentials?: boolean;
  /** Adds the in-memory Bearer token. */
  authenticated?: boolean;
  /**
   * u3-reacceptance: revalidates with the server instead of reusing a cached answer (the legal documents are cached
   * for 300 s; after a 409 the screen needs the new version at once).
   */
  revalidate?: boolean;
}

export interface ClientSettings {
  baseUrl: string;
  timeoutMs?: number;
  maxRetries?: number;
  backoffMs?: number;
  fetchImpl?: typeof fetch;
  sleep?: (ms: number) => Promise<void>;
  newId?: () => string;
}

const realSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * Fetch wrapper of the guest app (contract-summary.md "Repetição pelo cliente"): 10 s timeout; at most 2 retries
 * with growing backoff, only for GET or requests with an Idempotency-Key, and only on 503 or timeout.
 */
export function createApiClient(settings: ClientSettings) {
  const timeoutMs = settings.timeoutMs ?? 10_000;
  const maxRetries = settings.maxRetries ?? 2;
  const backoffMs = settings.backoffMs ?? 300;
  const doFetch = settings.fetchImpl ?? ((...args: Parameters<typeof fetch>) => fetch(...args));
  const sleep = settings.sleep ?? realSleep;
  const newId = settings.newId ?? (() => crypto.randomUUID());

  async function attempt(path: string, init: RequestInit): Promise<Response> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      return await doFetch(`${settings.baseUrl}${path}`, { ...init, signal: controller.signal });
    } finally {
      clearTimeout(timer);
    }
  }

  async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const method = options.method ?? 'GET';
    const headers: Record<string, string> = {
      'X-Correlation-Id': newId(),
      Accept: 'application/json',
    };
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    if (options.idempotencyKey) {
      headers['Idempotency-Key'] = options.idempotencyKey;
    } else if (options.idempotent) {
      headers['Idempotency-Key'] = newId();
    }
    const token = tokenStore.get();
    if (options.authenticated && token) {
      headers.Authorization = `Bearer ${token}`;
    }
    const init: RequestInit = {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      credentials: options.credentials ? 'include' : 'omit',
      ...(options.revalidate ? { cache: 'no-cache' as RequestCache } : {}),
    };
    const retryable =
      method === 'GET' || Boolean(options.idempotent) || Boolean(options.idempotencyKey);

    for (let tries = 0; ; tries++) {
      let response: Response;
      try {
        response = await attempt(path, init);
      } catch (failure) {
        const timedOut = failure instanceof Error && failure.name === 'AbortError';
        if (timedOut && retryable && tries < maxRetries) {
          await sleep(backoffMs * 2 ** tries);
          continue;
        }
        throw new ApiError(0, timedOut ? 'TIMEOUT' : 'NETWORK');
      }
      if (response.status === 503 && retryable && tries < maxRetries) {
        await sleep(backoffMs * 2 ** tries);
        continue;
      }
      return parse<T>(response);
    }
  }

  return { request };
}

async function parse<T>(response: Response): Promise<T> {
  if (response.ok) {
    return (response.status === 204 ? undefined : await response.json()) as T;
  }
  let problem: Partial<Problem> & { sources?: SourceStatusProblem[] } = {};
  try {
    problem = (await response.json()) as Partial<Problem> & { sources?: SourceStatusProblem[] };
  } catch {
    problem = {};
  }
  throw new ApiError(
    response.status,
    problem.code ?? 'HTTP_ERROR',
    problem.reason,
    problem.correlationId,
    (problem.fields ?? []).map((field) => ({
      field: field.field ?? '',
      message: field.message ?? '',
    })),
    problem.sources ?? [],
    retryAfter(response),
    problem.currentVersion,
  );
}

function retryAfter(response: Response): number | undefined {
  const value = response.headers?.get?.('Retry-After');
  if (!value || !/^\d+$/.test(value)) {
    return undefined;
  }
  return Number(value);
}

export type ApiClient = ReturnType<typeof createApiClient>;
