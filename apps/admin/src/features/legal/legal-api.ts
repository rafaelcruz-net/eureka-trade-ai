import type { components } from '@/lib/api/schema';

/**
 * Server-side client of L1 in the administrator app (u1-legal-documents, performance-design.md, NFR7.4). Runs ONLY in
 * the Next.js server: the origin of the API is read from the server environment (never a NEXT_PUBLIC_* variable). A
 * successful answer is kept in memory for 300 s per URL; an error or a 404 is never kept.
 */
export type LegalDocuments = components['schemas']['LegalDocuments'];
export type LegalVersionRef = components['schemas']['LegalVersionRef'];
export type LegalText = components['schemas']['LegalText'];
export type RetentionItem = components['schemas']['RetentionItem'];

export type LegalResult<T> =
  | { status: 'ok'; data: T }
  | { status: 'not-found' }
  | { status: 'error' };

export const LEGAL_CACHE_TTL_MS = 300_000;
export const LEGAL_TIMEOUT_MS = 10_000;

const VERSION = /^[A-Za-z0-9._-]{1,32}$/;

export interface LegalFetchSettings {
  origin?: string;
  fetchImpl?: typeof fetch;
  now?: () => number;
  timeoutMs?: number;
}

const cache = new Map<string, { expiresAt: number; data: unknown }>();

/** Drops every kept answer (tests). */
export function clearLegalCache(): void {
  cache.clear();
}

/** The API origin as the app SERVER reaches it (API_INTERNAL_ORIGIN in the containers, else API_ORIGIN). */
export function legalApiOrigin(env: Record<string, string | undefined> = process.env): string {
  return (env.API_INTERNAL_ORIGIN || env.API_ORIGIN || 'http://localhost:8080').replace(/\/+$/, '');
}

export function isValidVersion(version: string): boolean {
  return VERSION.test(version);
}

export async function fetchLegal<T>(
  path: string,
  settings: LegalFetchSettings = {},
): Promise<LegalResult<T>> {
  const now = settings.now ?? Date.now;
  const url = `${settings.origin ?? legalApiOrigin()}/api/v1/legal${path}`;
  const kept = cache.get(url);
  if (kept && kept.expiresAt > now()) {
    return { status: 'ok', data: kept.data as T };
  }
  const doFetch = settings.fetchImpl ?? fetch;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), settings.timeoutMs ?? LEGAL_TIMEOUT_MS);
  try {
    const response = await doFetch(url, {
      headers: { Accept: 'application/json' },
      cache: 'no-store',
      signal: controller.signal,
    });
    if (response.status === 404) {
      return { status: 'not-found' };
    }
    if (!response.ok) {
      console.warn('Legal documents request failed', response.status, path);
      return { status: 'error' };
    }
    const data = (await response.json()) as T;
    cache.set(url, { expiresAt: now() + LEGAL_CACHE_TTL_MS, data });
    return { status: 'ok', data };
  } catch (failure) {
    console.warn(
      'Legal documents request failed',
      failure instanceof Error ? failure.name : 'unknown',
      path,
    );
    return { status: 'error' };
  } finally {
    clearTimeout(timer);
  }
}

export type LegalPageData =
  | { state: 'error' }
  | { state: 'not-found' }
  | {
      state: 'ready';
      document: LegalDocuments;
      currentVersion: string;
      history: LegalVersionRef[] | null;
    };

/** The current version, or the version of `?versao=` from the history (BR1.10, BR1.14). */
export async function loadLegalPage(
  requestedVersion: string | undefined,
  settings: LegalFetchSettings = {},
): Promise<LegalPageData> {
  if (requestedVersion !== undefined && !isValidVersion(requestedVersion)) {
    return { state: 'not-found' };
  }
  const current = await fetchLegal<LegalDocuments>('/current', settings);
  if (current.status !== 'ok') {
    return { state: 'error' };
  }
  const history = await fetchLegal<LegalVersionRef[]>('/versions', settings);
  const historyList = history.status === 'ok' ? history.data : null;
  if (requestedVersion === undefined || requestedVersion === current.data.version) {
    return {
      state: 'ready',
      document: current.data,
      currentVersion: current.data.version,
      history: historyList,
    };
  }
  const requested = await fetchLegal<LegalDocuments>(
    `/versions/${encodeURIComponent(requestedVersion)}`,
    settings,
  );
  if (requested.status === 'not-found') {
    return { state: 'not-found' };
  }
  if (requested.status === 'error') {
    return { state: 'error' };
  }
  return {
    state: 'ready',
    document: requested.data,
    currentVersion: current.data.version,
    history: historyList,
  };
}
