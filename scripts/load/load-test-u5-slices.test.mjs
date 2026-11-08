// Regression of the U5 load checks (Build and Test loop 2, failures F7 and F9; NFR2.2, NFR2.3). Run with:
//   node --test scripts/load/load-test-u5-slices.test.mjs
// F7: the run expected Math.floor(DURATION_S / 300) = 6 completed slices in 30 minutes, but the slices are 5 minutes
// aligned to the clock, the run starts inside one of them and the cycles of a slice run from slotStart + 60 s, so only
// the slices that fit whole in the run can be complete. Fixed instants only; no database, no network.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import * as failures from './u5-failures.mjs';
import { expectedU5Slices, SLICE_MS, u5SlicesSql } from './u5-slices.mjs';

const at = (iso) => Date.parse(iso);
const iso = (slices) => slices.map((slice) => new Date(slice).toISOString());
const THIRTY_MINUTES_MS = 1_800_000;

test('a run that starts exactly at the start of a slice expects the 5 slices that end by its end minus 60 s', () => {
  const start = at('2026-11-07T12:00:00.000Z');

  const slices = expectedU5Slices(start, start + THIRTY_MINUTES_MS);

  assert.deepEqual(iso(slices), [
    '2026-11-07T12:00:00.000Z',
    '2026-11-07T12:05:00.000Z',
    '2026-11-07T12:10:00.000Z',
    '2026-11-07T12:15:00.000Z',
    '2026-11-07T12:20:00.000Z',
  ]);
});

test('a run that starts in the middle of a slice leaves that slice out and expects the next whole ones', () => {
  const start = at('2026-11-07T12:02:30.000Z');

  const slices = expectedU5Slices(start, start + THIRTY_MINUTES_MS);

  assert.deepEqual(iso(slices), [
    '2026-11-07T12:05:00.000Z',
    '2026-11-07T12:10:00.000Z',
    '2026-11-07T12:15:00.000Z',
    '2026-11-07T12:20:00.000Z',
    '2026-11-07T12:25:00.000Z',
  ]);
});

test('a slice that ends after the end of the run minus 60 s is not expected; one that ends exactly then is', () => {
  const start = at('2026-11-07T12:00:00.000Z');
  const lastWhole = at('2026-11-07T12:25:00.000Z');

  const oneSecondShort = expectedU5Slices(start, at('2026-11-07T12:30:59.000Z'));
  const exactly = expectedU5Slices(start, at('2026-11-07T12:31:00.000Z'));

  assert.equal(oneSecondShort.length, 5);
  assert.ok(!oneSecondShort.includes(lastWhole), 'the slice 12:25 ends at 12:30, after 12:29:59');
  assert.equal(exactly.length, 6);
  assert.equal(exactly.at(-1), lastWhole);
  for (const slice of exactly) assert.equal(slice % SLICE_MS, 0, 'every slice is aligned to 5 minutes');
});

test('a run too short for a whole slice expects none, and a bad instant is refused', () => {
  const start = at('2026-11-07T12:02:30.000Z');

  assert.deepEqual(expectedU5Slices(start, start + 5 * 60_000), []);
  assert.throws(() => expectedU5Slices(Number.NaN, start), /startedAtMs/);
  assert.throws(() => expectedU5Slices(start, '2026-11-07'), /endedAtMs/);
  assert.throws(() => expectedU5Slices(start + 1, start), /ends before it starts/);
});

test('the query counts the completed cycles of exactly the expected slices', () => {
  const slices = [at('2026-11-07T12:05:00.000Z'), at('2026-11-07T12:10:00.000Z')];

  assert.equal(
    u5SlicesSql(slices),
    "select count(distinct slot_start) from bot_cycle where status = 'COMPLETED' " +
      "and slot_start in ('2026-11-07T12:05:00.000Z', '2026-11-07T12:10:00.000Z')",
  );
  assert.equal(u5SlicesSql([]), 'select 0');
  assert.throws(() => u5SlicesSql(["2026-11-07' or '1'='1"]), /slice is not an instant/);
});

