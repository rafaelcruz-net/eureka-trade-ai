// Pure logic of the load scenario "real" of 261113-trading-real-bot (operation/performance-validation, Q1 to Q5):
// percentiles of client samples, quantiles of Prometheus histograms, the targets of the requirements (never relaxed),
// the bottleneck rules (pool, person lock, TxSigner, signings per wallet and minute, queues, query plans), the
// pass/fail evaluation and the pt-BR report. No I/O here: scripts/load/real.mjs measures, this module judges, and
// scripts/load/load-test-real.test.mjs tests it without a network. Times are numbers (ms or s); money never appears
// here as a number: lamports stay strings or BigInt in scripts/load/real.mjs.

/** Targets of the U1 to U7 requirements measured by the scenario, exactly as written there (Q3: none is relaxed). */
export const REAL_TARGETS = [
  {
    id: 'U1:NFR3.1',
    source: 'u1-bot-wallet/nfr-requirements/performance-requirements.md',
    what: 'Desafio de consentimento e leituras da carteira (bot-wallet, depósitos, saques)',
    metric: 'walletReads',
    stat: 'p95',
    limit: 300,
    unit: 'ms',
  },
  {
    id: 'U1:NFR3.2',
    source: 'u1-bot-wallet/nfr-requirements/performance-requirements.md',
    what: 'Resposta do pedido de saque (POST)',
    metric: 'withdrawalPost',
    stat: 'p95',
    limit: 500,
    unit: 'ms',
  },
  {
    id: 'U1:NFR3.3',
    source: 'u1-bot-wallet/nfr-requirements/performance-requirements.md',
    what: 'Do pedido de saque ao envio para a rede (estado SENT)',
    metric: 'withdrawalToSend',
    stat: 'p95',
    limit: 5000,
    unit: 'ms',
  },
  {
    id: 'U1:NFR3.4',
    source: 'u1-bot-wallet/nfr-requirements/performance-requirements.md',
    what: 'Ativação da carteira do bot, sem o tempo de assinar',
    metric: 'activation',
    stat: 'p95',
    limit: 3000,
    unit: 'ms',
  },
  {
    id: 'U1:NFR3.5',
    source: 'u1-bot-wallet/nfr-requirements/performance-requirements.md',
    what: 'Depósito visível no saldo depois de finalizado (cada depósito)',
    metric: 'depositVisible',
    stat: 'max',
    limit: 45000,
    unit: 'ms',
  },
  {
    id: 'U1:NFR3.8',
    source: 'u1-bot-wallet/nfr-requirements/scalability-requirements.md',
    what: 'Saques de carteiras diferentes não se bloqueiam: o mais lento de cada rajada chega ao envio em até 5 s',
    metric: 'burstWithdrawalToSend',
    stat: 'max',
    limit: 5000,
    unit: 'ms',
  },
  {
    id: 'U3-NFR3.1',
    source: 'u3-real-risk/nfr-requirements/performance-requirements.md',
    what: '`evaluate` de compra, sem a espera pela trava',
    metric: 'riskBuy',
    stat: 'p95',
    limit: 150,
    unit: 'ms',
  },
  {
    id: 'U3-NFR3.2',
    source: 'u3-real-risk/nfr-requirements/performance-requirements.md',
    what: '`evaluate` de venda',
    metric: 'riskSell',
    stat: 'p95',
    limit: 30,
    unit: 'ms',
  },
  {
    id: 'U3-NFR3.3',
    source: 'u3-real-risk/nfr-requirements/performance-requirements.md',
    what: 'Espera pela trava por pessoa (limite duro)',
    metric: 'lockWait',
    stat: 'max',
    limit: 1000,
    unit: 'ms',
  },
  {
    id: 'U3-NFR3.4',
    source: 'u3-real-risk/nfr-requirements/performance-requirements.md',
    what: 'Trabalhos F3, F4 e F5 com 21 carteiras, por execução',
    metric: 'riskJobs',
    stat: 'max',
    limit: 5000,
    unit: 'ms',
  },
  {
    id: 'U4-NFR3.1',
    source: 'u4-real-execution/nfr-requirements/performance-requirements.md',
    what: 'Da gravação da decisão ao envio (AUTO_FULL)',
    metric: 'decisionToSend',
    stat: 'p95',
    limit: 3000,
    unit: 'ms',
  },
  {
    id: 'U4-NFR3.2',
    source: 'u4-real-execution/nfr-requirements/performance-requirements.md',
    what: 'Da gravação da decisão ao início do consumo',
    metric: 'decisionQueueWait',
    stat: 'p95',
    limit: 500,
    unit: 'ms',
  },
  {
    id: 'U4-NFR3.4',
    source: 'u4-real-execution/nfr-requirements/performance-requirements.md',
    what: '`GET` de ordens e `GET /pnl`',
    metric: 'ordersAndPnl',
    stat: 'p95',
    limit: 300,
    unit: 'ms',
  },
  {
    id: 'U5-NFR3.1',
    source: 'u5-real-operations/nfr-requirements/performance-requirements.md',
    what: 'Reconciliação de uma carteira',
    metric: 'reconciliationWallet',
    stat: 'p95',
    limit: 5000,
    unit: 'ms',
  },
  {
    id: 'U5-NFR3.2',
    source: 'u5-real-operations/nfr-requirements/performance-requirements.md',
    what: 'Rodada de reconciliação das 21 carteiras',
    metric: 'reconciliationRound',
    stat: 'max',
    limit: 60000,
    unit: 'ms',
  },
  {
    id: 'U5-NFR3.3',
    source: 'u5-real-operations/nfr-requirements/performance-requirements.md',
    what: 'Trabalho de portões, por execução',
    metric: 'gatesJob',
    stat: 'max',
    limit: 10000,
    unit: 'ms',
  },
  {
    id: 'U5-NFR3.4',
    source: 'u5-real-operations/nfr-requirements/performance-requirements.md',
    what: '`GET /api/v1/admin/real/users` e `/users/{userId}`',
    metric: 'adminRoutes',
    stat: 'p95',
    limit: 500,
    unit: 'ms',
  },
  {
    id: 'CICLO-WORKER',
    source: '261026-memetrade-saas-mvp U5 NFR2.1 (ciclo do bot), citado em [Q3]',
    what: 'Ciclo do bot no worker (cronômetro `loadtest.bots.cycle`)',
    metric: 'botCycle',
    stat: 'p95',
    limit: 2000,
    unit: 'ms',
  },
];

