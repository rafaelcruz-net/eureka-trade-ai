// Test of the load scenario "reacceptance" of u3-reacceptance (Passo 12). Run with:
//   node --test scripts/load/load-test-reacceptance.test.mjs
// No network and no database: the fetch, the clock and the SQL are doubles; synthetic values only.
import assert from 'node:assert/strict';
import { createPublicKey, sign, verify } from 'node:crypto';
import { test } from 'node:test';
import {
  ACCEPT_ROUTE,
  ACQUIRE_TIMER,
  API_POOL,
  assertLocal,
  base58,
  CHECK_TIMER,
  evaluateReacceptance,
  histogram,
  LOCKED_ROUTE,
  newWallet,
  outdatedGuestStatements,
  p95BetweenMs,
  reacceptanceMarkdown,
  REACCEPTANCE_LIMITS_MS,
  runReacceptance,
  snapshot,
} from './reacceptance.mjs';

/**
 * A Prometheus text with the acceptance route, the lock timer and the connection wait of the api pool, `accept`,
 * `check` and `acquire` as {le: cumulative count}; `otherPoolAcquire` is the same histogram of another pool.
 */
function metrics({ accept = {}, check = {}, acquire = {}, otherPoolAcquire = {} } = {}) {
  const lines = [];
  for (const [le, count] of Object.entries(acquire)) {
    lines.push(`${ACQUIRE_TIMER}_bucket{pool="${API_POOL}",le="${le}"} ${count}`);
  }
  for (const [le, count] of Object.entries(otherPoolAcquire)) {
    lines.push(`${ACQUIRE_TIMER}_bucket{pool="memetrade-retention-pool",le="${le}"} ${count}`);
  }
  for (const [le, count] of Object.entries(accept)) {
    lines.push(
      `http_server_requests_seconds_bucket{error="none",exception="none",method="POST",outcome="SUCCESS",status="200",uri="${ACCEPT_ROUTE}",le="${le}"} ${count}`,
    );
  }
  lines.push(
    'http_server_requests_seconds_bucket{method="GET",uri="/api/v1/bots",status="200",le="0.001"} 999',
  );
  for (const [le, count] of Object.entries(check)) {
    lines.push(`${CHECK_TIMER}_bucket{le="${le}"} ${count}`);
  }
  return lines.join('\n');
}

/**
 * A fake api: the guests start outdated; an acceptance of the current version unlocks the person of the token.
 * `accepts` decides the status of the acceptance; `lock` decides whether the lock answers 403 before the acceptance.
 */
function fakeApi({ current = 'v2', accepts = () => 200, lock = true, metricsTexts } = {}) {
  let tokens = 0;
  const unlocked = new Set();
  const inserted = [];
  const calls = [];
  const texts = metricsTexts ?? [metrics(), metrics({ accept: { '0.1': 20, '+Inf': 20 }, check: { '0.002': 40, '+Inf': 40 } })];
  let metricsRead = 0;
  const respond = (status, body) => ({ status, json: async () => body, text: async () => '' });
  const io = {
    apiBase: 'http://127.0.0.1:8080',
    managementBase: 'http://127.0.0.1:8081',
    now: () => Date.parse('2026-11-10T12:00:00Z'),
    warn: () => undefined,
    insertOutdatedGuest: async (statements) => inserted.push(statements),
    fetch: async (url, init = {}) => {
      calls.push({ url, init });
      if (url.endsWith('/actuator/prometheus')) {
        const text = texts[Math.min(metricsRead++, texts.length - 1)];
        return { status: 200, text: async () => text };
      }
      const path = url.slice('http://127.0.0.1:8080'.length);
      const token = init.headers?.Authorization?.slice('Bearer '.length);
      if (path === '/api/v1/legal/current') return respond(200, { version: current });
      if (path === '/api/v1/auth/challenge') return respond(200, { message: 'mensagem de teste' });
      if (path === '/api/v1/auth/verify') return respond(200, { accessToken: `token-${tokens++}` });
      if (path === LOCKED_ROUTE) {
        return lock && !unlocked.has(token)
          ? respond(403, { code: 'TERMS_REACCEPTANCE_REQUIRED', currentVersion: current })
          : respond(200, { items: [] });
      }
      if (path === ACCEPT_ROUTE) {
        const status = accepts(JSON.parse(init.body).version);
        if (status === 200) unlocked.add(token);
        return respond(status, status === 200 ? { upToDate: true } : { code: 'TERMS_VERSION_OUTDATED' });
      }
      return respond(404, {});
    },
  };
  return { io, inserted, calls };
}

