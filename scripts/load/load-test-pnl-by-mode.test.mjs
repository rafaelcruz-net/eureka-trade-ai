// 261121-paper-real-unico U2, Etapa 25 (written after the layer; NFR1.1 to NFR1.6, D-NFR1): the gates of the scenario
// pnl-by-mode and its traffic, without a server. 261121-paper-real-unico U3, Etapa 26 (NFR1.6, D-NFR2, R-04): the cycle
// of 9 labelled reads, the cursor of C5, the p95 by the nearest rank, each new gate failing on its own, the gates of
// the U2 unchanged, the four cases of the reading rule of the report and its Markdown. Run with:
//   node --test "scripts/load/*.test.mjs"
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  CAUSES,
  CYCLE,
  NEW_READS,
  PNL_LIMITS,
  histogramP95,
  nearestRankP95,
  pnlByModeFailures,
  pnlByModeGroups,
  pnlByModeTraffic,
  pnlViewsMarkdown,
  rangeOf,
  readStats,
  requestOf,
  series,
  snapshot,
  targetsOf,
  u2Failures,
  u3Failures,
} from './pnl-by-mode.mjs';

const zero = () => Object.fromEntries(CAUSES.map((cause) => [cause, 0]));
const byLabel = (value = 0) => Object.fromEntries(CYCLE.map((label) => [label, value]));
const reads = (p95 = 0.4) =>
  Object.fromEntries(CYCLE.map((label) => [label, { count: 600, p50: 0.2, p95, p99: 0.6 }]));

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
    reads: reads(),
    non200ByLabel: byLabel(),
    unavailableByLabel: byLabel(),
    missingCursor: 0,
    ...overrides,
  };
}

const PERSON = {
  targets: { linesBotId: 'bot-r', comparePaperBotId: 'bot-p', compareRealBotId: 'bot-r2' },
  cursor: null,
};

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

test('U3: the share of timeouts is over every read of C4 (ALL and RANGE), the limits of the U2 unchanged', () => {
  assert.equal(PNL_LIMITS.byModeP95Seconds, 1.0);
  assert.equal(PNL_LIMITS.attributionP95Seconds, 0.5);
  assert.equal(PNL_LIMITS.timeoutShare, 0.005);
  const timeouts = { ...zero(), TIMEOUT: 45 };
  assert.deepEqual(
    u2Failures(passing({ readsByMode: 6_000, readsC4: 18_000, unavailable: timeouts, unavailableByAnswers: 45 })),
    [],
  );
});

test('the counters and the p95 are read from the Prometheus text', () => {
  const metrics = [
    'pnl_mode_block_unavailable_total{cause="TIMEOUT",market="REAL",} 3.0',
    'pnl_mode_block_unavailable_total{cause="TIMEOUT",market="PAPER",} 9.0',
    'pnl_mode_block_unavailable_total{cause="ERROR",market="REAL",} 1.0',
    'pnl_compare_side_unavailable_total{cause="TIMEOUT",market="REAL",} 2.0',
    'pnl_compare_side_unavailable_total{cause="TIMEOUT",market="PAPER",} 1.0',
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
    compareUnavailable: { TIMEOUT: 3, REJECTED: 0, ERROR: 0, RECONCILIATION_MISMATCH: 0, TOO_MANY_ORDERS: 0 },
    mismatches: 3,
  });
  assert.equal(histogramP95(metrics, 'pnl_real_attribution_read_seconds', { path: 'PNL' }), 0.25);
  assert.equal(histogramP95('', 'pnl_real_attribution_read_seconds', { path: 'PNL' }), null);
});

test('U3: the cycle has the 9 labelled reads and the 7 new ones', () => {
  assert.deepEqual(CYCLE, [
    'byModeAll',
    'realPnl',
    'byModeRange30',
    'byModeRange366',
    'linesFirst',
    'linesNext',
    'compareAll',
    'compareRange30',
    'compareRange366',
  ]);
  assert.deepEqual(NEW_READS, CYCLE.slice(2));
  assert.equal(PNL_LIMITS.readP95Seconds, 1.0);
});

