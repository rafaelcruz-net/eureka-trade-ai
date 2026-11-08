// U6, Build and Test loop 3, failure F12 (NFR2.1, NFR2.5). Run with:
//   node --test scripts/load/load-test-u6-evaluate.test.mjs
// The defect: the p95 of OrderGate.evaluate in each window came from the bucket bounds of the Micrometer percentile
// histogram (..., 16.78, 22.37, 27.96, 33.55 ms, ...), so a p95 of 22.3 ms before the purge and 22.5 ms with it read as
// 22.37 against 27.96 ms (25% above) and failed the rule "at most 10% above". The p95 now comes from the samples of
// the load driver (microseconds, nearest rank), and the rules (10%, 250 ms) are unchanged. No database, no network.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  evaluateP95,
  evaluateWindowFailures,
  parseEvaluateSamples,
  U6_EVALUATE_LIMITS,
} from './u6-evaluate.mjs';

/** 100 samples in microseconds whose nearest-rank p95 is exactly {@code p95Ms}: 94 below it, then it, then above. */
function window(p95Ms, belowMs = 15) {
  const micros = (ms) => Math.round(ms * 1000);
  return [
    ...Array.from({ length: 94 }, (_, i) => micros(belowMs) + i),
    micros(p95Ms),
    ...Array.from({ length: 5 }, (_, i) => micros(p95Ms) + 100 + i),
  ];
}

test('the limits are the approved ones: 250 ms in each window and at most 10% above', () => {
  assert.deepEqual(U6_EVALUATE_LIMITS, { evaluateS: 0.25, withPurgeRatio: 1.1 });
});

test('the p95 is the nearest rank of the samples, far finer than a histogram bucket', () => {
  const oneToHundredMs = Array.from({ length: 100 }, (_, i) => (i + 1) * 1000);
  assert.equal(evaluateP95(oneToHundredMs), 0.095);
  // A window around 22 ms: the value itself, not the bucket bound 22.369621 or 27.962026 ms.
  assert.ok(Math.abs(evaluateP95(window(22.5)) - 0.0225) < 0.00001);
  assert.ok(Math.abs(evaluateP95(window(10.2, 5)) - 0.0102) < 0.00001);
  assert.equal(evaluateP95([]), null);
});

test('two windows less than 10% apart on either side of an old bucket bound pass (F12 of loop 3)', () => {
  // 22.3 ms is under the bound 22.369621 ms and 22.5 ms above it: the histogram read 22.37 against 27.96 ms.
  assert.deepEqual(evaluateWindowFailures(window(22.3), window(22.5)), []);
});

test('two windows less than 10% apart inside the same old bucket pass', () => {
  assert.deepEqual(evaluateWindowFailures(window(23.0), window(25.0)), []);
});

test('a real difference above 10% still fails, and so does a p95 above 250 ms', () => {
  assert.deepEqual(evaluateWindowFailures(window(22.0), window(25.0)), ['evaluate with the purge more than 10% above']);
  assert.deepEqual(evaluateWindowFailures(window(30.0), window(33.1)), ['evaluate with the purge more than 10% above']);
  assert.deepEqual(evaluateWindowFailures(window(240.0, 200), window(251.0, 200)), ['p95 evaluate with the purge']);
  assert.deepEqual(evaluateWindowFailures(window(251.0, 200), window(251.0, 200)), [
    'p95 evaluate before the purge',
    'p95 evaluate with the purge',
  ]);
});

test('a window without samples fails instead of passing in silence', () => {
  assert.deepEqual(evaluateWindowFailures([], window(20.0)), ['p95 evaluate before the purge']);
  assert.deepEqual(evaluateWindowFailures(window(20.0), []), ['p95 evaluate with the purge']);
});

test('the samples are read from the details of the health component loadtestRisk', () => {
  assert.deepEqual(parseEvaluateSamples({ evaluateMicros: { 'before-purge': [1200, 900], 'with-purge': [1500] } }), {
    beforePurge: [1200, 900],
    withPurge: [1500],
  });
  assert.deepEqual(parseEvaluateSamples({}), { beforePurge: [], withPurge: [] });
  assert.throws(() => parseEvaluateSamples({ evaluateMicros: { 'before-purge': [1.5] } }), /not a whole number/);
  assert.throws(() => parseEvaluateSamples({ evaluateMicros: { 'with-purge': [-1] } }), /not a whole number/);
});