test('an outdated guest is the same four rows as the e2e spec, with one acceptance of v1 only', () => {
  const statements = outdatedGuestStatements('Addr111', 'v1', {
    tenantId: 't',
    userId: 'u',
    walletId: 'w',
    acceptanceId: 'a',
  });

  assert.equal(statements.length, 4);
  assert.match(statements[1].text, /'BETA_USER'/);
  assert.deepEqual(statements[2].values, ['w', 'u', 'Addr111']);
  assert.match(statements[3].text, /null, 'REACCEPTANCE'/);
  assert.deepEqual(statements[3].values, ['a', 'u', 't', 'v1']);
  for (const statement of statements) {
    assert.doesNotMatch(statement.text, /Addr111/, 'values are parameters, never inlined');
  }
});

test('reads the cumulative buckets of one series and the p95 between two snapshots', () => {
  const before = histogram(metrics({ check: { '0.001': 10, '0.005': 10, '+Inf': 10 } }), CHECK_TIMER);
  const after = histogram(metrics({ check: { '0.001': 10, '0.005': 29, '+Inf': 30 } }), CHECK_TIMER);

  assert.equal(p95BetweenMs(before, after), 5);
  assert.equal(p95BetweenMs(after, after), null);
  const accept = snapshot(metrics({ accept: { '0.25': 19, '0.5': 20, '+Inf': 20 } })).accept;
  assert.equal(accept.get(0.5), 20);
  assert.equal(accept.size, 3, 'the GET of the bots is another series');
});

test('a p95 above the limits, a missing sample or a person that never passed fails the run', () => {
  const complete = { locked: true, accepted: true, passedAfter: true };
  assert.deepEqual(evaluateReacceptance({ acceptP95Ms: 250, checkP95Ms: 2, people: [complete] }), {
    failures: [],
    passed: true,
  });
  assert.deepEqual(
    evaluateReacceptance({
      acceptP95Ms: REACCEPTANCE_LIMITS_MS.accept + 1,
      checkP95Ms: REACCEPTANCE_LIMITS_MS.check + 1,
      people: [complete, { ...complete, passedAfter: false }],
    }).failures,
    ['p95 acceptance', 'p95 lock check', '1 people without lock -> acceptance -> pass'],
  );
  assert.deepEqual(evaluateReacceptance({ acceptP95Ms: null, checkP95Ms: null, people: [] }).failures, [
    'no acceptance sample',
    'no lock check sample',
  ]);
});

test('the run creates 20 outdated guests and each goes lock -> acceptance -> pass', async () => {
  const { io, inserted, calls } = fakeApi();

  const report = await runReacceptance({ users: 20, io });

  assert.equal(inserted.length, 20);
  assert.equal(report.passed, true, report.failures.join(', '));
  assert.equal(report.locked, 20);
  assert.equal(report.accepted, 20);
  assert.equal(report.passedAfter, 20);
  assert.equal(report.acceptP95Ms, 100);
  assert.equal(report.checkP95Ms, 2);
  assert.equal(report.currentVersion, 'v2');
  const accepts = calls.filter((call) => call.url.endsWith(ACCEPT_ROUTE));
  assert.equal(accepts.length, 20);
  assert.ok(accepts.every((call) => call.init.headers['Idempotency-Key']), 'every POST has an Idempotency-Key');
  assert.equal(new Set(accepts.map((call) => call.init.headers['Idempotency-Key'])).size, 20);
});

test('reads the connection wait of the api pool only, between the two snapshots', () => {
  const before = snapshot(
    metrics({ acquire: { '0.001': 5, '0.01': 5, '+Inf': 5 }, otherPoolAcquire: { '0.001': 0, '0.5': 100, '+Inf': 100 } }),
  ).acquire;
  const after = snapshot(
    metrics({ acquire: { '0.001': 25, '0.01': 45, '+Inf': 45 }, otherPoolAcquire: { '0.001': 0, '0.5': 900, '+Inf': 900 } }),
  ).acquire;

  assert.equal(after.get(0.01), 45, 'only the series of the api pool');
  assert.equal(p95BetweenMs(before, after), 10);
  assert.equal(p95BetweenMs(after, after), null);
});

