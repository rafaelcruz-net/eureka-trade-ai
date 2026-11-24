// 261121-paper-real-unico U2 (nfr-requirements NFR1.1 to NFR1.9, D-NFR1, D-NFR3; performance-design.md "Portão de
// carga"): the scenario pnl-by-mode of scripts/load/load-test.mjs. 20 virtual users, one per seeded person, each reading
// every 2 s, for DURATION_S after 60 s of warm-up that are not counted. The gates of the U2:
//   - p95 of GET /api/v1/pnl/by-mode at most 1 s (NFR1.1), from the server histogram;
//   - p95 of the attribution of the P&L path at most 500 ms (NFR1.3), pnl_real_attribution_read_seconds{path="PNL"};
//   - 0 answers other than 200 in its two reads;
//   - 0 reconciliation mismatches (pnl_real_reconciliation_mismatches_total does not rise, NFR2.11);
//   - REAL blocks UNAVAILABLE by TIMEOUT in at most 0.5% of the reads of C4 counted after the warm-up, and 0 by any
//     other cause, from pnl_mode_block_unavailable_total{market="REAL",cause}; the blocks counted from the answers of C4
//     equal the counter;
//   - the plan of the main REAL read uses its index (scripts/load/pnl-plans.mjs);
//   - the pending pool and the memory limits of the other scenarios.
//
// 261121-paper-real-unico U3 (NFR1.1, NFR1.3, NFR1.4, NFR1.6, NFR1.16; D-NFR2; cicd-pipeline.md R-04): the cycle has 9
// reads, labelled: the two of the U2 (C4 ALL and GET /api/v1/real/pnl) and 7 new ones (C4 RANGE of 30 and of 366 days,
// C5 first and next page, C6 ALL, RANGE of 30 and of 366 days). Each new read is timed by the client, only after the
// warm-up, because the server histogram by uri does not separate the periods; its gate is p95 at most 1 s by the
// nearest rank, plus 0 answers other than 200 and 0 UNAVAILABLE. The gates are read in TWO GROUPS, each with its own
// result: U2 red leaves U2 and U3 pending (the reads of the U3 run on the same REAL pass); only U3 red leaves only the
// U3 pending. Answers other than 200 and UNAVAILABLE go to the group of the read that received them, by its label. The
// limits of the U2 never change.
// Everything that touches the network comes through io, so the gates are tested without a server.

export const PNL_LIMITS = {
  byModeP95Seconds: 1.0,
  attributionP95Seconds: 0.5,
  timeoutShare: 0.005,
  pendingSeconds: 10,
  readP95Seconds: 1.0,
};

export const CAUSES = ['TIMEOUT', 'REJECTED', 'ERROR', 'RECONCILIATION_MISMATCH', 'TOO_MANY_ORDERS'];

/** The 9 labelled reads of one cycle, in order. */
export const CYCLE = [
  'byModeAll',
  'realPnl',
  'byModeRange30',
  'byModeRange366',
  'linesFirst',
  'linesNext',
  'compareAll',
  'compareRange30',
  'compareRange366',
];
/** The reads of the U2 (their gates do not change). */
export const U2_READS = ['byModeAll', 'realPnl'];
/** The 7 new reads of the U3, each with its own gate of 1 s. */
export const NEW_READS = CYCLE.filter((label) => !U2_READS.includes(label));

const DAY_MS = 86_400_000;
const C4_READS = ['byModeAll', 'byModeRange30', 'byModeRange366'];
const C6_READS = ['compareAll', 'compareRange30', 'compareRange366'];

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
  const compare = {};
  for (const cause of CAUSES) {
    real[cause] = series(metrics, 'pnl_mode_block_unavailable_total', { market: 'REAL', cause });
    // U3: the sides of C6 UNAVAILABLE by market and cause (pnl.compare.side_unavailable), reported by cause.
    compare[cause] =
      series(metrics, 'pnl_compare_side_unavailable_total', { market: 'PAPER', cause }) +
      series(metrics, 'pnl_compare_side_unavailable_total', { market: 'REAL', cause });
  }
  return {
    unavailable: real,
    compareUnavailable: compare,
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

/** The value at the quantile q of the measured times by the nearest rank (the 95th smallest of 100 for p95). */
export function nearestRank(values, q) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.max(0, Math.ceil(q * sorted.length) - 1)];
}

/** NFR1.6: the p95 of the times of one label, by the nearest rank. */
export function nearestRankP95(values) {
  return nearestRank(values, 0.95);
}

/** count, p50, p95 and p99 of the times (seconds) of every label. */
export function readStats(times) {
  return Object.fromEntries(
    CYCLE.map((label) => {
      const values = times[label] ?? [];
      return [
        label,
        {
          count: values.length,
          p50: nearestRank(values, 0.5),
          p95: nearestRank(values, 0.95),
          p99: nearestRank(values, 0.99),
        },
      ];
    }),
  );
}

