// Test of the load scenario "real" of 261113-trading-real-bot (operation/performance-validation). Run with:
//   node --test scripts/load/load-test-real.test.mjs
// No network, no database and no Docker: the fetch, the clock, the sleep, the RPC, the SQL and the Prometheus texts are
// doubles; synthetic values only (wallets generated in this process, never a real one).
import assert from 'node:assert/strict';
import { createPublicKey, generateKeyPairSync, sign, verify } from 'node:crypto';
import { test } from 'node:test';
import { base58, newWallet } from './data-requests.mjs';
import {
  activate,
  administrator,
  assertLocal,
  burst,
  call,
  createBots,
  databaseChecks,
  decodeBase58,
  deposit,
  hotQueries,
  liquidate,
  newSamples,
  parseQueues,
  plans,
  QUEUE_NAMES,
  rawPublicKey,
  realAccessSql,
  runReal,
  sampler,
  screenUser,
  SCREEN_ACTIONS,
  serverWindow,
  trackWithdrawal,
  transferMessage,
} from './real.mjs';
import {
  burstOffsets,
  evaluateReal,
  highestMs,
  histogramDelta,
  histogramOf,
  histogramQuantile,
  labelsOf,
  maxOf,
  percentile,
  phaseAt,
  poolSummary,
  queueGrowing,
  REAL_TARGETS,
  realMarkdown,
  samplesOf,
  seededRandom,
  seqScanOn,
  SIGNINGS_PER_WALLET_MINUTE_LIMIT,
  signingsPerWalletMinute,
  sumOf,
  summarize,
  summarizeHistogram,
  timeline,
} from './real-stats.mjs';

// ----- doubles -----

/**
 * A discrete-event clock shared by the fetch and the sleep: each sleep wakes at its own instant, the earliest first,
 * and a call takes the `ms` of its answer, so concurrent people do not add up their waits. `routes(method, path, body,
 * headers)` answers each call.
 */
function fakeIo(routes, extra = {}) {
  const waiting = [];
  let ticking = false;
  let order = 0;
  const tick = () => {
    waiting.sort((a, b) => a.at - b.at || a.order - b.order);
    const next = waiting.shift();
    io.t = Math.max(io.t, next.at);
    next.resolve();
    if (waiting.length > 0) setImmediate(tick);
    else ticking = false;
  };
  const io = {
    t: 0,
    calls: [],
    apiBase: 'http://127.0.0.1:8080',
    rpcBase: 'http://127.0.0.1:8899',
    now: () => io.t,
    wallNow: () => Date.UTC(2026, 10, 20) + io.t,
    sleep: (ms) =>
      new Promise((resolve) => {
        waiting.push({ at: io.t + Math.max(0, ms), order: order++, resolve });
        if (!ticking) {
          ticking = true;
          setImmediate(tick);
        }
      }),
    fetch: async (url, init) => {
      const path = url.slice(io.apiBase.length);
      const body = init.body === undefined ? undefined : JSON.parse(init.body);
      io.calls.push({ method: init.method, path, body, headers: init.headers });
      const answer = await routes(init.method, path, body, init.headers);
      await io.sleep(answer.ms ?? 10);
      return { status: answer.status, elapsedMs: answer.ms ?? 10, json: async () => answer.body ?? {} };
    },
    warn: () => {},
    ...extra,
  };
  return io;
}

const histogramText = (name, buckets, labels = '') =>
  buckets
    .map(([le, count]) => `${name}_bucket{${labels}${labels ? ',' : ''}le="${le}"} ${count}`)
    .concat(`${name}_count{${labels}} ${buckets.at(-1)[1]}`)
    .join('\n');

// ----- real-stats.mjs: percentiles and Prometheus -----

test('percentile is the nearest rank and summarize gives n, p50, p95, p99 and max', () => {
  const values = Array.from({ length: 100 }, (_, i) => i + 1);
  assert.equal(percentile(values, 0.5), 50);
  assert.equal(percentile(values, 0.95), 95);
  assert.equal(percentile(values, 0.99), 99);
  assert.equal(percentile([], 0.95), null);
  assert.equal(percentile([7], 0.95), 7);
  assert.deepEqual(summarize([30, 10, 20]), { n: 3, p50: 20, p95: 30, p99: 30, max: 30 });
  assert.deepEqual(summarize([]), { n: 0, p50: null, p95: null, p99: null, max: null });
});

test('Prometheus samples are read by name and labels, never by a longer name', () => {
  const text = [
    '# HELP x',
    'hikaricp_connections_active{pool="memetrade-pool"} 3.0',
    'hikaricp_connections_active{pool="memetrade-worker-pool"} 9.0',
    'hikaricp_connections_active_total{pool="memetrade-pool"} 99.0',
    'real_ops_job_seconds_max{job="gates"} 0.4',
    'real_ops_job_seconds_max{job="reconciliation"} 12.5',
  ].join('\n');
  assert.deepEqual(labelsOf('{a="1",b="x y"}'), { a: '1', b: 'x y' });
  assert.equal(samplesOf(text, 'hikaricp_connections_active').length, 2);
  assert.equal(sumOf(text, 'hikaricp_connections_active'), 12);
  assert.equal(sumOf(text, 'hikaricp_connections_active', { pool: 'memetrade-pool' }), 3);
  assert.equal(sumOf(text, 'missing_metric'), 0);
  assert.equal(maxOf(text, 'real_ops_job_seconds_max'), 12.5);
  assert.equal(maxOf(text, 'real_ops_job_seconds_max', { job: 'gates' }), 0.4);
  assert.equal(maxOf(text, 'real_ops_job_seconds_max', { job: 'none' }), null);
});

