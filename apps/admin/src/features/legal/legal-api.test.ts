import {
  clearLegalCache,
  fetchLegal,
  isValidVersion,
  LEGAL_CACHE_TTL_MS,
  legalApiOrigin,
  loadLegalPage,
} from '@/features/legal/legal-api';
import { CURRENT, HISTORY, LEGACY, legalFetch } from '@/features/legal/fixtures';

/**
 * Passo 14 / 17 (performance-design.md, NFR7.4, NFR6.1): the server client of L1 in the administrator app keeps a
 * success for 300 s per URL, never keeps an error or a 404, and reads the API origin only on the server.
 */
const ORIGIN = 'http://api:8080';

beforeEach(() => {
  clearLegalCache();
  jest.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => jest.restoreAllMocks());

describe('legal api of the administrator app', () => {
  it('prefers the internal origin of the containers and never reads a NEXT_PUBLIC variable', () => {
    expect(legalApiOrigin({ API_INTERNAL_ORIGIN: 'http://api:8080/' })).toBe('http://api:8080');
    expect(legalApiOrigin({ API_ORIGIN: 'http://localhost:8080' })).toBe('http://localhost:8080');
    expect(legalApiOrigin({ NEXT_PUBLIC_API_ORIGIN: 'http://public' })).toBe(
      'http://localhost:8080',
    );
  });

  it('keeps a success for 300 s and asks again after it', async () => {
    let now = 0;
    const fetchImpl = legalFetch({ '/current': { status: 200, body: CURRENT } });
    const settings = { origin: ORIGIN, fetchImpl, now: () => now };

    await fetchLegal('/current', settings);
    now += LEGAL_CACHE_TTL_MS - 1;
    await fetchLegal('/current', settings);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    now += 1;
    await fetchLegal('/current', settings);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it('never keeps an error nor a 404', async () => {
    const fetchImpl = legalFetch({ '/current': { status: 503 }, '/versions/v9': { status: 404 } });

    expect(await fetchLegal('/current', { origin: ORIGIN, fetchImpl })).toEqual({
      status: 'error',
    });
    expect(await fetchLegal('/current', { origin: ORIGIN, fetchImpl })).toEqual({
      status: 'error',
    });
    expect(await fetchLegal('/versions/v9', { origin: ORIGIN, fetchImpl })).toEqual({
      status: 'not-found',
    });
    expect(fetchImpl).toHaveBeenCalledTimes(3);
  });

  it('a network failure or a timeout is an error', async () => {
    const failing = jest.fn(async () => {
      throw new TypeError('fetch failed');
    }) as unknown as typeof fetch;
    const hanging = jest.fn(
      (_url: string | URL | Request, init?: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => reject(new Error('aborted')));
        }),
    ) as unknown as typeof fetch;

    expect(await fetchLegal('/current', { origin: ORIGIN, fetchImpl: failing })).toEqual({
      status: 'error',
    });
    expect(
      await fetchLegal('/current', { origin: ORIGIN, fetchImpl: hanging, timeoutMs: 5 }),
    ).toEqual({ status: 'error' });
    expect(console.warn).toHaveBeenCalledWith(
      'Legal documents request failed',
      'TypeError',
      '/current',
    );
  });

  it('loads the current page, an older version, a missing one and an invalid one', async () => {
    const fetchImpl = legalFetch({
      '/current': { status: 200, body: CURRENT },
      '/versions': { status: 200, body: HISTORY },
      '/versions/v1': { status: 200, body: LEGACY },
      '/versions/v9': { status: 404 },
    });
    const settings = { origin: ORIGIN, fetchImpl };

    expect(await loadLegalPage(undefined, settings)).toMatchObject({
      state: 'ready',
      history: HISTORY,
    });
    expect(await loadLegalPage('v1', settings)).toMatchObject({
      state: 'ready',
      currentVersion: 'v2',
      document: { version: 'v1' },
    });
    expect(await loadLegalPage('v2', settings)).toMatchObject({ document: { version: 'v2' } });
    expect(await loadLegalPage('v9', settings)).toEqual({ state: 'not-found' });
    expect(await loadLegalPage('../x', settings)).toEqual({ state: 'not-found' });
    expect(isValidVersion('v1.0_b-2')).toBe(true);
  });

  it('a failure of the current or of the requested version is an error; of the history it only hides it', async () => {
    expect(await loadLegalPage(undefined, { origin: ORIGIN, fetchImpl: legalFetch({}) })).toEqual({
      state: 'error',
    });
    const partial = legalFetch({
      '/current': { status: 200, body: CURRENT },
      '/versions/v1': { status: 502 },
    });
    expect(await loadLegalPage('v1', { origin: ORIGIN, fetchImpl: partial })).toEqual({
      state: 'error',
    });
    clearLegalCache();
    expect(await loadLegalPage(undefined, { origin: ORIGIN, fetchImpl: partial })).toMatchObject({
      state: 'ready',
      history: null,
    });
  });
});