/** [from, to) of the last `days` days ending now, in UTC, in whole seconds (the API accepts up to 366 days). */
export function rangeOf(days, now) {
  const to = Math.floor(now / 1_000) * 1_000;
  const text = (ms) => new Date(ms).toISOString().replace('.000Z', 'Z');
  return { from: text(to - days * DAY_MS), to: text(to) };
}

function query(values) {
  return new URLSearchParams(values).toString();
}

/**
 * The path of a labelled read for a person: `person.targets` holds the REAL bot of C5 and the pair of C6 of the same
 * templateId, `person.cursor` the nextCursor of its last first page. Null when the next page has no cursor to follow.
 */
export function requestOf(label, person, now) {
  const { linesBotId, comparePaperBotId, compareRealBotId } = person.targets ?? {};
  const pair = { paperBotId: comparePaperBotId, realBotId: compareRealBotId };
  switch (label) {
    case 'byModeAll':
      return '/api/v1/pnl/by-mode';
    case 'realPnl':
      return '/api/v1/real/pnl';
    case 'byModeRange30':
      return `/api/v1/pnl/by-mode?${query({ period: 'RANGE', ...rangeOf(30, now) })}`;
    case 'byModeRange366':
      return `/api/v1/pnl/by-mode?${query({ period: 'RANGE', ...rangeOf(366, now) })}`;
    case 'linesFirst':
      return `/api/v1/bots/${linesBotId}/pnl/lines?${query({ limit: '50' })}`;
    case 'linesNext':
      return person.cursor
        ? `/api/v1/bots/${linesBotId}/pnl/lines?${query({ limit: '50', cursor: person.cursor })}`
        : null;
    case 'compareAll':
      return `/api/v1/pnl/compare?${query({ ...pair, period: 'ALL' })}`;
    case 'compareRange30':
      return `/api/v1/pnl/compare?${query({ ...pair, period: 'RANGE', ...rangeOf(30, now) })}`;
    case 'compareRange366':
      return `/api/v1/pnl/compare?${query({ ...pair, period: 'RANGE', ...rangeOf(366, now) })}`;
    default:
      throw new Error(`unknown read ${label}`);
  }
}

/**
 * C5 and C6 of a person: the first REAL bot for the lines and a PAPER bot with a REAL bot of the same templateId for the
 * comparison, from the pages of listBots by market (the seed gives every bot MOMENTUM_VOLUME). Null when one is missing.
 */
export function targetsOf(paperBots, realBots) {
  const linesBot = realBots[0];
  for (const paper of paperBots) {
    const real = realBots.find((bot) => bot.templateId && bot.templateId === paper.templateId);
    if (linesBot && real) {
      return { linesBotId: linesBot.botId, comparePaperBotId: paper.botId, compareRealBotId: real.botId };
    }
  }
  return null;
}

/** UNAVAILABLE blocks or sides of one answer (C4 counts the REAL block only, as the counter of the U2 does). */
function unavailableIn(label, body) {
  if (C4_READS.includes(label)) return body?.real?.status === 'UNAVAILABLE' ? 1 : 0;
  if (C6_READS.includes(label)) {
    return (body?.paper?.status === 'UNAVAILABLE' ? 1 : 0) + (body?.real?.status === 'UNAVAILABLE' ? 1 : 0);
  }
  return 0;
}

function zeroByLabel() {
  return Object.fromEntries(CYCLE.map((label) => [label, 0]));
}