test('histograms are summed over labels, windowed by delta and their quantile never undershoots', () => {
  const text = [
    histogramText('real_risk_evaluation_seconds', [['0.05', 10], ['0.15', 18], ['+Inf', 20]], 'side="BUY",process="a"'),
    histogramText('real_risk_evaluation_seconds', [['0.05', 0], ['0.15', 0], ['+Inf', 0]], 'side="BUY",process="b"'),
    histogramText('real_risk_evaluation_seconds', [['0.05', 5], ['0.15', 5], ['+Inf', 5]], 'side="SELL"'),
  ].join('\n');
  const buy = histogramOf(text, 'real_risk_evaluation_seconds', { side: 'BUY' });
  assert.deepEqual(buy, [
    [0.05, 10],
    [0.15, 18],
    [Infinity, 20],
  ]);
  assert.equal(histogramQuantile(buy, 0.5), 0.05);
  assert.equal(histogramQuantile(buy, 0.9), 0.15);
  // The p95 rank (19 of 20) is in +Inf: never reported as a pass.
  assert.equal(histogramQuantile(buy, 0.95), Infinity);
  assert.equal(histogramQuantile([], 0.95), null);
  assert.equal(histogramQuantile([[1, 0], [Infinity, 0]], 0.95), null);
  const before = [
    [0.05, 4],
    [0.15, 4],
  ];
  assert.deepEqual(histogramDelta([[0.05, 10], [0.15, 18], [0.5, 2]], before), [
    [0.05, 6],
    [0.15, 14],
    [0.5, 2],
  ]);
  assert.deepEqual(summarizeHistogram(buy, 0.4), { n: 20, p50: 50, p95: Infinity, p99: Infinity, max: 400 });
  assert.equal(highestMs([null, 0.2, undefined, 1.5, Number.NaN]), 1500);
  assert.equal(highestMs([null]), null);
});

test('serverWindow merges the api and the worker and subtracts what was there before the load', () => {
  const before = [histogramText('real_reconciliation_seconds', [['5.0', 2], ['+Inf', 2]]), ''];
  const after = [
    histogramText('real_reconciliation_seconds', [['5.0', 10], ['+Inf', 11]]),
    histogramText('real_reconciliation_seconds', [['5.0', 3], ['+Inf', 3]]),
  ];
  assert.deepEqual(serverWindow(before, after, 'real_reconciliation_seconds'), [
    [5, 11],
    [Infinity, 12],
  ]);
});

// ----- real-stats.mjs: bottleneck rules -----

test('signatures per wallet and minute keep the highest count and never the address', () => {
  const output = ['walletA|2026-11-20 10:00:00+00|12', 'walletA|2026-11-20 10:01:00+00|61', 'walletB|2026-11-20 10:00:00+00|3', ''].join('\n');
  assert.deepEqual(signingsPerWalletMinute(output), { max: 61, wallets: 2, limit: 60 });
  assert.deepEqual(signingsPerWalletMinute(''), { max: 0, wallets: 0, limit: SIGNINGS_PER_WALLET_MINUTE_LIMIT });
});

test('a queue grows when its last third never comes down to its first third, or when it does not drain', () => {
  const window = { fromMs: 0, toMs: 900_000 };
  const series = (depths) => depths.map((depth, i) => ({ atMs: i * 10_000, depth }));
  assert.deepEqual(queueGrowing(series([0, 1, 0, 2, 0, 1, 0, 1, 0]), window, 0, 0), {
    growing: false,
    trend: false,
    notDrained: false,
  });
  assert.equal(queueGrowing(series([0, 1, 1, 3, 4, 5, 6, 7, 8]), window, 0, 0).trend, true);
  assert.equal(queueGrowing(series([0, 0, 0]), window, 0, 4).notDrained, true);
  assert.equal(queueGrowing(series([0, 9]), window, 0, 0).growing, false);
  assert.equal(queueGrowing([], window, null, null).growing, false);
  // Samples outside the window (the bursts after the steady state) do not count for the trend.
  const withBurst = [...series([0, 1, 0, 1, 0, 1]), { atMs: 950_000, depth: 21 }];
  assert.equal(queueGrowing(withBurst, window, 0, 0).growing, false);
});

test('a pool fails only with connections waiting in two readings in a row; using all of it is reported', () => {
  assert.deepEqual(poolSummary([]), { readings: 0, maxActive: 0, maxPending: 0, size: null, full: false, exhausted: false });
  const once = poolSummary([
    { active: 5, pending: 0, max: 36 },
    { active: 36, pending: 2, max: 36 },
    { active: 10, pending: 0, max: 36 },
  ]);
  assert.equal(once.exhausted, false);
  assert.equal(once.full, true);
  assert.equal(once.maxPending, 2);
  const sustained = poolSummary([
    { active: 20, pending: 1, max: 20 },
    { active: 20, pending: 3, max: 20 },
  ]);
  assert.equal(sustained.exhausted, true);
});

test('the timeline of Q2 has 15, 5 and 5 minutes and five bursts one minute apart', () => {
  const phases = timeline({ steadyS: 900, peakS: 300, burstS: 300, burstEveryS: 60 });
  assert.deepEqual(phases.steady, { fromMs: 0, toMs: 900_000 });
  assert.deepEqual(phases.peak, { fromMs: 900_000, toMs: 1_200_000 });
  assert.deepEqual(phases.burst, { fromMs: 1_200_000, toMs: 1_500_000 });
  assert.deepEqual(phases.bursts, [1_200_000, 1_260_000, 1_320_000, 1_380_000, 1_440_000]);
  assert.equal(phases.endMs, 1_500_000);
  assert.equal(phaseAt(phases, 0), 'steady');
  assert.equal(phaseAt(phases, 900_000), 'peak');
  assert.equal(phaseAt(phases, 1_499_999), 'burst');
  assert.equal(phaseAt(phases, 1_500_000), 'drain');
});

test('a burst spreads 21 requests over 10 s, each wallet once, in the same order for the same seed', () => {
  const offsets = burstOffsets(21, 10_000, seededRandom(261113));
  assert.equal(offsets.length, 21);
  assert.deepEqual(
    [...offsets.map((o) => o.index)].sort((a, b) => a - b),
    Array.from({ length: 21 }, (_, i) => i),
  );
  assert.equal(offsets[0].offsetMs, 0);
  assert.equal(offsets.at(-1).offsetMs, 10_000);
  assert.deepEqual(burstOffsets(21, 10_000, seededRandom(261113)), offsets);
  assert.notDeepEqual(burstOffsets(21, 10_000, seededRandom(7)).map((o) => o.index), offsets.map((o) => o.index));
  assert.deepEqual(burstOffsets(1, 10_000, seededRandom(1)), [{ index: 0, offsetMs: 0 }]);
  const random = seededRandom(5);
  for (let i = 0; i < 100; i += 1) {
    const value = random();
    assert.ok(value >= 0 && value < 1);
  }
});

test('a plan reads a table with a sequential scan only when it says so', () => {
  assert.equal(seqScanOn('Limit\n  ->  Seq Scan on withdrawal  (cost=0.00..1.01)', 'withdrawal'), true);
  assert.equal(seqScanOn('Index Scan using withdrawal_wallet_idx on withdrawal', 'withdrawal'), false);
  assert.equal(seqScanOn('Seq Scan on withdrawal_archive', 'withdrawal'), false);
});

// ----- real-stats.mjs: evaluation and report -----