test('the load script takes the slices from this module and no longer from the duration alone', () => {
  const runner = readFileSync(new URL('./load-test.mjs', import.meta.url), 'utf8');

  assert.match(runner, /from '\.\/u5-slices\.mjs'/);
  assert.doesNotMatch(runner, /Math\.floor\(DURATION_S \/ 300\)/);
  assert.doesNotMatch(runner, /Math\.floor\(report\.durationSeconds \/ 300\)/);
  assert.match(runner, /report\.failures = u5Failures\(report, U5_LIMITS, MEMORY_LIMIT_MB\);/);
  assert.match(runner, /\$\{u5DecisionsLine\(db\)\}/);
});

// F9: the timer loadtest_bots_gate_seconds of the worker stayed empty (no order reached the barrier), timerP95 gave
// null and the run only said "p95 barrier plus execution", as if the barrier had been slow.
const LIMITS = { cycleS: 2.0, gateS: 0.5, routesS: 1.0, screenS: 2.0, liveS: 4.0, maxConnections: 14, acquireS: 1.0 };
const MEMORY_LIMIT_MB = 900;

/** A report of a run that meets every limit; each test changes only the field under test. */
function passingReport() {
  return {
    cycle: 0.134,
    gate: 0.21,
    screen: 0.026,
    live: 1.919,
    routes: { '/api/v1/bots': 0.008, '/api/v1/bots/{botId}': 0.007 },
    database: {
      checked: true,
      staleDecisions: 0,
      slices: 5,
      slicesExpected: 5,
      buyDecisions: 12,
      sellDecisions: 7,
    },
    stopwatch: { lines: 372, maxTotalMs: 1126, failed: 0 },
    workerPool: { maxActive: 6, maxAcquireS: 0.001 },
    pool: { longestPendingSeconds: 0 },
    errors: 0,
    maxMemoryMb: { api: 377, postgres: 457, worker: 278 },
  };
}

test('a run that meets every limit has no failure', () => {
  assert.deepEqual(failures.u5Failures(passingReport(), LIMITS, MEMORY_LIMIT_MB), []);
});

test('an empty barrier timer fails with "nenhuma ordem chegou à barreira", never as a slow barrier', () => {
  const report = { ...passingReport(), gate: null };

  const found = failures.u5Failures(report, LIMITS, MEMORY_LIMIT_MB);

  assert.deepEqual(found, ['nenhuma ordem chegou à barreira']);
});

test('a filled barrier timer above 500 ms still fails as "p95 barrier plus execution"; at 500 ms it passes', () => {
  assert.deepEqual(failures.u5Failures({ ...passingReport(), gate: 0.51 }, LIMITS, MEMORY_LIMIT_MB), [
    'p95 barrier plus execution',
  ]);
  assert.deepEqual(failures.u5Failures({ ...passingReport(), gate: 0.5 }, LIMITS, MEMORY_LIMIT_MB), []);
});

test('stale decisions and a missing slice still fail; the limits are the ones the caller gives', () => {
  const report = passingReport();
  report.database = { ...report.database, staleDecisions: 1, slices: 4 };

  assert.deepEqual(failures.u5Failures(report, LIMITS, MEMORY_LIMIT_MB), [
    'decisions for stale data',
    'fewer completed slices than expected',
  ]);
  assert.deepEqual(failures.u5Failures({ ...passingReport(), cycle: null }, LIMITS, MEMORY_LIMIT_MB), [
    'p95 cycle',
  ]);
});

test('the report shows how many BUY and SELL decisions came out during the run', () => {
  assert.equal(
    failures.u5DecisionsLine(passingReport().database),
    '- Decisões durante a carga: BUY 12, SELL 7 (sem elas, a barreira não tem o que medir)',
  );
  assert.equal(failures.u5DecisionsLine({ checked: false }), '- Decisões durante a carga: não conferido');
});