/** Most signatures one bot wallet may get in one minute (TxSigner limit per wallet, Q4). */
export const SIGNINGS_PER_WALLET_MINUTE_LIMIT = 60;

/** Nearest-rank percentile of samples (q in 0..1); null without samples. */
export function percentile(values, q) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.max(0, Math.ceil(sorted.length * q) - 1))];
}

/** n, p50, p95, p99 and max of samples; the percentiles are null without samples. */
export function summarize(values) {
  return {
    n: values.length,
    p50: percentile(values, 0.5),
    p95: percentile(values, 0.95),
    p99: percentile(values, 0.99),
    max: values.length === 0 ? null : Math.max(...values),
  };
}

/** Labels of one Prometheus sample line: `{a="1",b="2"}` to { a: '1', b: '2' }. */
export function labelsOf(text) {
  const labels = {};
  for (const match of text.matchAll(/(\w+)="((?:[^"\\]|\\.)*)"/g)) labels[match[1]] = match[2];
  return labels;
}

/** Every sample of one metric name: [{ labels, value }]. Comments and other names are skipped. */
export function samplesOf(text, name) {
  const out = [];
  for (const line of text.split('\n')) {
    if (!line.startsWith(name)) continue;
    const match = /^([a-zA-Z_:][\w:]*)(\{[^}]*\})?\s+(\S+)/.exec(line);
    if (!match || match[1] !== name) continue;
    out.push({ labels: labelsOf(match[2] ?? ''), value: Number(match[3]) });
  }
  return out;
}

function matches(labels, filter) {
  return Object.entries(filter).every(([key, value]) => labels[key] === value);
}

/** Sum of the samples of `name` whose labels contain `filter`; 0 when none. */
export function sumOf(text, name, filter = {}) {
  return samplesOf(text, name)
    .filter((sample) => matches(sample.labels, filter))
    .reduce((total, sample) => total + sample.value, 0);
}

/** Highest sample of `name` whose labels contain `filter`; null when none. */
export function maxOf(text, name, filter = {}) {
  const values = samplesOf(text, name)
    .filter((sample) => matches(sample.labels, filter))
    .map((sample) => sample.value);
  return values.length === 0 ? null : Math.max(...values);
}

/**
 * The cumulative buckets of the histogram `base` (`<base>_bucket`) whose labels contain `filter`, summed over the other
 * labels: a sorted array of [upperBoundSeconds, cumulativeCount], +Inf last.
 */