function passingReport() {
  const measured = {};
  for (const target of REAL_TARGETS) {
    measured[target.metric] = { n: 30, p50: target.limit / 4, p95: target.limit / 2, p99: target.limit, max: target.limit };
  }
  return {
    date: '2026-11-20',
    config: {
      people: 21,
      botsPerPerson: 3,
      screenUsers: 20,
      steadyS: 900,
      peakWallets: 5,
      peakS: 300,
      burstSpreadS: 10,
      burstEveryS: 60,
      burstS: 300,
    },
    measured,
    errors: { respostasInesperadas: 0, ordensFalhas: 0 },
    duplicates: { assinaturasRepetidas: 0 },
    queues: { decisoes_pendentes: { start: 0, max: 2, end: 0, growing: false } },
    signings: { max: 4, wallets: 21, limit: 60 },
    pools: { worker: { maxActive: 12, size: 36, maxPending: 0, exhausted: false } },
    txsigner: { WITHDRAWAL: { n: 105, p50: 40, p95: 90, p99: 120, max: 130 } },
    plans: [{ name: 'saques prontos (worker)', table: 'withdrawal', indexPath: true, firstLine: 'Limit  (cost=0..1)' }],
    setupFailures: [],
  };
}

test('every target exactly at its limit passes; one above it or without samples fails', () => {
  const report = passingReport();
  const result = evaluateReal(report);
  assert.deepEqual(result.failures, []);
  assert.equal(result.passed, true);
  assert.equal(result.targets.length, REAL_TARGETS.length);

  report.measured.walletReads = { n: 400, p50: 80, p95: 301, p99: 400, max: 500 };
  report.measured.reconciliationRound = { n: 0, p50: null, p95: null, p99: null, max: null };
  delete report.measured.gatesJob;
  const failed = evaluateReal(report);
  assert.equal(failed.passed, false);
  assert.ok(failed.failures.includes('U1:NFR3.1: p95 acima de 300 ms'));
  assert.ok(failed.failures.includes('U5-NFR3.2: sem amostra'));
  assert.ok(failed.failures.includes('U5-NFR3.3: sem amostra'));
  // A quantile in +Inf is above any limit.
  report.measured.walletReads.p95 = Infinity;
  assert.ok(evaluateReal(report).failures.includes('U1:NFR3.1: p95 acima de 300 ms'));
});

test('errors, duplicates, growing queues, 61 signatures, a waiting pool, a plan without index and the setup fail the run', () => {
  const report = passingReport();
  report.errors.ordensFalhas = 1;
  report.duplicates.assinaturasRepetidas = 2;
  report.queues.decisoes_pendentes.growing = true;
  report.signings.max = 61;
  report.pools.worker.exhausted = true;
  report.plans[0].indexPath = false;
  report.setupFailures = ['a carteira do bot do fundador não é a do swap gravado'];
  const { failures, passed } = evaluateReal(report);
  assert.equal(passed, false);
  assert.deepEqual(failures, [
    'erro ordensFalhas: 1',
    'duplicidade assinaturasRepetidas: 2',
    'fila crescendo: decisoes_pendentes',
    'assinaturas por carteira e minuto: 61 > 60',
    'pool esgotado: worker',
    'plano sem índice: saques prontos (worker)',
    'a carteira do bot do fundador não é a do swap gravado',
  ]);
  // Exactly 60 signatures in a minute is within the limit.
  const atLimit = passingReport();
  atLimit.signings.max = 60;
  assert.equal(evaluateReal(atLimit).passed, true);
  assert.equal(evaluateReal({ measured: {} }).passed, false);
});

