// 261121-paper-real-unico U2, Etapa 25 (written after the layer; NFR1.1 to NFR1.6, D-NFR1): the gates of the scenario
// pnl-by-mode and its traffic, without a server. Run with:
//   node --test scripts/load/load-test-pnl-by-mode.test.mjs
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  CAUSES,
  histogramP95,
  pnlByModeFailures,
  pnlByModeTraffic,
  series,
  snapshot,
} from './pnl-by-mode.mjs';

const zero = () => Object.fromEntries(CAUSES.map((cause) => [cause, 0]));

function passing(overrides = {}) {
  return {
    byModeP95Seconds: 0.5,
    attributionP95Seconds: 0.25,
    non200: 0,
    mismatches: 0,
    readsByMode: 6_000,
    unavailable: zero(),
    unavailableByAnswers: 0,
    plan: { passed: true },
    longestPendingSeconds: 0,
    maxMemoryMb: { api: 600, postgres: 700 },
    memoryLimitMb: 900,
    ...overrides,
  };
}

test('a run within every limit passes', () => {
  assert.deepEqual(pnlByModeFailures(passing()), []);
});

test('each gate of D-NFR1 fails on its own', () => {
  assert.deepEqual(pnlByModeFailures(passing({ byModeP95Seconds: 1.01 })), ['p95 /api/v1/pnl/by-mode']);
  assert.deepEqual(pnlByModeFailures(passing({ attributionP95Seconds: 0.6 })), ['p95 attribution PNL']);
  assert.deepEqual(pnlByModeFailures(passing({ non200: 1 })), ['answers other than 200']);
  assert.deepEqual(pnlByModeFailures(passing({ mismatches: 1 })), ['reconciliation mismatch']);
  assert.deepEqual(pnlByModeFailures(passing({ plan: { passed: false } })), ['the main REAL read without its index']);
  assert.deepEqual(pnlByModeFailures(passing({ longestPendingSeconds: 11 })), ['pool wait']);
  assert.deepEqual(pnlByModeFailures(passing({ maxMemoryMb: { api: 901 } })), ['memory api']);
});

test('timeouts pass up to 0.5% of the reads of C4 and any other cause fails at once', () => {
  const within = { ...zero(), TIMEOUT: 30 };
  assert.deepEqual(pnlByModeFailures(passing({ unavailable: within, unavailableByAnswers: 30 })), []);
  const above = { ...zero(), TIMEOUT: 31 };
  assert.deepEqual(pnlByModeFailures(passing({ unavailable: above, unavailableByAnswers: 31 })), [
    'REAL blocks UNAVAILABLE by timeout above 0.5%',
  ]);
  for (const cause of ['REJECTED', 'ERROR', 'RECONCILIATION_MISMATCH', 'TOO_MANY_ORDERS']) {
    const one = { ...zero(), [cause]: 1 };
    assert.deepEqual(pnlByModeFailures(passing({ unavailable: one, unavailableByAnswers: 1 })), [
      `REAL blocks UNAVAILABLE by ${cause}`,
    ]);
  }
  assert.deepEqual(pnlByModeFailures(passing({ unavailableByAnswers: 2 })), [
    'UNAVAILABLE blocks of the answers differ from the counter',
  ]);
});

test('the counters and the p95 are read from the Prometheus text', () => {
  const metrics = [
    'pnl_mode_block_unavailable_total{cause="TIMEOUT",market="REAL",} 3.0',
    'pnl_mode_block_unavailable_total{cause="TIMEOUT",market="PAPER",} 9.0',
    'pnl_mode_block_unavailable_total{cause="ERROR",market="REAL",} 1.0',
    'pnl_real_reconciliation_mismatches_total{check="FEES",} 2.0',
    'pnl_real_reconciliation_mismatches_total{check="REALIZED",} 1.0',
    'pnl_real_attribution_read_seconds_bucket{outcome="OK",path="PNL",le="0.1",} 90.0',
    'pnl_real_attribution_read_seconds_bucket{outcome="OK",path="PNL",le="0.25",} 99.0',
    'pnl_real_attribution_read_seconds_bucket{outcome="OK",path="PNL",le="+Inf",} 100.0',
    'pnl_real_attribution_read_seconds_bucket{outcome="OK",path="GATE",le="0.1",} 0.0',
  ].join('\n');

  assert.equal(series(metrics, 'pnl_mode_block_unavailable_total', { market: 'REAL', cause: 'TIMEOUT' }), 3);
  assert.deepEqual(snapshot(metrics), {
    unavailable: { TIMEOUT: 3, REJECTED: 0, ERROR: 1, RECONCILIATION_MISMATCH: 0, TOO_MANY_ORDERS: 0 },
    mismatches: 3,
  });
  assert.equal(histogramP95(metrics, 'pnl_real_attribution_read_seconds', { path: 'PNL' }), 0.25);
  assert.equal(histogramP95('', 'pnl_real_attribution_read_seconds', { path: 'PNL' }), null);
});

test('the traffic alternates the two routes every 2 s and counts only after the warm-up', async () => {
  let now = 0;
  const calls = [];
  const io = {
    now: () => now,
    sleep: async (ms) => {
      now += ms;
    },
    get: async (route, person) => {
      calls.push([person, route]);
      return route === '/api/v1/pnl/by-mode'
        ? { status: 200, body: { real: { status: now % 8_000 === 0 ? 'UNAVAILABLE' : 'AVAILABLE' } } }
        : { status: 200, body: {} };
    },
  };

  const counts = await pnlByModeTraffic({ people: ['a'], warmupS: 4, durationS: 8, io });

  assert.deepEqual(
    calls.slice(0, 4).map(([, route]) => route),
    ['/api/v1/pnl/by-mode', '/api/v1/real/pnl', '/api/v1/pnl/by-mode', '/api/v1/real/pnl'],
  );
  assert.equal(counts.readsByMode + counts.readsReal, 4);
  assert.equal(counts.non200, 0);
  assert.equal(counts.unavailableByAnswers, 1);
});