export function histogramOf(text, base, filter = {}) {
  const buckets = new Map();
  for (const sample of samplesOf(text, `${base}_bucket`)) {
    if (!matches(sample.labels, filter)) continue;
    const bound = sample.labels.le === '+Inf' ? Infinity : Number(sample.labels.le);
    buckets.set(bound, (buckets.get(bound) ?? 0) + sample.value);
  }
  return [...buckets.entries()].sort((a, b) => a[0] - b[0]);
}

/** Bucket counts of a window: `after` minus `before` (same bounds; a bound missing before counts as 0). */
export function histogramDelta(after, before) {
  const earlier = new Map(before);
  return after.map(([bound, count]) => [bound, count - (earlier.get(bound) ?? 0)]);
}

/**
 * Quantile of a cumulative histogram as the upper bound of the first bucket that holds the rank: never below the true
 * value, so a pass is never a rounding artefact. Infinity when the rank falls in +Inf; null without observations.
 */
export function histogramQuantile(buckets, q) {
  const total = buckets.at(-1)?.[1] ?? 0;
  if (total <= 0) return null;
  const rank = Math.ceil(total * q);
  return buckets.find(([, count]) => count >= rank)?.[0] ?? Infinity;
}

/** n, p50, p95, p99 in ms of a histogram in seconds; `maxSeconds` (polled `_max`) becomes max in ms. */
export function summarizeHistogram(buckets, maxSeconds = null) {
  const ms = (seconds) => (seconds === null ? null : seconds * 1000);
  return {
    n: buckets.at(-1)?.[1] ?? 0,
    p50: ms(histogramQuantile(buckets, 0.5)),
    p95: ms(histogramQuantile(buckets, 0.95)),
    p99: ms(histogramQuantile(buckets, 0.99)),
    max: ms(maxSeconds),
  };
}

/** Milliseconds of a `_max` gauge read over time (seconds); the highest reading wins. */
export function highestMs(readingsSeconds) {
  const seen = readingsSeconds.filter((value) => typeof value === 'number' && Number.isFinite(value));
  return seen.length === 0 ? null : Math.max(...seen) * 1000;
}

/**
 * Lines of `psql -At -F '|'`: rows of the signed transactions per wallet and minute (`wallet|minute|count`). Returns the
 * highest count and how many wallets signed at all. The wallet column is never kept (no address in the report).
 */
export function signingsPerWalletMinute(psqlOutput) {
  let max = 0;
  const wallets = new Set();
  for (const line of psqlOutput.split('\n')) {
    const [wallet, , count] = line.trim().split('|');
    if (!wallet || count === undefined) continue;
    wallets.add(wallet);
    max = Math.max(max, Number(count));
  }
  return { max, wallets: wallets.size, limit: SIGNINGS_PER_WALLET_MINUTE_LIMIT };
}

/**
 * A queue grows when, inside the window, its smallest depth in the last third is above its largest depth in the first
 * third (it never came back down to where it started), or when after the drain it ends above where it started.
 * `samples` = [{ atMs, depth }] in time order; `window` = { fromMs, toMs }; `start` and `end` the depths before the
 * load and after the drain.
 */
export function queueGrowing(samples, window, start, end) {
  const inside = samples.filter((s) => s.atMs >= window.fromMs && s.atMs <= window.toMs);
  const third = Math.floor(inside.length / 3);
  const trend =
    third > 0 &&
    Math.min(...inside.slice(-third).map((s) => s.depth)) > Math.max(...inside.slice(0, third).map((s) => s.depth));
  const notDrained = end !== null && start !== null && end > start;
  return { growing: trend || notDrained, trend, notDrained };
}

/**
 * The phases of Q2 from the start of the load (ms): steady, then the sell-all peak, then the withdrawal burst, with the
 * instants of each burst (one every `burstEveryS`, spread over `burstSpreadS`).
 */
export function timeline({ steadyS, peakS, burstS, burstEveryS }) {
  const steady = { fromMs: 0, toMs: steadyS * 1000 };
  const peak = { fromMs: steady.toMs, toMs: steady.toMs + peakS * 1000 };
  const burst = { fromMs: peak.toMs, toMs: peak.toMs + burstS * 1000 };
  const bursts = [];
  for (let at = burst.fromMs; at < burst.toMs; at += burstEveryS * 1000) bursts.push(at);
  return { steady, peak, burst, bursts, endMs: burst.toMs };
}