test('the pt-BR report lists every target with p50, p95, p99 and the bottlenecks', () => {
  const report = passingReport();
  const result = { ...report, ...evaluateReal(report) };
  result.targets[0].measured = { n: 0, p50: null, p95: null, p99: null, max: null };
  result.targets[1].measured = { ...result.targets[1].measured, p95: Infinity };
  result.plans[0].firstLine = 'Limit | x';
  const text = realMarkdown(result);
  assert.match(text, /# Carga do REAL — piloto x2 \(2026-11-20\)/);
  assert.match(text, /Ambiente de CI, não produção/);
  assert.match(text, /\*\*aprovado\*\*/);
  for (const target of REAL_TARGETS) assert.ok(text.includes(`| ${target.id} |`), target.id);
  assert.match(text, /sem amostra/);
  assert.match(text, /acima do maior bucket/);
  assert.match(text, /máximo 4 \(limite 60\), em 21 carteiras/);
  assert.match(text, /Limit \\\| x/);
  const failed = { ...report, ...evaluateReal({ ...report, errors: { ordensFalhas: 2 } }) };
  assert.match(realMarkdown(failed), /\*\*reprovado\*\* \(erro ordensFalhas: 2\)/);
});

// ----- real.mjs: helpers -----

test('only loopback targets are accepted', () => {
  assertLocal('http://127.0.0.1:8080', 'http://localhost:8899');
  assert.throws(() => assertLocal('http://127.0.0.1:8080', 'https://api.devnet.solana.com'), /only targets localhost/);
});

test('base58 decodes what it encodes, with leading zeros, and refuses other characters', () => {
  const bytes = Buffer.from([0, 0, 7, 255, 1, 2, 3]);
  assert.deepEqual(decodeBase58(base58(bytes)), bytes);
  assert.deepEqual(decodeBase58('1'), Buffer.from([0]));
  assert.throws(() => decodeBase58('0OIl'), /not base58/);
});

test('the test transfer message is a legacy System transfer of exact lamports and its signature verifies', () => {
  const from = newWallet();
  const to = newWallet().address;
  const blockhash = base58(Buffer.alloc(32, 9));
  const message = transferMessage(rawPublicKey(from.key), to, blockhash, 500_000_000n);
  assert.equal(message.length, 4 + 32 * 3 + 32 + 6 + 4 + 8);
  assert.deepEqual([...message.subarray(0, 4)], [1, 0, 1, 3]);
  assert.equal(base58(message.subarray(4, 36)), from.address);
  assert.equal(base58(message.subarray(36, 68)), to);
  assert.equal(message.readBigUInt64LE(message.length - 8), 500_000_000n);
  const { publicKey, privateKey } = generateKeyPairSync('ed25519');
  const signature = sign(null, message, privateKey);
  assert.equal(verify(null, message, publicKey, signature), true);
  assert.throws(() => transferMessage(rawPublicKey(from.key), to, blockhash, 5), /positive BigInt/);
  assert.throws(() => transferMessage(rawPublicKey(from.key), to, blockhash, 0n), /positive BigInt/);
});

test('queue depths come in the order of the query and a missing value is null', () => {
  assert.deepEqual(parseQueues('1|0|3|2\n'), {
    decisoes_pendentes: 1,
    saques_em_andamento: 0,
    ordens_em_andamento: 3,
    comandos_txsigner: 2,
  });
  assert.equal(parseQueues('1|x')[QUEUE_NAMES[1]], null);
});

test('the access seed copies the release and terms of the smoke account and drops anything not base58', () => {
  const [release, terms] = realAccessSql("Smoke1'; drop table x;--", ['AbC123', "evil'--"]);
  // Quotes, spaces, ';', '-' and the letter 'l' (not base58) are all dropped.
  assert.match(release, /sw\.address = 'Smoke1droptabex'/);
  assert.match(release, /w\.address in \('AbC123', 'evi'\)/);
  assert.match(release, /'ACTIVE'/);
  assert.match(terms, /on conflict on constraint uq_terms_acceptance_user_version do nothing/);
  assert.doesNotMatch(release + terms, /;--/);
});

test('the hot queries carry the wallet, tenant and person of the run and name their tables', () => {
  const queries = hotQueries({ wallet: 'w-1', tenant: 't-1', user: 'u-1' });
  assert.equal(queries.length, 7);
  assert.ok(queries.some((q) => q.sql.includes("bot_wallet_id = 'w-1'") && q.sql.includes("tenant_id = 't-1'")));
  assert.ok(queries.some((q) => q.sql.includes("user_id = 'u-1'")));
  for (const q of queries) assert.match(q.sql, new RegExp(`from ${q.table}\\b`));
});

// ----- real.mjs: calls and flows with doubles -----

function person() {
  return { wallet: newWallet(), token: 'old-token', botAddress: newWallet().address };
}

test('a 401 signs the person in again and repeats the call with the same Idempotency-Key', async () => {
  const someone = person();
  let first = true;
  const io = fakeIo((method, path, body, headers) => {
    if (path === '/api/v1/auth/challenge') return { status: 200, body: { message: 'login message' } };
    if (path === '/api/v1/auth/verify') {
      const signature = decodeBase58(body.signature);
      assert.equal(verify(null, Buffer.from('login message'), createPublicKey(someone.wallet.key), signature), true);
      return { status: 200, body: { accessToken: 'new-token' } };
    }
    if (first) {
      first = false;
      return { status: 401 };
    }
    assert.equal(headers.Authorization, 'Bearer new-token');
    return { status: 201, body: { ok: true } };
  });
  const result = await call(io, someone, 'POST', '/api/v1/real/liquidations', { key: 'k-1' });
  assert.equal(result.status, 201);
  const posts = io.calls.filter((c) => c.path === '/api/v1/real/liquidations');
  assert.equal(posts.length, 2);
  assert.equal(posts[0].headers['Idempotency-Key'], 'k-1');
  assert.equal(posts[1].headers['Idempotency-Key'], 'k-1');
  assert.equal(someone.token, 'new-token');
  // A GET carries no Idempotency-Key; a person with its own re-authentication uses it.
  const admin = { token: 'a', reauth: async () => (admin.token = 'b') };
  const io2 = fakeIo((m, p, b, headers) => (headers.Authorization === 'Bearer a' ? { status: 401 } : { status: 200 }));
  assert.equal((await call(io2, admin, 'GET', '/api/v1/admin/real/users')).status, 200);
  assert.equal(io2.calls[0].headers['Idempotency-Key'], undefined);
  assert.equal(admin.token, 'b');
});

test('the activation signs the consent challenge with the main wallet and times only the POST', async () => {
  const someone = person();
  const samples = newSamples();
  const io = fakeIo((method, path, body) => {
    if (path === '/api/v1/real/consents/challenge') {
      assert.deepEqual(body, { kind: 'ACTIVATE', capLamports: '1000000000' });
      return { status: 201, body: { challengeId: 'c-1', message: 'consent text' }, ms: 15 };
    }
    if (path === '/api/v1/real/bot-wallet' && method === 'POST') {
      assert.equal(body.challengeId, 'c-1');
      assert.equal(verify(null, Buffer.from('consent text'), createPublicKey(someone.wallet.key), decodeBase58(body.signature)), true);
      return { status: 201, body: { address: 'BotAddress111' }, ms: 1200 };
    }
    return { status: 500 };
  });
  assert.equal(await activate(io, someone, '1000000000', samples), 'BotAddress111');
  assert.deepEqual(samples.activation, [1200]);
  const refused = fakeIo((m, path) =>
    path.endsWith('/challenge') ? { status: 201, body: { challengeId: 'c', message: 'm' } } : { status: 409 },
  );
  await assert.rejects(activate(refused, person(), '1', newSamples()), /activation failed with 409/);
  const noChallenge = fakeIo(() => ({ status: 429 }));
  await assert.rejects(activate(noChallenge, person(), '1', newSamples()), /consent challenge ACTIVATE failed with 429/);
});


test('a deposit waits for the balance to grow by the exact lamports after the transfer is finalized', async () => {
  const someone = person();
  const samples = newSamples();
  let balance = 100n;
  let polls = 0;
  const rpcCalls = [];
  const io = fakeIo(
    () => {
      polls += 1;
      if (polls >= 4) balance = 100n + 500_000_000n;
      return { status: 200, body: { balance: { totalLamports: String(balance) } } };
    },
    {
      rpc: async (method, params) => {
        rpcCalls.push(method);
        if (method === 'getLatestBlockhash') return { value: { blockhash: base58(Buffer.alloc(32, 3)) } };
        if (method === 'sendTransaction') {
          const wire = Buffer.from(params[0], 'base64');
          assert.equal(wire[0], 1);
          assert.equal(wire.readBigUInt64LE(wire.length - 8), 500_000_000n);
          return 'sig';
        }
        if (method === 'requestAirdrop') return 'airdrop-sig';
        return { value: [{ confirmationStatus: 'finalized' }] };
      },
    },
  );
  await deposit(io, someone, 500_000_000n, samples, 'setup', { fundMain: true });
  assert.deepEqual(rpcCalls, ['requestAirdrop', 'getSignatureStatuses', 'getLatestBlockhash', 'sendTransaction', 'getSignatureStatuses']);
  assert.equal(samples.depositVisible.length, 1);
  assert.ok(samples.depositVisible[0] > 0);
  assert.equal(samples.byPhase.setup.depositVisible.length, 1);
  const never = fakeIo(() => ({ status: 200, body: { balance: { totalLamports: '0' } } }), {
    rpc: async (method) =>
      method === 'getLatestBlockhash'
        ? { value: { blockhash: base58(Buffer.alloc(32, 3)) } }
        : method === 'sendTransaction'
          ? 's'
          : { value: [{ confirmationStatus: 'finalized' }] },
  });
  await assert.rejects(deposit(never, person(), 1n, newSamples(), 'peak', { timeoutMs: 5000 }), /did not show/);
  const stuck = fakeIo(() => ({ status: 200 }), {
    rpc: async (method) => (method === 'requestAirdrop' ? 'a' : { value: [null] }),
  });
  await assert.rejects(deposit(stuck, person(), 1n, newSamples(), 'setup', { fundMain: true }), /not finalized/);
});

test('the REAL bots follow the wizard; only the first bot of the founder selects the recorded mint', async () => {
  const someone = person();
  const drafts = [];
  const io = fakeIo((method, path, body) => {
    if (path === '/api/v1/strategy-templates') {
      return { status: 200, body: [{ templateId: 'MOMENTUM_VOLUME', parameters: [{ key: 'window', default: '5' }] }] };
    }
    if (path === '/api/v1/bots') {
      assert.equal(body.market, 'REAL');
      return { status: 201, body: { botId: `bot-${drafts.length}` } };
    }
    if (path.endsWith('/draft')) {
      drafts.push(body);
      return { status: 200 };
    }
    return { status: 200 };
  });
  await createBots(io, someone, 3, 'RecordedMint111');
  assert.equal(someone.bots.length, 3);
  const firstSteps = drafts.filter((d) => d.step === 1);
  assert.deepEqual(firstSteps[0].tokenSelection, { tokens: [{ chain: 'solana', mint: 'RecordedMint111' }], rules: [] });
  assert.equal(firstSteps[1].tokenSelection.tokens.length, 0);
  assert.equal(firstSteps[0].parameters.window, '5');
  const noTemplate = fakeIo(() => ({ status: 200, body: [] }));
  await assert.rejects(createBots(noTemplate, person(), 1, null), /template is missing/);
  const badStart = fakeIo((m, path) =>
    path === '/api/v1/strategy-templates'
      ? { status: 200, body: [{ templateId: 'MOMENTUM_VOLUME', parameters: [] }] }
      : path === '/api/v1/bots'
        ? { status: 201, body: { botId: 'b' } }
        : path.endsWith('/start')
          ? { status: 422 }
          : { status: 200 },
  );
  await assert.rejects(createBots(badStart, person(), 1, null), /start failed with 422/);
});

test('a person on the screen makes one action every 2 s through every route and counts unexpected answers', async () => {
  const someone = person();
  const samples = newSamples();
  const io = fakeIo((method, path) => {
    if (path === '/api/v1/real/pnl') return { status: 503, ms: 40 };
    if (path === '/api/v1/real/consents/challenge') return { status: 201, ms: 30 };
    if (path === '/api/v1/real/release') throw Object.assign(new Error('reset'), { name: 'TypeError' });
    return { status: 200, ms: 20 };
  });
  await screenUser(io, someone, 28_000, samples, (t) => (t < 14_000 ? 'steady' : 'peak'));
  assert.equal(io.calls.length, 14);
  assert.deepEqual(new Set(io.calls.map((c) => c.path)), new Set(SCREEN_ACTIONS.map((a) => a.path)));
  assert.equal(samples.unexpected['/api/v1/real/pnl 503'], 2);
  assert.equal(samples.unexpected['/api/v1/real/release TypeError'], 2);
  assert.equal(samples.walletReads.length, 8);
  assert.equal(samples.ordersAndPnl.length, 4);
  assert.ok(samples.byPhase.steady.walletReads.length > 0);
  assert.ok(samples.byPhase.peak.walletReads.length > 0);
  // Every action starts 2 s after the previous one, whatever its duration.
  assert.equal(io.t >= 28_000, true);
});

test('the administrator reads a page and the view of the first person every 2 s', async () => {
  const samples = newSamples();
  const io = fakeIo((method, path) =>
    path.startsWith('/api/v1/admin/real/users?')
      ? { status: 200, body: { items: [{ userId: 'u-1' }] }, ms: 50 }
      : { status: 404, ms: 20 },
  );
  await administrator(io, { token: 't' }, 4_000, samples, () => 'steady');
  assert.equal(samples.adminRoutes.length, 4);
  assert.equal(samples.unexpected['/api/v1/admin/real/users/{userId} 404'], 2);
  assert.ok(io.calls.some((c) => c.path === '/api/v1/admin/real/users/u-1'));
  const empty = newSamples();
  const broken = fakeIo(() => ({ status: 500, body: {} }));
  await administrator(broken, { token: 't' }, 2_000, empty, () => 'steady');
  assert.equal(empty.unexpected['/api/v1/admin/real/users 500'], 1);
});

test('a withdrawal is timed from its request to its first SENT read and followed until CONFIRMED', async () => {
  const someone = person();
  const samples = newSamples();
  const states = ['REQUESTED', 'SIGNED', 'SENT', 'SENT', 'CONFIRMED'];
  const io = fakeIo(() => ({ status: 200, body: { state: states.shift() ?? 'CONFIRMED' }, ms: 5 }));
  const toSend = await trackWithdrawal(io, someone, 'w-1', 0, samples, { burst: true });
  assert.equal(toSend, 2 * (5 + 250));
  assert.deepEqual(samples.withdrawalToSend, [toSend]);
  assert.deepEqual(samples.burstWithdrawalToSend, [toSend]);
  assert.equal(samples.walletReads.length, 5);

  const failed = newSamples();
  const ioFailed = fakeIo(() => ({ status: 200, body: { state: 'FAILED' } }));
  assert.equal(await trackWithdrawal(ioFailed, person(), 'w-2', 0, failed), null);
  assert.equal(failed.unexpected['withdrawal FAILED'], 1);

  const stuck = newSamples();
  const ioStuck = fakeIo(() => ({ status: 200, body: { state: 'REQUESTED' } }));
  assert.equal(await trackWithdrawal(ioStuck, person(), 'w-3', 0, stuck), null);
  assert.equal(stuck.unexpected['withdrawal NOT_SENT'], 1);

  const unconfirmed = newSamples();
  const ioUnconfirmed = fakeIo(() => ({ status: 200, body: { state: 'SENT' } }));
  // Already SENT on the first read, at the instant of the request.
  assert.equal(await trackWithdrawal(ioUnconfirmed, person(), 'w-4', 0, unconfirmed), 0);
  assert.equal(unconfirmed.unexpected['withdrawal NOT_CONFIRMED'], 1);

  const notFound = newSamples();
  let reads = 0;
  const ioNotFound = fakeIo(() => (reads++ === 0 ? { status: 404 } : { status: 200, body: { state: 'CONFIRMED' } }));
  await trackWithdrawal(ioNotFound, person(), 'w-5', 0, notFound);
  assert.equal(notFound.unexpected['/api/v1/real/bot-wallet/withdrawals/{withdrawalId} 404'], 1);
});

test('a burst sends one withdrawal per wallet and a replay with the same key must return the same withdrawal', async () => {
  const people = [person(), person(), person()];
  const samples = newSamples();
  const ledger = { requested: 0, replayMismatch: 0 };
  const byKey = new Map();
  let created = 0;
  const io = fakeIo((method, path, body, headers) => {
    if (method === 'POST') {
      assert.deepEqual(body, { amount: { lamports: '1000000' } });
      const key = headers['Idempotency-Key'];
      if (!byKey.has(key)) byKey.set(key, `w-${created++}`);
      return { status: 202, body: { withdrawalId: byKey.get(key) }, ms: 80 };
    }
    return { status: 200, body: { state: 'CONFIRMED' }, ms: 5 };
  });
  await burst(io, people, 10_000, seededRandom(1), samples, ledger);
  assert.equal(ledger.requested, 3);
  assert.equal(ledger.replayMismatch, 0);
  assert.equal(created, 3);
  assert.equal(samples.withdrawalPost.length, 3);
  assert.equal(samples.burstWithdrawalToSend.length, 3);

  const sloppy = { requested: 0, replayMismatch: 0 };
  let n = 0;
  const ioSloppy = fakeIo((method) =>
    method === 'POST' ? { status: 201, body: { withdrawalId: `x-${n++}` } } : { status: 200, body: { state: 'CONFIRMED' } },
  );
  await burst(ioSloppy, [person()], 0, seededRandom(1), newSamples(), sloppy);
  assert.equal(sloppy.replayMismatch, 1);

  const refused = newSamples();
  const ioRefused = fakeIo(() => ({ status: 429 }));
  const counted = { requested: 0, replayMismatch: 0 };
  await burst(ioRefused, [person()], 0, seededRandom(1), refused, counted);
  assert.equal(counted.requested, 0);
  assert.equal(refused.unexpected['/api/v1/real/bot-wallet/withdrawals 429'], 1);
});

test('"vender tudo e sacar" waits for an empty bot wallet; a refusal or a wallet that never empties is an error', async () => {
  const samples = newSamples();
  let reads = 0;
  const io = fakeIo((method) =>
    method === 'POST'
      ? { status: 202, body: { liquidationId: 'l-1' } }
      : { status: 200, body: { balance: { totalLamports: reads++ < 2 ? '5000' : '0' } } },
  );
  assert.equal(await liquidate(io, person(), samples), true);
  assert.equal(samples.liquidationPost.length, 1);
  const refused = newSamples();
  assert.equal(await liquidate(fakeIo(() => ({ status: 409 })), person(), refused), false);
  assert.equal(refused.unexpected['/api/v1/real/liquidations 409'], 1);
  const full = newSamples();
  const ioFull = fakeIo((method) =>
    method === 'POST' ? { status: 202 } : { status: 200, body: { balance: { totalLamports: '1' } } },
  );
  assert.equal(await liquidate(ioFull, person(), full, 9000), false);
  assert.equal(full.unexpected['liquidation NOT_EMPTY'], 1);
  await assert.rejects(
    liquidate(fakeIo((method) => (method === 'POST' ? { status: 202 } : { status: 500 })), person(), newSamples()),
    /bot wallet read failed with 500/,
  );
});

test('the sampler reads queues, pools, job maxima and the audit outbox every 10 s until the end', async () => {
  const api = [
    'hikaricp_connections_active{pool="memetrade-pool"} 4',
    'hikaricp_connections_pending{pool="memetrade-pool"} 0',
    'hikaricp_connections_max{pool="memetrade-pool"} 20',
    'audit_outbox_pending 3',
  ].join('\n');
  const worker = [
    'hikaricp_connections_active{pool="memetrade-worker-pool"} 11',
    'hikaricp_connections_pending{pool="memetrade-worker-pool"} 1',
    'hikaricp_connections_max{pool="memetrade-worker-pool"} 36',
    'real_ops_job_seconds_max{job="reconciliation"} 8.5',
    'real_ops_job_seconds_max{job="gates"} 0.9',
    'real_risk_lock_wait_seconds_max 0.05',
    'real_risk_job_seconds_max{job="day"} 0.12',
  ].join('\n');
  let failOnce = true;
  const io = fakeIo(() => ({ status: 200 }), {
    sql: async () => {
      if (failOnce) {
        failOnce = false;
        throw Object.assign(new Error('docker'), { name: 'Error' });
      }
      return '0|2|1|0\n';
    },
    apiMetrics: async () => api,
    workerMetrics: async () => worker,
    txsignerMetrics: async () => 'hikaricp_connections_active{pool="txsigner-pool"} 1',
  });
  const state = {
    loadStartedAt: 0,
    queues: {},
    pools: { api: [], worker: [], txsigner: [] },
    max: { reconciliationRound: [], gatesJob: [], lockWait: [], riskJobs: [] },
    sampleErrors: 0,
  };
  await sampler(io, 30_000, state);
  assert.equal(state.sampleErrors, 1);
  assert.equal(state.queues.saques_em_andamento.length, 2);
  assert.equal(state.queues.saques_em_andamento[0].depth, 2);
  assert.equal(state.queues.audit_outbox[0].depth, 3);
  assert.deepEqual(state.pools.worker[0], { active: 11, pending: 1, max: 36 });
  assert.equal(state.pools.txsigner[0].active, 1);
  assert.equal(highestMs(state.max.reconciliationRound), 8500);
  assert.equal(highestMs(state.max.gatesJob), 900);
  assert.equal(highestMs(state.max.riskJobs), 120);
});

test('the database checks count errors and duplicates since the setup and keep no wallet address', async () => {
  const queries = [];
  const answers = [
    [/real_order where state in \('FAILED'/, '1'],
    [/withdrawal where state in \('FAILED'/, '0'],
    [/decision = 'REFUSED'/, '0'],
    [/bot_cycle/, '0'],
    [/real_divergence/, '0'],
    [/real_release/, '0'],
    [/group by signature having/, '2'],
    [/from withdrawal where tx_signature/, '0'],
    [/from real_order where tx_signature/, '0'],
    [/decision_request_id/, '0'],
    [/kind = 'PARTIAL'/, '105'],
    [/date_trunc/, 'walletX|2026-11-20 10:00:00+00|5\nwalletY|2026-11-20 10:00:00+00|7\n'],
  ];
  const io = fakeIo(() => ({ status: 200 }), {
    sql: async (text) => {
      queries.push(text);
      return answers.find(([pattern]) => pattern.test(text))?.[1] ?? '';
    },
  });
  const checks = await databaseChecks(io, '2026-11-20T10:00:00.000Z');
  assert.equal(checks.errors.ordensFalhas, 1);
  assert.equal(checks.duplicates.assinaturasRepetidas, 2);
  assert.equal(checks.partials, 105);
  assert.deepEqual(checks.signings, { max: 7, wallets: 2, limit: 60 });
  assert.ok(queries.every((q) => !/require|drop|delete|update/i.test(q)));
  assert.ok(queries.slice(0, 5).every((q) => q.includes("'2026-11-20T10:00:00.000Z'")));
  assert.equal(JSON.stringify(checks).includes('walletX'), false);
});

test('each hot query has its natural plan recorded and needs an index path with sequential scans off', async () => {
  const wallet = '11111111-1111-4111-8111-111111111111';
  const io = fakeIo(() => ({ status: 200 }), {
    sql: async (text, options = {}) => {
      if (text.startsWith('select bot_wallet_id')) return `${wallet}|${wallet}|${wallet}\n`;
      if (options.seqScanOff) {
        return text.includes('from observed_deposit') ? 'Seq Scan on observed_deposit  (cost=1e10..)' : 'Index Scan using x';
      }
      return 'Limit  (cost=0.1..2.0) (actual time=0.01..0.02)\n  ->  Index Scan';
    },
  });
  const result = await plans(io);
  assert.equal(result.length, 7);
  assert.equal(result.find((p) => p.table === 'observed_deposit').indexPath, false);
  assert.equal(result.filter((p) => p.indexPath).length, 6);
  assert.match(result[0].firstLine, /^Limit/);
  const empty = fakeIo(() => ({ status: 200 }), { sql: async () => '\n' });
  assert.deepEqual(await plans(empty), []);
});

// ----- real.mjs: the whole scenario against a simulated stack (short phases, same flow and same rules) -----

/** A simulated api, validator, database and actuators: every person gets a bot wallet, deposits and withdrawals. */
function simulatedStack({ slowWalletReadsMs = 20, refuseSignings = 0 } = {}) {
  const botOf = new Map();
  const balance = new Map();
  const withdrawals = new Map();
  const reads = new Map();
  const addresses = [];
  let tokens = 0;
  let bots = 0;
  const personOf = (headers) => headers.Authorization?.slice('Bearer '.length);
  const io = fakeIo((method, path, body, headers) => {
    const token = personOf(headers);
    if (path === '/api/v1/legal/current') return { status: 200, body: { version: 'v3' } };
    if (path.endsWith('/auth/challenge')) return { status: 200, body: { message: 'login' } };
    if (path.endsWith('/auth/verify')) return { status: 200, body: { accessToken: `tok-${tokens++}` } };
    if (path === '/api/v1/real/consents/challenge') {
      return { status: 201, body: { challengeId: 'c', message: 'consent' }, ms: 30 };
    }
    if (path === '/api/v1/real/bot-wallet' && method === 'POST') {
      const address = newWallet().address;
      addresses.push(address);
      botOf.set(token, address);
      balance.set(address, 0n);
      return { status: 201, body: { address }, ms: 900 };
    }
    if (path === '/api/v1/real/bot-wallet') {
      const total = String(balance.get(botOf.get(token)) ?? 0n);
      return { status: 200, body: { balance: { totalLamports: total } }, ms: slowWalletReadsMs };
    }
    if (path === '/api/v1/real/mode') return { status: 200 };
    if (path === '/api/v1/strategy-templates') {
      return { status: 200, body: [{ templateId: 'MOMENTUM_VOLUME', parameters: [{ key: 'k', default: '1' }] }] };
    }
    if (path === '/api/v1/bots') return { status: 201, body: { botId: `bot-${bots++}` } };
    if (path.startsWith('/api/v1/bots/')) return { status: 200 };
    if (path === '/api/v1/real/liquidations') {
      balance.set(botOf.get(token), 0n);
      return { status: 202, body: { liquidationId: 'l' }, ms: 120 };
    }
    if (path === '/api/v1/real/bot-wallet/withdrawals' && method === 'POST') {
      const key = headers['Idempotency-Key'];
      if (!withdrawals.has(key)) withdrawals.set(key, `w-${withdrawals.size}`);
      return { status: 202, body: { withdrawalId: withdrawals.get(key) }, ms: 90 };
    }
    if (path.startsWith('/api/v1/real/bot-wallet/withdrawals/')) {
      const seen = (reads.get(path) ?? 0) + 1;
      reads.set(path, seen);
      return { status: 200, body: { state: seen < 3 ? 'SIGNED' : seen < 5 ? 'SENT' : 'CONFIRMED' }, ms: 5 };
    }
    if (path.startsWith('/api/v1/admin/real/users?')) return { status: 200, body: { items: [{ userId: 'u-1' }] }, ms: 60 };
    if (path.startsWith('/api/v1/admin/real/users/')) return { status: 200, ms: 40 };
    if (path.startsWith('/api/v1/real/')) {
      return { status: 200, ms: path.includes('orders') || path.includes('pnl') ? 50 : slowWalletReadsMs };
    }
    return { status: 500 };
  });
  io.rpc = async (method, params) => {
    if (method === 'getLatestBlockhash') return { value: { blockhash: base58(Buffer.alloc(32, 4)) } };
    if (method === 'requestAirdrop') return 'airdrop';
    if (method === 'sendTransaction') {
      const message = Buffer.from(params[0], 'base64').subarray(65);
      const to = base58(message.subarray(36, 68));
      balance.set(to, (balance.get(to) ?? 0n) + message.readBigUInt64LE(message.length - 8));
      return 'transfer';
    }
    return { value: [{ confirmationStatus: 'finalized' }] };
  };
  const wallet = '22222222-2222-4222-8222-222222222222';
  io.sql = async (text, options = {}) => {
    if (/r\.state = 'ACTIVE'/.test(text)) return '1\n';
    if (text.startsWith('insert')) return '';
    if (text.startsWith('select (select count(*) from real_decision_request')) return '0|0|0|0\n';
    if (text.startsWith('select bot_wallet_id')) return `${wallet}|${wallet}|${wallet}\n`;
    if (text.startsWith('explain')) return options.seqScanOff ? 'Index Scan using idx' : 'Limit  (cost=0..1)\n';
    if (/decision = 'REFUSED'/.test(text)) return `${refuseSignings}\n`;
    if (/kind = 'PARTIAL'/.test(text)) return `${withdrawals.size}\n`;
    if (/date_trunc/.test(text)) return 'walletA|2026-11-20 10:00:00+00|3\n';
    return '0\n';
  };
  const hist = (name, labels, bound, count) => histogramText(name, [[String(bound), count], ['+Inf', count]], labels);
  let apiReads = 0;
  let workerReads = 0;
  let signerReads = 0;
  io.apiMetrics = async () =>
    apiReads++ === 0
      ? ''
      : [
          'hikaricp_connections_active{pool="memetrade-pool"} 4',
          'hikaricp_connections_max{pool="memetrade-pool"} 20',
          'audit_outbox_pending 0',
        ].join('\n');
  io.workerMetrics = async () =>
    workerReads++ === 0
      ? ''
      : [
          'hikaricp_connections_active{pool="memetrade-worker-pool"} 9',
          'hikaricp_connections_max{pool="memetrade-worker-pool"} 36',
          hist('real_risk_evaluation_seconds', 'side="BUY"', 0.1, 40),
          hist('real_risk_evaluation_seconds', 'side="SELL"', 0.025, 2),
          hist('real_risk_lock_wait_seconds', '', 0.05, 40),
          'real_risk_lock_wait_seconds_max 0.04',
          hist('real_risk_job_seconds', 'job="day"', 0.5, 3),
          'real_risk_job_seconds_max{job="day"} 0.3',
          hist('real_order_decision_to_send_seconds', 'mode="AUTO_FULL"', 2, 2),
          hist('real_decision_queue_wait_seconds', '', 0.2, 2),
          hist('real_reconciliation_seconds', '', 1, 60),
          hist('real_ops_job_seconds', 'job="reconciliation"', 30, 3),
          'real_ops_job_seconds_max{job="reconciliation"} 12',
          hist('real_ops_job_seconds', 'job="gates"', 5, 1),
          'real_ops_job_seconds_max{job="gates"} 1.5',
          hist('loadtest_bots_cycle_seconds', '', 1, 30),
        ].join('\n');
  io.txsignerMetrics = async () =>
    signerReads++ === 0
      ? ''
      : [
          hist('txsigner_latency_seconds', 'kind="WITHDRAWAL"', 0.1, 9),
          'txsigner_latency_seconds_max{kind="WITHDRAWAL"} 0.08',
        ].join('\n');
  return { io, addresses };
}

const SHORT = {
  people: 3,
  screenUsers: 2,
  botsPerPerson: 1,
  steadyS: 30,
  peakS: 20,
  burstS: 20,
  peakWallets: 2,
  burstEveryS: 10,
  burstSpreadS: 1,
  actionEveryMs: 2000,
  sampleEveryMs: 10_000,
  drainS: 10,
  capLamports: '1000000000',
  airdropLamports: 2_000_000_000n,
  depositLamports: 500_000_000n,
  partialWithdrawalLamports: '1000000',
  seed: 1,
};

test('the whole scenario passes on a healthy simulated stack and fills every part of the report', async () => {
  const { io, addresses } = simulatedStack();
  // The first activation of the stack is the founder's: the recorded wallet is the first address it hands out.
  const recorded = { RECORDED_MINT: 'Mint' };
  Object.defineProperty(recorded, 'RECORDED_BOT_WALLET', { get: () => addresses[0] ?? 'pending', enumerable: true });
  const report = await runReal({ config: SHORT, io, founder: newWallet(), adminWallet: newWallet(), codes: ['a', 'b'], recorded });
  assert.deepEqual(report.failures, []);
  assert.equal(report.passed, true);
  assert.equal(report.scenario, 'real');
  assert.match(report.environment, /não é produção/);
  assert.equal(report.config.depositLamports, '500000000');
  assert.equal(report.measured.activation.n, 3);
  assert.equal(report.measured.depositVisible.n, 3 + 2);
  assert.equal(report.withdrawalsRequested, 2 * 3);
  assert.equal(report.measured.burstWithdrawalToSend.n, 6);
  assert.equal(report.measured.reconciliationRound.max, 12_000);
  assert.equal(report.measured.gatesJob.n, 1);
  assert.equal(report.txsigner.WITHDRAWAL.n, 9);
  assert.equal(report.signings.max, 3);
  assert.equal(report.plans.length, 7);
  assert.ok(report.byPhase.steady.walletReads.n > 0);
  assert.ok(report.byPhase.burst.withdrawalPost.n > 0);
  assert.equal(report.duplicates.repeticoesComOutroSaque, 0);
  assert.equal(report.queues.decisoes_pendentes.growing, false);
  assert.match(realMarkdown(report), /\*\*aprovado\*\*/);
});

test('the scenario fails on refused signatures, slow wallet reads and a founder wallet that is not the recorded one', async () => {
  const { io } = simulatedStack({ slowWalletReadsMs: 400, refuseSignings: 2 });
  const report = await runReal({
    config: SHORT,
    io,
    founder: newWallet(),
    adminWallet: newWallet(),
    codes: ['a', 'b'],
    recorded: { RECORDED_MINT: 'Mint', RECORDED_BOT_WALLET: 'SomeOtherWallet' },
  });
  assert.equal(report.passed, false);
  assert.ok(report.failures.includes('U1:NFR3.1: p95 acima de 300 ms'));
  assert.ok(report.failures.includes('erro assinaturasRecusadas: 2'));
  assert.ok(report.failures.some((f) => f.startsWith('a carteira do bot do fundador')));
});

test('the scenario refuses to start without enough invitations, without the recordings or outside localhost', async () => {
  const { io } = simulatedStack();
  const base = { config: SHORT, io, founder: newWallet(), adminWallet: newWallet() };
  const recorded = { RECORDED_MINT: 'm', RECORDED_BOT_WALLET: 'w' };
  await assert.rejects(runReal({ ...base, codes: ['a'], recorded }), /2 needed/);
  await assert.rejects(runReal({ ...base, codes: ['a', 'b'], recorded: {} }), /recorded swap fixtures are missing/);
  io.rpcBase = 'https://api.devnet.solana.com';
  await assert.rejects(runReal({ ...base, codes: ['a', 'b'], recorded }), /only targets localhost/);
});