test('U3: each read asks its route; RANGE ends now in UTC, in whole seconds, up to 366 days', () => {
  const now = Date.parse('2026-11-23T12:34:56.789Z');
  assert.deepEqual(rangeOf(30, now), { from: '2026-10-24T12:34:56Z', to: '2026-11-23T12:34:56Z' });
  assert.deepEqual(rangeOf(366, now), { from: '2025-11-22T12:34:56Z', to: '2026-11-23T12:34:56Z' });
  assert.equal(requestOf('byModeAll', PERSON, now), '/api/v1/pnl/by-mode');
  assert.equal(requestOf('realPnl', PERSON, now), '/api/v1/real/pnl');
  assert.equal(
    requestOf('byModeRange30', PERSON, now),
    '/api/v1/pnl/by-mode?period=RANGE&from=2026-10-24T12%3A34%3A56Z&to=2026-11-23T12%3A34%3A56Z',
  );
  assert.equal(requestOf('linesFirst', PERSON, now), '/api/v1/bots/bot-r/pnl/lines?limit=50');
  assert.equal(requestOf('linesNext', PERSON, now), null);
  assert.equal(
    requestOf('linesNext', { ...PERSON, cursor: 'abc' }, now),
    '/api/v1/bots/bot-r/pnl/lines?limit=50&cursor=abc',
  );
  assert.equal(requestOf('compareAll', PERSON, now), '/api/v1/pnl/compare?paperBotId=bot-p&realBotId=bot-r2&period=ALL');
  assert.match(requestOf('compareRange366', PERSON, now), /period=RANGE&from=2025-11-22T12%3A34%3A56Z/);
  for (const label of CYCLE) {
    assert.doesNotMatch(String(requestOf(label, { ...PERSON, cursor: 'c' }, now)), /tenantId|accountId|userId/);
  }
});

test('U3: the pair of C6 has the same templateId and C5 reads a REAL bot', () => {
  const paper = [
    { botId: 'p1', templateId: 'RANGE_BREAKOUT' },
    { botId: 'p2', templateId: 'MOMENTUM_VOLUME' },
  ];
  const real = [{ botId: 'r1', templateId: 'MOMENTUM_VOLUME' }];
  assert.deepEqual(targetsOf(paper, real), { linesBotId: 'r1', comparePaperBotId: 'p2', compareRealBotId: 'r1' });
  assert.equal(targetsOf(paper.slice(0, 1), real), null);
  assert.equal(targetsOf(paper, []), null);
});

test('U3: the p95 is the nearest rank (the 95th smallest of 100)', () => {
  const values = Array.from({ length: 100 }, (_, index) => (index + 1) / 100);
  assert.equal(nearestRankP95(values), 0.95);
  assert.equal(nearestRankP95([0.3]), 0.3);
  assert.equal(nearestRankP95([]), null);
  const stats = readStats({ byModeRange30: values });
  assert.deepEqual(stats.byModeRange30, { count: 100, p50: 0.5, p95: 0.95, p99: 0.99 });
  assert.deepEqual(stats.compareAll, { count: 0, p50: null, p95: null, p99: null });
});

test('U3: each new gate fails on its own, without touching the group U2', () => {
  for (const label of NEW_READS) {
    const slow = { ...reads(), [label]: { count: 600, p50: 0.5, p95: 1.001, p99: 1.2 } };
    assert.deepEqual(u3Failures(passing({ reads: slow })), [`p95 ${label}`]);
    assert.deepEqual(u2Failures(passing({ reads: slow })), []);
    const none = { ...reads(), [label]: { count: 0, p50: null, p95: null, p99: null } };
    assert.deepEqual(u3Failures(passing({ reads: none })), [`no read of ${label}`]);
    assert.deepEqual(u3Failures(passing({ non200ByLabel: { ...byLabel(), [label]: 1 } })), [
      `answers other than 200 in ${label}`,
    ]);
    assert.deepEqual(u3Failures(passing({ unavailableByLabel: { ...byLabel(), [label]: 1 } })), [
      `UNAVAILABLE in ${label}`,
    ]);
  }
  assert.deepEqual(u3Failures(passing({ missingCursor: 1 })), ['missing nextCursor']);
  assert.deepEqual(u3Failures(passing({ reads: reads(1.0) })), []);
});

test('U3: the reading rule of the report, four cases', () => {
  assert.deepEqual(pnlByModeGroups(passing()), {
    u2: { passed: true, failures: [] },
    u3: { passed: true, failures: [] },
    pending: [],
  });
  const onlyU3 = pnlByModeGroups(passing({ missingCursor: 2 }));
  assert.equal(onlyU3.u2.passed, true);
  assert.equal(onlyU3.u3.passed, false);
  assert.deepEqual(onlyU3.pending, ['U3']);
  const onlyU2 = pnlByModeGroups(passing({ mismatches: 1 }));
  assert.equal(onlyU2.u2.passed, false);
  assert.equal(onlyU2.u3.passed, true);
  assert.deepEqual(onlyU2.pending, ['U2', 'U3']);
  const both = pnlByModeGroups(passing({ mismatches: 1, missingCursor: 1 }));
  assert.deepEqual(both.pending, ['U2', 'U3']);
  // An answer other than 200 goes to the group of its read: a U2 read fails only U2, a U3 read only U3.
  const u3Read = pnlByModeGroups(passing({ non200ByLabel: { ...byLabel(), compareAll: 1 } }));
  assert.deepEqual([u3Read.u2.passed, u3Read.u3.passed], [true, false]);
  const u2Read = pnlByModeGroups(passing({ non200: 1, non200ByLabel: { ...byLabel(), realPnl: 1 } }));
  assert.deepEqual([u2Read.u2.passed, u2Read.u3.passed], [false, true]);
});