/** The phase of an instant (ms since the start of the load): steady, peak, burst or drain. */
export function phaseAt(phases, atMs) {
  if (atMs < phases.steady.toMs) return 'steady';
  if (atMs < phases.peak.toMs) return 'peak';
  if (atMs < phases.burst.toMs) return 'burst';
  return 'drain';
}

/**
 * Offsets (ms) of the `count` requests of one burst, spread evenly over `spreadMs`, in an order shuffled by `random`
 * (a seeded generator in the tests): [{ index, offsetMs }].
 */
export function burstOffsets(count, spreadMs, random) {
  const order = Array.from({ length: count }, (_, index) => index);
  for (let i = order.length - 1; i > 0; i -= 1) {
    const j = Math.floor(random() * (i + 1));
    [order[i], order[j]] = [order[j], order[i]];
  }
  const step = count > 1 ? spreadMs / (count - 1) : 0;
  return order.map((index, position) => ({ index, offsetMs: Math.round(position * step) }));
}

/** A small deterministic generator (mulberry32) for the burst order: the same seed gives the same run. */
export function seededRandom(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Whether an EXPLAIN text reads `table` with a sequential scan (the plan with enable_seqscan off must not). */
export function seqScanOn(plan, table) {
  return new RegExp(`Seq Scan on ${table}\\b`).test(plan);
}

/**
 * Pass or fail of the whole run. `report.measured[metric]` = summary ({ n, p50, p95, p99, max }); every target needs
 * at least one sample and its statistic within the limit. Then zero errors, no duplicate transaction, no growing
 * queue, at most 60 signatures per wallet and minute, no sustained pool exhaustion and an index path for every hot
 * query. Returns { targets, failures, passed }.
 */
export function evaluateReal(report) {
  const targets = REAL_TARGETS.map((target) => {
    const measured = report.measured[target.metric] ?? summarize([]);
    const value = measured[target.stat];
    const passed = measured.n > 0 && value !== null && value <= target.limit;
    return { ...target, measured, value, passed };
  });
  const failures = targets
    .filter((target) => !target.passed)
    .map((target) =>
      target.measured.n === 0 ? `${target.id}: sem amostra` : `${target.id}: ${target.stat} acima de ${target.limit} ${target.unit}`,
    );
  const errors = report.errors ?? {};
  for (const [name, count] of Object.entries(errors)) {
    if (count > 0) failures.push(`erro ${name}: ${count}`);
  }
  for (const [name, count] of Object.entries(report.duplicates ?? {})) {
    if (count > 0) failures.push(`duplicidade ${name}: ${count}`);
  }
  for (const [name, queue] of Object.entries(report.queues ?? {})) {
    if (queue.growing) failures.push(`fila crescendo: ${name}`);
  }
  const signings = report.signings ?? { max: 0, limit: SIGNINGS_PER_WALLET_MINUTE_LIMIT };
  if (signings.max > signings.limit) failures.push(`assinaturas por carteira e minuto: ${signings.max} > ${signings.limit}`);
  for (const [name, pool] of Object.entries(report.pools ?? {})) {
    if (pool.exhausted) failures.push(`pool esgotado: ${name}`);
  }
  for (const plan of report.plans ?? []) {
    if (!plan.indexPath) failures.push(`plano sem índice: ${plan.name}`);
  }
  for (const missing of report.setupFailures ?? []) failures.push(missing);
  return { targets, failures, passed: failures.length === 0 };
}

/**
 * A pool is exhausted when it had connections waiting in two readings in a row (10 s apart): a queue for connections
 * that did not clear. Using every connection without anyone waiting is reported (`full`), not failed.
 * `readings` = [{ active, pending, max }].
 */
export function poolSummary(readings) {
  let maxActive = 0;
  let maxPending = 0;
  let size = null;
  let sustained = false;
  let previousPending = 0;
  for (const reading of readings) {
    maxActive = Math.max(maxActive, reading.active);
    maxPending = Math.max(maxPending, reading.pending);
    size = reading.max ?? size;
    if (reading.pending > 0 && previousPending > 0) sustained = true;
    previousPending = reading.pending;
  }
  const full = size !== null && size > 0 && maxActive >= size;
  return { readings: readings.length, maxActive, maxPending, size, full, exhausted: sustained };
}

function cell(value, unit) {
  if (value === null || value === undefined) return 'sem amostra';
  if (!Number.isFinite(value)) return `acima do maior bucket`;
  return unit === 'ms' ? `${Math.round(value)} ms` : `${value} ${unit}`;
}

/** The report of docs/perf/real-trading-<date>.md (pt-BR). */
export function realMarkdown(report) {
  const rows = report.targets
    .map(
      (t) =>
        `| ${t.id} | ${t.what} | ${t.measured.n} | ${cell(t.measured.p50, 'ms')} | ${cell(t.measured.p95, 'ms')} | ` +
        `${cell(t.measured.p99, 'ms')} | ${cell(t.measured.max, 'ms')} | ${t.stat} ≤ ${t.limit} ${t.unit} | ` +
        `${t.passed ? 'atingida' : '**não atingida**'} |`,
    )
    .join('\n');
  const pools = Object.entries(report.pools ?? {})
    .map(([name, p]) => `| ${name} | ${p.maxActive} | ${p.size ?? 'sem leitura'} | ${p.maxPending} | ${p.exhausted ? 'sim' : 'não'} |`)
    .join('\n');
  const queues = Object.entries(report.queues ?? {})
    .map(([name, q]) => `| ${name} | ${q.start ?? '-'} | ${q.max ?? '-'} | ${q.end ?? '-'} | ${q.growing ? '**sim**' : 'não'} |`)
    .join('\n');
  const signers = Object.entries(report.txsigner ?? {})
    .map(([kind, s]) => `| ${kind} | ${s.n} | ${cell(s.p50, 'ms')} | ${cell(s.p95, 'ms')} | ${cell(s.p99, 'ms')} | ${cell(s.max, 'ms')} |`)
    .join('\n');
  const plans = (report.plans ?? [])
    .map((p) => `| ${p.name} | \`${p.table}\` | ${p.indexPath ? 'sim' : '**não**'} | ${p.firstLine.replace(/\|/g, '\\|')} |`)
    .join('\n');
  const errors = Object.entries(report.errors ?? {})
    .map(([name, count]) => `| ${name} | ${count} |`)
    .join('\n');
  const duplicates = Object.entries(report.duplicates ?? {})
    .map(([name, count]) => `| ${name} | ${count} |`)
    .join('\n');
  return `# Carga do REAL — piloto x2 (${report.date})

Gerado por \`scripts/load-test.sh\` com \`LOAD_SCENARIO=real\` (perfis \`test,loadtest\`, Docker Compose com o validador
Solana local, o \`kms-sim\` e a Jupiter simulada). **Ambiente de CI, não produção** [Q1].

- Pessoas: ${report.config.people} carteiras do bot, ${report.config.botsPerPerson} bots REAL por carteira, ${report.config.screenUsers} pessoas na tela (uma ação a cada 2 s) e um administrador
- Fases [Q2]: estável ${report.config.steadyS} s, pico de "vender tudo e sacar" em ${report.config.peakWallets} carteiras por ${report.config.peakS} s, rajada de ${report.config.people} saques em ${report.config.burstSpreadS} s a cada ${report.config.burstEveryS} s por ${report.config.burstS} s
- Resultado: **${report.passed ? 'aprovado' : 'reprovado'}**${report.failures.length ? ` (${report.failures.join('; ')})` : ''}

## Metas

| Meta | O que mede | Amostras | p50 | p95 | p99 | Máximo | Limite | Situação |
|---|---|---|---|---|---|---|---|---|
${rows}

## Gargalos [Q4]

### Pools de conexão

| Pool | Ativas (máx.) | Tamanho | Esperando (máx.) | Esgotado |
|---|---|---|---|---|
${pools}

### TxSigner

| Tipo | Assinaturas | p50 | p95 | p99 | Máximo |
|---|---|---|---|---|---|
${signers}

- Assinaturas por carteira e minuto: máximo ${report.signings.max} (limite ${report.signings.limit}), em ${report.signings.wallets} carteiras

### Filas

| Fila | Início | Máximo | Fim (depois do escoamento) | Crescendo |
|---|---|---|---|---|
${queues}

### Planos das consultas quentes (EXPLAIN)

| Consulta | Tabela | Caminho por índice | Primeira linha do plano natural |
|---|---|---|---|
${plans}

## Erros

| Origem | Quantidade |
|---|---|
${errors}

## Duplicidades

| Verificação | Quantidade |
|---|---|
${duplicates}
`;
}
