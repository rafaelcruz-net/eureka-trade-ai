// Test of the load scenario "data-requests" of u4-data-requests (Passo 11). Run with:
//   node --test scripts/load/load-test-data-requests.test.mjs
// No network and no database: the fetch, the clock and the sleep are doubles; synthetic values only.
import assert from 'node:assert/strict';
import { createPublicKey, generateKeyPairSync, sign, verify } from 'node:crypto';
import { test } from 'node:test';
import {
  ADMIN_ROUTE,
  assertLocal,
  base58,
  DATA_REQUEST_LIMITS_MS,
  dataRequestsMarkdown,
  evaluateDataRequests,
  GUEST_ROUTE,
  newWallet,
  percentile95,
  runDataRequests,
  TYPES,
  walletFromSmokeFile,
} from './data-requests.mjs';

/** A fake api: accounts, an administrator, requests by type per token; every call takes `latencyMs` of the clock. */
function fakeApi({ latencyMs = 40, adminLatencyMs = 80, failRoute = null } = {}) {
  let clock = Date.parse('2026-11-10T12:00:00Z');
  let tokens = 0;
  const calls = [];
  let elapsedMs = 0;
  const respond = (status, body) => ({ status, elapsedMs, json: async () => body });
  const io = {
    apiBase: 'http://127.0.0.1:8080',
    now: () => clock,
    sleep: async (ms) => {
      clock += ms;
    },
    warn: () => undefined,
    fetch: async (url, init = {}) => {
      const path = url.replace('http://127.0.0.1:8080', '');
      calls.push({ path, init });
      elapsedMs = path.startsWith(ADMIN_ROUTE) ? adminLatencyMs : latencyMs;
      if (failRoute && path.startsWith(failRoute)) return respond(500, {});
      if (path === '/api/v1/legal/current') return respond(200, { version: 'v2' });
      if (path.endsWith('/auth/challenge')) return respond(200, { message: `entrar ${tokens}` });
      if (path.endsWith('/auth/verify')) return respond(200, { accessToken: `token-${tokens++}` });
      if (path === GUEST_ROUTE && init.method === 'POST') return respond(201, { requestId: 'r' });
      if (path === GUEST_ROUTE) return respond(200, []);
      if (path.startsWith(`${ADMIN_ROUTE}?`)) return respond(200, { items: [{ requestId: 'r-1' }] });
      if (path.startsWith(`${ADMIN_ROUTE}/`)) return respond(200, { requestId: 'r-1' });
      return respond(404, {});
    },
  };
  return { io, calls };
}

const ADMIN = newWallet();

test('base58 of a key and a wallet of this process that signs verifiably', () => {
  const wallet = newWallet();
  assert.match(wallet.address, /^[1-9A-HJ-NP-Za-km-z]{32,44}$/);
  assert.equal(base58(Buffer.from([0, 0, 1])), '112');
});

test('the administrator wallet file of SmokeWalletTool becomes a signing key', () => {
  const { privateKey } = generateKeyPairSync('ed25519');
  const seed = privateKey.export({ format: 'der', type: 'pkcs8' }).subarray(16).toString('base64');
  const wallet = walletFromSmokeFile(JSON.stringify({ address: 'Adm1', seed }));
  const message = Buffer.from('mensagem');
  const signature = sign(null, message, wallet.key);
  assert.ok(verify(null, message, createPublicKey(privateKey), signature));
  assert.throws(() => walletFromSmokeFile('{}'), /no address or seed/);
});

test('only loopback targets are accepted', () => {
  assert.doesNotThrow(() => assertLocal('http://127.0.0.1:8080'));
  assert.doesNotThrow(() => assertLocal('http://localhost:8080'));
  assert.throws(() => assertLocal('https://api.example.org'), /only targets localhost/);
});

test('the p95 is the nearest rank and null without samples', () => {
  assert.equal(percentile95([]), null);
  assert.equal(percentile95(Array.from({ length: 100 }, (_, i) => i + 1)), 95);
  assert.equal(percentile95([7]), 7);
});