test('the traffic runs the 9 reads in order every 2 s, follows the cursor and counts only after the warm-up', async () => {
  let now = 0;
  const calls = [];
  const io = {
    now: () => now,
    sleep: async (ms) => {
      now += ms;
    },
    get: async (route, person) => {
      calls.push([person.name, route]);
      now += route.includes('/pnl/compare') ? 300 : 100;
      if (route.includes('/pnl/lines') && !route.includes('cursor=')) {
        return { status: 200, body: { items: [], nextCursor: `cursor-${now}` } };
      }
      if (route.startsWith('/api/v1/pnl/by-mode')) {
        return { status: 200, body: { real: { status: route.includes('RANGE') ? 'UNAVAILABLE' : 'AVAILABLE' } } };
      }
      if (route.startsWith('/api/v1/pnl/compare')) {
        return { status: 200, body: { paper: { status: 'AVAILABLE' }, real: { status: 'UNAVAILABLE' } } };
      }
      return { status: route.endsWith('/real/pnl') ? 503 : 200, body: {} };
    },
  };
  const person = { ...PERSON, name: 'a' };

  const counts = await pnlByModeTraffic({ people: [person], warmupS: 0, durationS: 18, io });

  const routes = calls.map(([, route]) => route);
  assert.equal(routes[0], '/api/v1/pnl/by-mode');
  assert.equal(routes[1], '/api/v1/real/pnl');
  assert.match(routes[2], /period=RANGE/);
  assert.match(routes[4], /\/pnl\/lines\?limit=50$/);
  assert.match(routes[5], /cursor=cursor-/);
  assert.match(routes[6], /period=ALL/);
  assert.equal(routes.length, 9);
  assert.equal(counts.times.compareAll[0], 0.3);
  assert.equal(counts.times.linesNext.length, 1);
  assert.equal(counts.readsByMode, 1);
  assert.equal(counts.readsC4, 3);
  assert.equal(counts.non200, 1);
  assert.equal(counts.non200ByLabel.realPnl, 1);
  assert.equal(counts.unavailableByAnswers, 2);
  assert.equal(counts.unavailableByLabel.byModeRange30, 1);
  assert.equal(counts.unavailableByLabel.compareRange366, 1);
  assert.equal(counts.missingCursor, 0);
});

test('the warm-up is not counted, and a next page without a cursor is counted as missing', async () => {
  let now = 0;
  const io = {
    now: () => now,
    sleep: async (ms) => {
      now += ms;
    },
    get: async (route) => ({
      status: 200,
      body: route.includes('/pnl/lines') ? { items: [], nextCursor: null } : { real: { status: 'AVAILABLE' } },
    }),
  };

  // 9 reads at 0, 2, …, 16 s: the first two (C4 ALL and the REAL P&L) fall in the warm-up of 4 s.
  const counts = await pnlByModeTraffic({ people: [{ ...PERSON }], warmupS: 4, durationS: 14, io });

  assert.equal(counts.times.byModeAll.length, 0);
  assert.equal(counts.missingCursor, 1);
  assert.equal(counts.times.linesNext.length, 0);
});

test('U3: the Markdown of the report has both groups, the pending Units and the times of each read', () => {
  const result = passing({ missingCursor: 1 });
  const report = {
    ...result,
    date: '2026-11-23',
    users: 20,
    warmupSeconds: 60,
    durationSeconds: 600,
    compareUnavailable: zero(),
    groups: pnlByModeGroups(result),
  };

  const text = pnlViewsMarkdown(report);

  assert.match(text, /# Carga — U3 visões do P&L, cenário pnl-by-mode \(2026-11-23\)/);
  assert.match(text, /Grupo U2: \*\*aprovado\*\*/);
  assert.match(text, /Grupo U3: \*\*reprovado\*\* \(missing nextCursor\)/);
  assert.match(text, /Pendentes: U3/);
  assert.match(text, /\| `compareRange366` \| 600 \| 0\.200 s \| 0\.400 s \| 0\.600 s \| 1 s \| 0 \| 0 \|/);
  assert.doesNotMatch(text, /total geral/i);
});
