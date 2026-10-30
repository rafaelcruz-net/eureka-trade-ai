import { tokenStore } from '@/lib/auth/token-store';
import type { components } from '@/lib/api/schema';

export type Problem = components['schemas']['Problem'];

/** An API failure: HTTP status plus the RFC 7807 code and reason when the server sent them. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly reason?: string,
    readonly correlationId?: string,
  ) {
    super(`${status} ${code}`);
    this.name = 'ApiError';
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST';
  body?: unknown;
  /** Adds a fresh Idempotency-Key (UUID); such requests may be retried. */
  idempotent?: boolean;
  /** Sends the HttpOnly refresh cookie (only verify, refresh and logout need it). */
  credentials?: boolean;
  /** Adds the in-memory Bearer token. */
  authenticated?: boolean;
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
    if (options.idempotent) {
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
    };
    const retryable = method === 'GET' || Boolean(options.idempotent);

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
  let problem: Partial<Problem> = {};
  try {
    problem = (await response.json()) as Partial<Problem>;
  } catch {
    problem = {};
  }
  throw new ApiError(
    response.status,
    problem.code ?? 'HTTP_ERROR',
    problem.reason,
    problem.correlationId,
  );
}

export type ApiClient = ReturnType<typeof createApiClient>;
