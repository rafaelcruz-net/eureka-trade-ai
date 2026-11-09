/**
 * @jest-environment node
 */
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
 * Passo 14 / 17 (performance-design.md, NFR7.4, NFR6.1): the server client of L1 keeps a success for 300 s per URL,
 * never keeps an error or a 404, reads the API origin only from the server environment, and the page loader maps
 * `?versao=` to the history.
 */
const ORIGIN = 'http://api:8080';

beforeEach(() => {
  clearLegalCache();
  jest.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => jest.restoreAllMocks());

describe('legalApiOrigin', () => {
  it('prefers the internal origin of the containers, then the browser origin, then localhost', () => {
    expect(
      legalApiOrigin({ API_INTERNAL_ORIGIN: 'http://api:8080/', API_ORIGIN: 'http://x' }),
    ).toBe('http://api:8080');
    expect(legalApiOrigin({ API_ORIGIN: 'http://localhost:8080' })).toBe('http://localhost:8080');
    expect(legalApiOrigin({})).toBe('http://localhost:8080');
    expect(legalApiOrigin({ NEXT_PUBLIC_API_ORIGIN: 'http://public' })).toBe(
      'http://localhost:8080',
    );
  });
});

describe('fetchLegal', () => {
  it('keeps a success for 300 s per URL and asks again after it', async () => {
    let now = 1_000;
    const fetchImpl = legalFetch({ '/current': { status: 200, body: CURRENT } });
    const settings = { origin: ORIGIN, fetchImpl, now: () => now };

    expect(await fetchLegal('/current', settings)).toEqual({ status: 'ok', data: CURRENT });
    now += LEGAL_CACHE_TTL_MS - 1;
    await fetchLegal('/current', settings);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    now += 1;
    await fetchLegal('/current', settings);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(fetchImpl.mock.calls[0][0]).toBe('http://api:8080/api/v1/legal/current');
  });

  it('never keeps an error, so the next try asks the API again', async () => {
    const fetchImpl = legalFetch({ '/current': { status: 503 } });

    expect(await fetchLegal('/current', { origin: ORIGIN, fetchImpl })).toEqual({
      status: 'error',
    });
    expect(await fetchLegal('/current', { origin: ORIGIN, fetchImpl })).toEqual({
      status: 'error',
    });
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(console.warn).toHaveBeenCalledWith('Legal documents request failed', 503, '/current');
  });

  it('answers not-found for a 404 and does not keep it', async () => {
    const fetchImpl = legalFetch({ '/versions/v9': { status: 404 } });

    expect(await fetchLegal('/versions/v9', { origin: ORIGIN, fetchImpl })).toEqual({
      status: 'not-found',
    });
    await fetchLegal('/versions/v9', { origin: ORIGIN, fetchImpl });
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it('turns a network failure into an error with its name only', async () => {
    const fetchImpl = jest.fn(async () => {
      throw new TypeError('fetch failed: connect ECONNREFUSED 10.0.0.1');
    }) as unknown as typeof fetch;

    expect(await fetchLegal('/current', { origin: ORIGIN, fetchImpl })).toEqual({
      status: 'error',
    });
    expect(console.warn).toHaveBeenCalledWith(
      'Legal documents request failed',
      'TypeError',
      '/current',
    );
  });

  it('gives up after the timeout', async () => {
    const fetchImpl = jest.fn(
      (_url: string | URL | Request, init?: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () =>
            reject(new DOMException('aborted', 'AbortError')),
          );
        }),
    ) as unknown as typeof fetch;

    expect(await fetchLegal('/current', { origin: ORIGIN, fetchImpl, timeoutMs: 5 })).toEqual({
      status: 'error',
    });
  });
});

describe('loadLegalPage', () => {
  const answers = {
    '/current': { status: 200, body: CURRENT },
    '/versions': { status: 200, body: HISTORY },
    '/versions/v1': { status: 200, body: LEGACY },
    '/versions/v9': { status: 404 },
  };

  it('loads the current version with the history', async () => {
    const data = await loadLegalPage(undefined, { origin: ORIGIN, fetchImpl: legalFetch(answers) });

    expect(data).toEqual({
      state: 'ready',
      document: CURRENT,
      currentVersion: 'v2',
      history: HISTORY,
    });
  });

  it('loads an older version of the history and keeps the current one to compare', async () => {
    const data = await loadLegalPage('v1', { origin: ORIGIN, fetchImpl: legalFetch(answers) });

    expect(data).toMatchObject({
      state: 'ready',
      currentVersion: 'v2',
      document: { version: 'v1' },
    });
  });

  it('asking for the current version by name is the current page', async () => {
    const fetchImpl = legalFetch(answers);

    const data = await loadLegalPage('v2', { origin: ORIGIN, fetchImpl });

    expect(data).toMatchObject({ state: 'ready', document: { version: 'v2' } });
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it('a version outside the history is not found', async () => {
    expect(await loadLegalPage('v9', { origin: ORIGIN, fetchImpl: legalFetch(answers) })).toEqual({
      state: 'not-found',
    });
  });

  it('an invalid version is not found without calling the API', async () => {
    const fetchImpl = legalFetch(answers);

    expect(await loadLegalPage('../admin', { origin: ORIGIN, fetchImpl })).toEqual({
      state: 'not-found',
    });
    expect(fetchImpl).not.toHaveBeenCalled();
    expect(isValidVersion('v2')).toBe(true);
    expect(isValidVersion('a'.repeat(33))).toBe(false);
  });

  it('a failure of the current version is an error', async () => {
    expect(
      await loadLegalPage(undefined, {
        origin: ORIGIN,
        fetchImpl: legalFetch({ '/current': { status: 500 } }),
      }),
    ).toEqual({ state: 'error' });
  });

  it('a failure of the requested version is an error, and of the history only hides it', async () => {
    const fetchImpl = legalFetch({
      '/current': { status: 200, body: CURRENT },
      '/versions/v1': { status: 502 },
    });

    expect(await loadLegalPage('v1', { origin: ORIGIN, fetchImpl })).toEqual({ state: 'error' });
    clearLegalCache();
    expect(await loadLegalPage(undefined, { origin: ORIGIN, fetchImpl })).toMatchObject({
      state: 'ready',
      history: null,
    });
  });
});