test('the evaluation fails on each limit, on missing samples and on unexpected statuses', () => {
  assert.deepEqual(evaluateDataRequests({ guestP95Ms: 100, adminP95Ms: 200, unexpected: 0 }), {
    failures: [],
    passed: true,
  });
  assert.deepEqual(
    evaluateDataRequests({ guestP95Ms: DATA_REQUEST_LIMITS_MS.guest + 1, adminP95Ms: null, unexpected: 2 }).failures,
    ['p95 guest', 'no admin sample', '2 unexpected statuses'],
  );
  assert.deepEqual(
    evaluateDataRequests({ guestP95Ms: null, adminP95Ms: DATA_REQUEST_LIMITS_MS.admin + 1, unexpected: 0 }).failures,
    ['no guest sample', 'p95 admin'],
  );
});

test('the run signs every guest up with its invitation, creates one request per type and lists afterwards', async () => {
  const { io, calls } = fakeApi();
  const report = await runDataRequests({
    users: 2,
    // The three loops share one fake clock, each sleep of 2 s moves it for all of them.
    durationS: 120,
    codes: ['code-a', 'code-b'],
    adminWallet: ADMIN,
    io,
  });

  const verifications = calls.filter((c) => c.path === '/api/v1/auth/verify');
  assert.equal(verifications.length, 2);
  assert.deepEqual(
    verifications.map((c) => JSON.parse(c.init.body).inviteCode),
    ['code-a', 'code-b'],
  );
  assert.equal(JSON.parse(verifications[0].init.body).acceptedTermsVersion, 'v2');
  const creations = calls.filter((c) => c.path === GUEST_ROUTE && c.init.method === 'POST');
  const firstGuest = creations.filter((c) => c.init.headers.Authorization === 'Bearer token-0');
  assert.deepEqual(
    firstGuest.map((c) => JSON.parse(c.init.body).type),
    TYPES,
  );
  assert.ok(creations.every((c) => c.init.headers['Idempotency-Key']));
  assert.ok(calls.some((c) => c.path === GUEST_ROUTE && c.init.method === 'GET'));
  assert.ok(calls.some((c) => c.path === `${ADMIN_ROUTE}/r-1`));
  assert.equal(report.passed, true);
  assert.equal(report.guestP95Ms, 40);
  assert.equal(report.adminP95Ms, 80);
});

test('slow administrative routes or a 5xx fail the run', async () => {
  const slow = fakeApi({ adminLatencyMs: 1500 });
  const failing = fakeApi({ failRoute: GUEST_ROUTE });
  const slowReport = await runDataRequests({ users: 1, durationS: 10, codes: ['c'], adminWallet: ADMIN, io: slow.io });
  const failingReport = await runDataRequests({
    users: 1,
    durationS: 10,
    codes: ['c'],
    adminWallet: ADMIN,
    io: failing.io,
  });

  assert.deepEqual(slowReport.failures, ['p95 admin']);
  assert.equal(failingReport.passed, false);
  assert.match(failingReport.failures.join(), /unexpected statuses/);
});

test('missing invitations or a remote target stop the run before any call', async () => {
  const { io, calls } = fakeApi();
  await assert.rejects(
    runDataRequests({ users: 3, codes: ['only-one'], adminWallet: ADMIN, io }),
    /SMOKE_INVITE_CODES has 1 codes, 3 needed/,
  );
  await assert.rejects(
    runDataRequests({ users: 1, codes: ['c'], adminWallet: ADMIN, io: { ...io, apiBase: 'https://x.example.org' } }),
    /only targets localhost/,
  );
  assert.equal(calls.length, 0);
});

test('the report says the result and both p95 in pt-BR', () => {
  const markdown = dataRequestsMarkdown({
    date: '2026-11-10',
    users: 20,
    durationS: 300,
    guestSamples: 3000,
    adminSamples: 300,
    guestP95Ms: 120.4,
    adminP95Ms: null,
    unexpected: 0,
    limitsMs: DATA_REQUEST_LIMITS_MS,
    failures: ['no admin sample'],
    passed: false,
  });

  assert.match(markdown, /U4 Pedidos de titular \(2026-11-10\)/);
  assert.match(markdown, /\*\*reprovado\*\* \(no admin sample\)/);
  assert.match(markdown, /120 ms \| 500 ms/);
  assert.match(markdown, /sem amostra \| 1000 ms/);
});