/** D-NFR1 of the U2: its failures, with its limits unchanged; empty means passed. */
export function u2Failures(result) {
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
  // The counter counts every read of C4 (ALL and, since the U3, RANGE): the share is over all of them.
  if (result.unavailable.TIMEOUT > PNL_LIMITS.timeoutShare * (result.readsC4 ?? result.readsByMode)) {
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

/** NFR1.6 of the U3: the 7 new reads, each by its label; empty means passed. */
export function u3Failures(result) {
  const failures = [];
  for (const label of NEW_READS) {
    const stats = result.reads?.[label];
    if (!stats || stats.count === 0) {
      failures.push(`no read of ${label}`);
    } else if (stats.p95 === null || stats.p95 > PNL_LIMITS.readP95Seconds) {
      failures.push(`p95 ${label}`);
    }
    if ((result.non200ByLabel?.[label] ?? 0) > 0) failures.push(`answers other than 200 in ${label}`);
    if ((result.unavailableByLabel?.[label] ?? 0) > 0) failures.push(`UNAVAILABLE in ${label}`);
  }
  if ((result.missingCursor ?? 0) > 0) failures.push('missing nextCursor');
  return failures;
}

/**
 * R-04, the reading rule of the report: each group with its own result. U2 red leaves U2 and U3 pending; only U3 red
 * leaves only the U3 pending; both green leave nothing pending.
 */
export function pnlByModeGroups(result) {
  const u2 = u2Failures(result);
  const u3 = u3Failures(result);
  const pending = u2.length > 0 ? ['U2', 'U3'] : u3.length > 0 ? ['U3'] : [];
  return {
    u2: { passed: u2.length === 0, failures: u2 },
    u3: { passed: u3.length === 0, failures: u3 },
    pending,
  };
}

/** Every failure of one run, the U2 ones first; empty means both groups passed. */
export function pnlByModeFailures(result) {
  return [...u2Failures(result), ...u3Failures(result)];
}

/**
 * The traffic: one reader per person, a read every 2 s through the 9 labelled reads (each reader starts at another
 * point of the cycle), the warm-up not counted. The next page of C5 follows the nextCursor of the last first page of
 * the same person. Returns the counts of the U2 (reads of C4 ALL, of the REAL P&L, answers other than 200 of its two
 * reads, REAL blocks UNAVAILABLE in every C4 answer, the same set as the counter), and, for each label, the times in
 * seconds measured by the client, the answers other than 200 and the UNAVAILABLE blocks or sides.
 */
export async function pnlByModeTraffic({ people, warmupS, durationS, io }) {
  const start = io.now();
  const warmupEnd = start + warmupS * 1_000;
  const end = warmupEnd + durationS * 1_000;
  const counts = {
    readsByMode: 0,
    readsC4: 0,
    readsReal: 0,
    non200: 0,
    unavailableByAnswers: 0,
    times: Object.fromEntries(CYCLE.map((label) => [label, []])),
    non200ByLabel: zeroByLabel(),
    unavailableByLabel: zeroByLabel(),
    missingCursor: 0,
  };
  const reader = async (person, index) => {
    let turn = index % CYCLE.length;
    while (io.now() < end) {
      const label = CYCLE[turn];
      const counted = io.now() >= warmupEnd;
      const route = requestOf(label, person, io.now());
      if (route === null) {
        if (counted) counts.missingCursor++;
      } else {
        const sent = io.now();
        const response = await io.get(route, person);
        const seconds = (io.now() - sent) / 1_000;
        if (label === 'linesFirst' && response.status === 200) person.cursor = response.body?.nextCursor ?? null;
        if (counted) {
          if (response.status !== 200) {
            counts.non200ByLabel[label]++;
            if (U2_READS.includes(label)) counts.non200++;
          } else {
            counts.times[label].push(seconds);
            const unavailable = unavailableIn(label, response.body);
            counts.unavailableByLabel[label] += unavailable;
            if (C4_READS.includes(label)) {
              counts.readsC4++;
              counts.unavailableByAnswers += unavailable;
            }
            if (label === 'byModeAll') counts.readsByMode++;
            if (label === 'realPnl') counts.readsReal++;
          }
        }
      }
      turn = (turn + 1) % CYCLE.length;
      await io.sleep(2_000);
    }
  };
  await Promise.all(people.map((person, index) => reader(person, index)));
  return counts;
}

function seconds(value) {
  return value === null || value === undefined ? 'sem dado' : `${value.toFixed(3)} s`;
}

/** The report of the U3 (docs/perf/u3-pnl-views-<date>.md): the two groups and the times of each read. */
export function pnlViewsMarkdown(report) {
  const groups = report.groups;
  const line = (name, group) =>
    `- Grupo ${name}: **${group.passed ? 'aprovado' : 'reprovado'}**${group.failures.length ? ` (${group.failures.join('; ')})` : ''}`;
  const rows = CYCLE.map((label) => {
    const stats = report.reads[label];
    const gate = NEW_READS.includes(label) ? '1 s' : label === 'byModeAll' ? 'grupo U2 (histograma)' : 'só registro';
    return `| \`${label}\` | ${stats.count} | ${seconds(stats.p50)} | ${seconds(stats.p95)} | ${seconds(stats.p99)} | ${gate} | ${report.non200ByLabel[label]} | ${report.unavailableByLabel[label]} |`;
  });
  return `# Carga — U3 visões do P&L, cenário pnl-by-mode (${report.date})

Gerado por \`scripts/load-test.sh\` (\`LOAD_SCENARIO=pnl-by-mode\`), 261121-paper-real-unico U3 (NFR1.6, D-NFR2).

${line('U2', groups.u2)}
${line('U3', groups.u3)}
- Pendentes: ${groups.pending.length ? groups.pending.join(' e ') : 'nenhuma Unit'} (regra de leitura R-04: grupo U2 vermelho deixa a U2 e a U3 pendentes; só o grupo U3 vermelho deixa só a U3)
- Pessoas simultâneas: ${report.users}; duração medida: ${report.durationSeconds} s, depois de ${report.warmupSeconds} s de aquecimento
- Páginas seguintes de C5 sem cursor para seguir: ${report.missingCursor}
- Lados de C6 \`UNAVAILABLE\` por causa (contador): ${JSON.stringify(report.compareUnavailable)}

Tempo de cada leitura medido no cliente, depois do aquecimento, p95 pelo posto mais próximo:

| Leitura | Leituras | p50 | p95 | p99 | Portão | Respostas diferentes de 200 | \`UNAVAILABLE\` |
|---|---|---|---|---|---|---|---|
${rows.join('\n')}
`;
}
