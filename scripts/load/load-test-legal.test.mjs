// Test of the load scenario "legal" of u1-legal-documents (Passo 20). Run with:
//   node --test scripts/load/load-test-legal.test.mjs
// No network: the fetch, the clock and the sleep are doubles; synthetic values only.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  assertLocal,
  evaluateLegal,
  LEGAL_LIMITS_MS,
  legalMarkdown,
  legalPath,
  legalRole,
  percentile95,
  runLegal,
} from './legal.mjs';

/**
 * A virtual clock with a timer queue: every pending sleep of every user is registered before time moves, then time
 * jumps to the earliest one; each fetch sleeps `latency(url)` ms on the same clock, so concurrent users never inflate
 * each other's measured times.
 */
function virtualIo({ latency = () => 50, status = () => 200, fail = () => false } = {}) {
  let now = Date.parse('2026-11-09T12:00:00Z');
  const timers = [];
  let scheduled = false;
  function advance() {
    if (scheduled) return;
    scheduled = true;
    setImmediate(() => {
      scheduled = false;
      if (timers.length === 0) return;
      timers.sort((a, b) => a.at - b.at);
      const next = timers.shift();
      now = Math.max(now, next.at);
      next.resolve();
      advance();
    });
  }
  function sleep(ms) {
    return new Promise((resolve) => {
      timers.push({ at: now + ms, resolve });
      advance();
    });
  }
  const calls = [];
  return {
    calls,
    io: {
      webBase: 'http://127.0.0.1:3000',
      apiBase: 'http://127.0.0.1:8080',
      now: () => now,
      sleep,
      fetch: async (url) => {
        calls.push(url);
        if (fail(url)) throw new TypeError('fetch failed');
        await sleep(latency(url));
        return { status: status(url), arrayBuffer: async () => new ArrayBuffer(0) };
      },
      warn: () => undefined,
    },
  };
}

test('half of the users read the pages and half the api, alternating the two paths', () => {
  assert.deepEqual([0, 1, 2, 3].map(legalRole), ['page', 'api', 'page', 'api']);
  assert.equal(legalPath('page', 0), '/termos');
  assert.equal(legalPath('page', 1), '/privacidade');
  assert.equal(legalPath('api', 0), '/api/v1/legal/current');
  assert.equal(legalPath('api', 3), '/api/v1/legal/versions');
});

test('the p95 is the nearest rank and null without samples', () => {
  assert.equal(percentile95([]), null);
  assert.equal(percentile95([5]), 5);
  assert.equal(percentile95(Array.from({ length: 100 }, (_, i) => i + 1)), 95);
});

test('the limits are 2 s for the pages and 300 ms for the api', () => {
  assert.deepEqual(LEGAL_LIMITS_MS, { page: 2_000, api: 300 });
});

test('it passes under the limits and fails above each limit, on any 5xx, on another status and on a failed request', () => {
  const base = { page: [100, 200], api: [20, 30], serverErrors: 0, otherStatuses: 0, networkErrors: 0 };
  assert.equal(evaluateLegal(base).passed, true);
  assert.deepEqual(evaluateLegal({ ...base, page: [2_001] }).failures, ['p95 page']);
  assert.deepEqual(evaluateLegal({ ...base, api: [301] }).failures, ['p95 api']);
  assert.deepEqual(evaluateLegal({ ...base, serverErrors: 1 }).failures, ['5xx']);
  assert.deepEqual(evaluateLegal({ ...base, otherStatuses: 1 }).failures, ['unexpected status']);
  assert.deepEqual(evaluateLegal({ ...base, networkErrors: 1 }).failures, ['request failed']);
  assert.deepEqual(evaluateLegal({ ...base, page: [], api: [] }).failures, ['no page sample', 'no api sample']);
});

test('a run paces each user at one request every 2 s and measures both halves', async () => {
  const { io, calls } = virtualIo({ latency: (url) => (url.includes('/api/') ? 40 : 400) });

  const report = await runLegal({ users: 4, durationS: 10, io });

  assert.equal(report.passed, true);
  assert.equal(report.requests.page, 10);
  assert.equal(report.requests.api, 10);
  assert.equal(report.p95PageMs, 400);
  assert.equal(report.p95ApiMs, 40);
  assert.ok(calls.includes('http://127.0.0.1:3000/termos'));
  assert.ok(calls.includes('http://127.0.0.1:3000/privacidade'));
  assert.ok(calls.includes('http://127.0.0.1:8080/api/v1/legal/current'));
  assert.ok(calls.includes('http://127.0.0.1:8080/api/v1/legal/versions'));
});

test('a 5xx, a 429 or a failed request makes the run fail', async () => {
  const failing = virtualIo({ status: (url) => (url.endsWith('/versions') ? 503 : 200) });
  assert.deepEqual((await runLegal({ users: 2, durationS: 6, io: failing.io })).failures, ['5xx']);

  const limited = virtualIo({ status: (url) => (url.includes('/api/') ? 429 : 200) });
  assert.deepEqual((await runLegal({ users: 2, durationS: 6, io: limited.io })).failures, ['unexpected status']);

  const broken = virtualIo({ fail: (url) => url.endsWith('/termos') });
  assert.deepEqual((await runLegal({ users: 2, durationS: 6, io: broken.io })).failures, ['request failed']);
});

test('only localhost is ever targeted', () => {
  assert.doesNotThrow(() => assertLocal('http://127.0.0.1:8080'));
  assert.doesNotThrow(() => assertLocal('http://localhost:3000'));
  assert.throws(() => assertLocal('https://memetrade.example'), /only targets localhost/);
});

test('the report names the result, both p95 and the limits in pt-BR', async () => {
  const { io } = virtualIo();
  const report = await runLegal({ users: 2, durationS: 4, io });

  const text = legalMarkdown(report);
  assert.match(text, /# Carga local — U1 Documentos legais \(2026-11-09\)/);
  assert.match(text, /Resultado: \*\*aprovado\*\*/);
  assert.match(text, /\| p95 das páginas `\/termos` e `\/privacidade` \| 50 ms \| 2000 ms \|/);
  assert.match(text, /\| p95 de `\/api\/v1\/legal\/\*\*` \| 50 ms \| 300 ms \|/);
  assert.match(legalMarkdown({ ...report, passed: false, failures: ['p95 api'], p95ApiMs: null }), /reprovado\*\* \(p95 api\)[\s\S]*sem amostra/);
});
