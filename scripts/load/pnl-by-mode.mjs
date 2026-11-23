// 261121-paper-real-unico U2 (nfr-requirements NFR1.1 to NFR1.9, D-NFR1, D-NFR3; performance-design.md "Portão de
// carga"): the scenario pnl-by-mode of scripts/load/load-test.mjs. 20 virtual users, one per seeded person, each reading
// every 2 s, alternating GET /api/v1/pnl/by-mode and GET /api/v1/real/pnl, for DURATION_S after 60 s of warm-up that
// are not counted. The gates:
//   - p95 of GET /api/v1/pnl/by-mode at most 1 s (NFR1.1), from the server histogram;
//   - p95 of the attribution of the P&L path at most 500 ms (NFR1.3), pnl_real_attribution_read_seconds{path="PNL"};
//   - 0 answers other than 200;
//   - 0 reconciliation mismatches (pnl_real_reconciliation_mismatches_total does not rise, NFR2.11);
//   - REAL blocks UNAVAILABLE by TIMEOUT in at most 0.5% of the reads of C4 counted after the warm-up, and 0 by any
//     other cause, from pnl_mode_block_unavailable_total{market="REAL",cause}; the blocks counted from the answers equal
//     the counter;
//   - the plan of the main REAL read uses its index (scripts/load/pnl-plans.mjs);
//   - the pending pool and the memory limits of the other scenarios.
// Everything that touches the network comes through io, so the gates are tested without a server.

export const PNL_LIMITS = {
  byModeP95Seconds: 1.0,
  attributionP95Seconds: 0.5,
  timeoutShare: 0.005,
  pendingSeconds: 10,
};

export const CAUSES = ['TIMEOUT', 'REJECTED', 'ERROR', 'RECONCILIATION_MISMATCH', 'TOO_MANY_ORDERS'];

/** The value of one series of the Prometheus text, or 0 when it is absent. */
export function series(metrics, name, labels = {}) {
  let total = 0;
  for (const line of String(metrics ?? '').split('\n')) {
    if (!line.startsWith(`${name}{`) && !line.startsWith(`${name} `)) continue;
    const open = line.indexOf('{');
    const close = line.indexOf('}');
    const text = open >= 0 && close > open ? line.slice(open + 1, close) : '';
    const matches = Object.entries(labels).every(([key, value]) => text.includes(`${key}="${value}"`));
    if (matches) total += Number(line.slice((close >= 0 ? close : name.length) + 1).trim().split(' ')[0]);
  }
  return total;
}

/** The counters the gates compare before and after the measurement. */
export function snapshot(metrics) {
  const real = {};
  for (const cause of CAUSES) {
    real[cause] = series(metrics, 'pnl_mode_block_unavailable_total', { market: 'REAL', cause });
  }
  return {
    unavailable: real,
    mismatches: series(metrics, 'pnl_real_reconciliation_mismatches_total'),
  };
}

/** p95 of a Prometheus histogram from its cumulative buckets (seconds), the smallest bound with 95% of the count. */
export function histogramP95(metrics, name, labels) {
  const buckets = [];
  for (const line of String(metrics ?? '').split('\n')) {
    if (!line.startsWith(`${name}_bucket{`)) continue;
    const text = line.slice(line.indexOf('{') + 1, line.indexOf('}'));
    if (!Object.entries(labels).every(([key, value]) => text.includes(`${key}="${value}"`))) continue;
    const le = /le="([^"]+)"/.exec(text)?.[1];
    const count = Number(line.slice(line.indexOf('}') + 1).trim().split(' ')[0]);
    buckets.push({ le: le === '+Inf' ? Infinity : Number(le), count });
  }
  if (buckets.length === 0) return null;
  const merged = new Map();
  for (const bucket of buckets) merged.set(bucket.le, (merged.get(bucket.le) ?? 0) + bucket.count);
  const sorted = [...merged.entries()].sort((a, b) => a[0] - b[0]);
  const total = sorted[sorted.length - 1][1];
  if (total === 0) return null;
  for (const [le, count] of sorted) {
    if (count >= 0.95 * total) return le;
  }
  return Infinity;
}

/** D-NFR1: the failures of one run of the scenario, from the measured values; empty means passed. */
export function pnlByModeFailures(result) {
  const failures = [];
  if (result.byModeP95Seconds === null || result.byModeP95Seconds > PNL_LIMITS.byModeP95Seconds) {
    failures.push('p95 /api/v1/pnl/by-mode');
  }
  if (result.attributionP95Seconds === null || result.attributionP95Seconds > PNL_LIMITS.attributionP95Seconds) {
    failures.push('p95 attribution PNL');
  }
  if (result.non200 > 0) failures.push('answers other than 200');
  if (result.mismatches > 0) failures.push('reconciliation mismatch');
  if (result.readsByMode === 0) failures.push('no read of C4');
  if (result.unavailable.TIMEOUT > PNL_LIMITS.timeoutShare * result.readsByMode) {
    failures.push('REAL blocks UNAVAILABLE by timeout above 0.5%');
  }
  for (const cause of CAUSES.filter((name) => name !== 'TIMEOUT')) {
    if (result.unavailable[cause] > 0) failures.push(`REAL blocks UNAVAILABLE by ${cause}`);
  }
  const counted = CAUSES.reduce((sum, cause) => sum + result.unavailable[cause], 0);
  if (counted !== result.unavailableByAnswers) failures.push('UNAVAILABLE blocks of the answers differ from the counter');
  if (!result.plan.passed) failures.push('the main REAL read without its index');
  if (result.longestPendingSeconds > PNL_LIMITS.pendingSeconds) failures.push('pool wait');
  for (const [name, mb] of Object.entries(result.maxMemoryMb ?? {})) {
    if (mb > result.memoryLimitMb) failures.push(`memory ${name}`);
  }
  return failures;
}

/**
 * The traffic: one reader per person, a read every 2 s alternating the two routes, the warm-up not counted. Returns the
 * reads of C4 after the warm-up, the answers other than 200 and the REAL blocks UNAVAILABLE seen in the answers.
 */
export async function pnlByModeTraffic({ people, warmupS, durationS, io }) {
  const start = io.now();
  const warmupEnd = start + warmupS * 1_000;
  const end = warmupEnd + durationS * 1_000;
  const counts = { readsByMode: 0, readsReal: 0, non200: 0, unavailableByAnswers: 0 };
  const reader = async (person, index) => {
    let turn = index % 2;
    while (io.now() < end) {
      const counted = io.now() >= warmupEnd;
      const route = turn === 0 ? '/api/v1/pnl/by-mode' : '/api/v1/real/pnl';
      const response = await io.get(route, person);
      if (counted) {
        if (response.status !== 200) {
          counts.non200++;
        } else if (route === '/api/v1/pnl/by-mode') {
          counts.readsByMode++;
          if (response.body?.real?.status === 'UNAVAILABLE') counts.unavailableByAnswers++;
        } else {
          counts.readsReal++;
        }
      }
      turn = 1 - turn;
      await io.sleep(2_000);
    }
  };
  await Promise.all(people.map((person, index) => reader(person, index)));
  return counts;
}