test('the connection wait is reported next to the lock check and never fails the run', async () => {
  const { io } = fakeApi({
    metricsTexts: [
      metrics({ acquire: { '0.001': 0, '0.05': 0, '+Inf': 0 } }),
      metrics({
        accept: { '0.1': 20, '+Inf': 20 },
        check: { '0.002': 40, '+Inf': 40 },
        acquire: { '0.001': 10, '0.05': 40, '+Inf': 40 },
      }),
    ],
  });

  const report = await runReacceptance({ users: 2, io });

  assert.equal(report.acquireP95Ms, 50);
  assert.equal(report.checkP95Ms, 2);
  assert.equal(report.passed, true, 'a slow connection wait is information, not a failure');
  const markdown = reacceptanceMarkdown(report);
  assert.match(markdown, /hikaricp_connections_acquire_seconds/);
  assert.match(markdown, /50 ms \| só informativo/);
});

test('without samples of the connection wait the report says so and the run still passes', async () => {
  const report = await runReacceptance({ users: 2, io: fakeApi().io });

  assert.equal(report.acquireP95Ms, null);
  assert.equal(report.passed, true);
  assert.match(reacceptanceMarkdown(report), /sem amostra \| só informativo/);
});

test('a slow acceptance or lock check fails the run', async () => {
  const { io } = fakeApi({
    metricsTexts: [metrics(), metrics({ accept: { '0.6': 20, '+Inf': 20 }, check: { '0.008': 40, '+Inf': 40 } })],
  });

  const report = await runReacceptance({ users: 3, io });

  assert.equal(report.passed, false);
  assert.deepEqual(report.failures, ['p95 acceptance', 'p95 lock check']);
});

test('refused acceptances or a missing lock fail the run', async () => {
  const refused = await runReacceptance({ users: 2, io: fakeApi({ accepts: () => 409 }).io });
  const unlockedFromTheStart = await runReacceptance({ users: 2, io: fakeApi({ lock: false }).io });

  assert.equal(refused.passed, false);
  assert.equal(refused.accepted, 0);
  assert.equal(unlockedFromTheStart.passed, false);
  assert.equal(unlockedFromTheStart.locked, 0);
});

test('a catalog without a version newer than v1 stops the run before any insert', async () => {
  const { io, inserted } = fakeApi({ current: 'v1' });

  await assert.rejects(runReacceptance({ users: 2, io }), /newer than v1/);
  assert.equal(inserted.length, 0);
});

test('only localhost is a target', async () => {
  assert.doesNotThrow(() => assertLocal('http://127.0.0.1:8080'));
  assert.doesNotThrow(() => assertLocal('http://localhost:8081'));
  assert.throws(() => assertLocal('https://api.example.com'), /only targets localhost/);
  const { io } = fakeApi();
  await assert.rejects(runReacceptance({ users: 1, io: { ...io, apiBase: 'http://10.0.0.5:8080' } }), /localhost/);
});

test('the wallets are generated here and sign verifiably; base58 keeps leading zeros', () => {
  const wallet = newWallet();
  const message = Buffer.from('mensagem', 'utf8');
  const signature = sign(null, message, wallet.key);

  assert.ok(verify(null, message, createPublicKey(wallet.key), signature));
  assert.match(wallet.address, /^[1-9A-HJ-NP-Za-km-z]{32,44}$/);
  assert.equal(base58(Uint8Array.from([0, 0, 1])), '112');
});

test('the report in pt-BR names the limits and the result', () => {
  const markdown = reacceptanceMarkdown({
    date: '2026-11-10',
    users: 20,
    outdatedVersion: 'v1',
    currentVersion: 'v2',
    passed: false,
    failures: ['p95 acceptance'],
    acceptP95Ms: 600,
    checkP95Ms: null,
    limitsMs: REACCEPTANCE_LIMITS_MS,
    locked: 20,
    accepted: 20,
    passedAfter: 20,
  });

  assert.match(markdown, /reprovado/);
  assert.match(markdown, /600 ms \| 500 ms/);
  assert.match(markdown, /sem amostra \| 5 ms/);
});
