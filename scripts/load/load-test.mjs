// Local load test (U1 NFR3.1, U4 cicd-pipeline.md). 20 virtual users, one action every 2 s, for DURATION_S seconds.
//   LOAD_SCENARIO=u1     login challenges, verifications and the list without filters; every p95 at most 300 ms.
//   LOAD_SCENARIO=u4     plus the list with varied filters and orders (p95 at most 1 s, NFR3.1) and the assessment
//                        detail (p95 at most 500 ms, NFR3.2); the U1 limit of 300 ms stays on challenge and verify.
//   LOAD_SCENARIO=u3     (U3 NFR3.1, NFR3.2) one invitation per user from SMOKE_INVITE_CODES and one wallet per user
//                        generated here: 20 simultaneous verifications with an invitation (p95 at most 500 ms, timed
//                        per request because the route also serves sign-ins without invitation), then DURATION_S of
//                        invitation lookups and Account screens (server p95 at most 300 ms each) and no 429.
//   LOAD_SCENARIO=growth the U4 actions with 10000 tokens per source: recorded only, never failed (NFR3.5, before G1).
// Server-side p95 per route comes from the Prometheus histogram on the management port. During the run the script
// also reads hikaricp_connections_pending every 5 s (fails when above 0 for more than 10 s in a row), the memory of
// the api and postgres containers (fails above 900 MB) and, at the end, the plan of the history query (fails on a
// Seq Scan on observation_sample, NFR2.6). Never targets anything but localhost; synthetic values only.
import { execFile } from 'node:child_process';
import { createPrivateKey, generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { promisify } from 'node:util';

const run = promisify(execFile);
const API = process.env.API_BASE_URL ?? 'http://127.0.0.1:8080';
const MANAGEMENT = process.env.MANAGEMENT_URL ?? 'http://127.0.0.1:8081';
const SCENARIO = process.env.LOAD_SCENARIO ?? 'u1';
const USERS = Number(process.env.USERS ?? 20);
const DURATION_S = Number(process.env.DURATION_S ?? (SCENARIO === 'u1' ? 300 : 600));
const MEMORY_LIMIT_MB = 900;
const LIMITS_S = {
  challenge: 0.3,
  verify: 0.3,
  list: SCENARIO === 'u1' ? 0.3 : 1.0,
  detail: 0.5,
};
const FAILS = SCENARIO !== 'growth';
const ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
const FILTERS = [
  '',
  'minLiquidity=10000',
  'maxAgeMinutes=60&sort=LIQUIDITY',
  'minHolders=200&maxRiskScore=69&sort=RISK_SCORE',
  'minVolume24h=50000&order=ASC&sort=VOLUME_24H',
  'sourceId=sim-b&sort=HOLDERS',
];

function base58(bytes) {
  let n = BigInt(`0x${Buffer.from(bytes).toString('hex') || '0'}`);
  let out = '';
  while (n > 0n) {
    out = ALPHABET[Number(n % 58n)] + out;
    n /= 58n;
  }
  for (const b of bytes) {
    if (b !== 0) break;
    out = `1${out}`;
  }
  return out;
}

const wallet = JSON.parse(readFileSync(process.env.SMOKE_WALLET_FILE, 'utf8'));
const key = createPrivateKey({
  key: Buffer.concat([Buffer.from('302e020100300506032b657004220420', 'hex'), Buffer.from(wallet.seed, 'base64')]),
  format: 'der',
  type: 'pkcs8',
});

async function post(path, body) {
  const response = await fetch(`${API}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
    body: JSON.stringify(body),
  });
  return { status: response.status, body: await response.json().catch(() => ({})) };
}

async function login() {
  const challenge = await post('/api/v1/auth/challenge', { walletAddress: wallet.address });
  const signature = base58(sign(null, Buffer.from(challenge.body.message, 'utf8'), key));
  const verified = await post('/api/v1/auth/verify', {
    walletAddress: wallet.address,
    message: challenge.body.message,
    signature,
    provider: 'PHANTOM',
  });
  if (verified.status !== 200) throw new Error(`login failed with ${verified.status}`);
  return verified.body.accessToken;
}

async function list(state, query) {
  const response = await fetch(`${API}/api/v1/scanner/tokens?limit=50${query ? `&${query}` : ''}`, {
    headers: { Authorization: `Bearer ${state.token}` },
  });
  const body = await response.json().catch(() => ({}));
  for (const item of body.items ?? []) {
    state.mints.add(`${item.chain}/${item.mint}`);
  }
}

async function detail(state) {
  const known = [...state.mints];
  if (known.length === 0) {
    return list(state, '');
  }
  const target = known[Math.floor(Math.random() * known.length)];
  await fetch(`${API}/api/v1/tokens/${target}/assessment`, { headers: { Authorization: `Bearer ${state.token}` } });
}

async function action(index, step, state) {
  if (step % 3 === 0) {
    return post('/api/v1/auth/challenge', { walletAddress: base58(randomBytes(32)) });
  }
  if (step % 10 === 1 && index === 0) {
    state.token = await login();
    return undefined;
  }
  if (SCENARIO === 'u1') {
    return list(state, '');
  }
  return step % 2 === 0 ? detail(state) : list(state, FILTERS[step % FILTERS.length]);
}

async function user(index, deadline, state) {
  let step = index;
  while (Date.now() < deadline) {
    const started = Date.now();
    try {
      await action(index, step, state);
    } catch (failure) {
      state.errors += 1;
      console.error(`user ${index}: ${failure.name}`);
    }
    step += 1;
    await new Promise((resolve) => setTimeout(resolve, Math.max(0, 2_000 - (Date.now() - started))));
  }
}

function p95(metrics, uri) {
  const escaped = uri.replace(/[/{}]/g, (c) => `\\${c}`);
  const pattern = new RegExp(
    `^http_server_requests_seconds_bucket\\{[^}]*le="([^"]+)"[^}]*uri="${escaped}"[^}]*\\} (\\S+)$|` +
      `^http_server_requests_seconds_bucket\\{[^}]*uri="${escaped}"[^}]*le="([^"]+)"[^}]*\\} (\\S+)$`,
  );
  const buckets = new Map();
  for (const line of metrics.split('\n')) {
    const m = pattern.exec(line);
    if (!m) continue;
    const le = m[1] ?? m[3];
    const count = Number(m[2] ?? m[4]);
    const bound = le === '+Inf' ? Infinity : Number(le);
    buckets.set(bound, (buckets.get(bound) ?? 0) + count);
  }
  const sorted = [...buckets.entries()].sort((a, b) => a[0] - b[0]);
  const total = sorted.at(-1)?.[1] ?? 0;
  if (total === 0) return null;
  return sorted.find(([, count]) => count >= total * 0.95)?.[0] ?? null;
}

/** Pool wait: seconds in a row with pending connections; fails above 10 s (NFR3.1 budget, infrastructure spec). */
async function watchPool(deadline, report) {
  let pendingSince = null;
  while (Date.now() < deadline) {
    try {
      const metrics = await (await fetch(`${MANAGEMENT}/actuator/prometheus`)).text();
      const line = metrics.split('\n').find((l) => l.startsWith('hikaricp_connections_pending{pool="memetrade-pool"'));
      const pending = line ? Number(line.split(' ').at(-1)) : 0;
      report.maxPending = Math.max(report.maxPending, pending);
      if (pending > 0) {
        pendingSince ??= Date.now();
        report.longestPendingSeconds = Math.max(report.longestPendingSeconds, (Date.now() - pendingSince) / 1000);
      } else {
        pendingSince = null;
      }
    } catch (failure) {
      report.poolReadErrors += 1;
      console.error(`pool read: ${failure.name}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 5_000));
  }
}

function megabytes(usage) {
  const [value, unit] = /([\d.]+)\s*([KMG]i?B)/.exec(usage)?.slice(1) ?? ['0', 'MiB'];
  const factor = { KiB: 1 / 1024, KB: 1 / 1000, MiB: 1, MB: 1, GiB: 1024, GB: 1000 }[unit] ?? 1;
  return Number(value) * factor;
}

/** Memory of the api and postgres containers through docker stats, every 15 s. */
async function watchMemory(deadline, report) {
  const containers = { api: process.env.API_CONTAINER, postgres: process.env.POSTGRES_CONTAINER };
  while (Date.now() < deadline) {
    for (const [name, id] of Object.entries(containers)) {
      if (!id) continue;
      try {
        const { stdout } = await run('docker', ['stats', '--no-stream', '--format', '{{.MemUsage}}', id]);
        report.maxMemoryMb[name] = Math.max(report.maxMemoryMb[name] ?? 0, megabytes(stdout.split('/')[0]));
      } catch (failure) {
        console.error(`docker stats ${name}: ${failure.name}`);
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 15_000));
  }
}

/** NFR2.6: the 1-hour price query reads the history through its index, never a sequential scan. */
async function historyPlan() {
  if (!process.env.POSTGRES_CONTAINER) return 'not checked: POSTGRES_CONTAINER not set';
  const sql =
    "EXPLAIN select distinct on (x.chain, x.mint) x.chain, x.mint, x.value from observation_sample x " +
    "where x.field = 'PRICE' and x.status = 'KNOWN' and x.observed_at between now() - interval '65 minutes' " +
    "and now() - interval '60 minutes' order by x.chain, x.mint, x.observed_at desc, x.source_id";
  const { stdout } = await run('docker', [
    'exec',
    process.env.POSTGRES_CONTAINER,
    'psql',
    '-U',
    process.env.DB_SUPERUSER ?? 'postgres',
    '-d',
    process.env.DB_NAME ?? 'memetrade',
    '-c',
    sql,
  ]);
  return stdout;
}

function markdown(report) {
  const rows = Object.entries(report.p95Seconds)
    .map(([route, value]) => `| ${route} | ${value === null ? 'sem dados' : `${value * 1000} ms`} | ${LIMITS_S[route] * 1000} ms |`)
    .join('\n');
  return `# Carga local — U4 Descoberta e avaliação (${report.date})

- Cenário: \`${report.scenario}\` · usuários: ${report.users} · duração: ${report.durationSeconds} s · tokens por fonte: ${report.tokensPerSource}
- Resultado: **${report.passed ? 'aprovado' : report.scenario === 'growth' ? 'só anotado (crescimento)' : 'reprovado'}**

| Rota | p95 | Limite |
|---|---|---|
${rows}

- Espera do pool: máximo de ${report.pool.maxPending} conexões pendentes; maior sequência ${report.pool.longestPendingSeconds} s (limite 10 s)
- Memória máxima: api ${Math.round(report.maxMemoryMb.api ?? 0)} MB, postgres ${Math.round(report.maxMemoryMb.postgres ?? 0)} MB (limite ${MEMORY_LIMIT_MB} MB)
- Plano do histórico sem \`Seq Scan\` em \`observation_sample\`: ${report.historyPlanWithoutSeqScan ? 'sim' : 'não'}
- Erros de cliente: ${report.errors}
`;
}

// ----- U3 scenario -----

const U3_LIMITS_S = { verifyWithInvite: 0.5, lookup: 0.3, account: 0.3 };

/** A wallet generated in this test process (never a real one): public key in Base58 and its signing key. */
function u3Wallet() {
  const { privateKey, publicKey } = generateKeyPairSync('ed25519');
  const spki = publicKey.export({ format: 'der', type: 'spki' });
  return { address: base58(spki.subarray(spki.length - 32)), key: privateKey };
}

/** Challenge, then the verification with the invitation; returns the client-side time of the verification only. */
async function createAccount(code, wallet) {
  const challenge = await post('/api/v1/auth/challenge', { walletAddress: wallet.address });
  const signature = base58(sign(null, Buffer.from(challenge.body.message, 'utf8'), wallet.key));
  const started = performance.now();
  const verified = await post('/api/v1/auth/verify', {
    walletAddress: wallet.address,
    message: challenge.body.message,
    signature,
    provider: 'PHANTOM',
    inviteCode: code,
    acceptedTermsVersion: 'v1',
  });
  const seconds = (performance.now() - started) / 1000;
  if (verified.status !== 200) throw new Error(`account creation failed with ${verified.status}`);
  return { token: verified.body.accessToken, seconds };
}

function percentile95(values) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * 0.95) - 1)];
}

async function u3User(index, deadline, account, state) {
  let step = index;
  while (Date.now() < deadline) {
    const started = Date.now();
    try {
      const response =
        step % 2 === 0
          ? await fetch(`${API}/api/v1/invites/${encodeURIComponent(account.code)}`)
          : await fetch(`${API}/api/v1/account`, { headers: { Authorization: `Bearer ${account.token}` } });
      if (response.status === 429) state.rateLimited += 1;
      else if (response.status !== 200) state.errors += 1;
    } catch (failure) {
      state.errors += 1;
      console.error(`user ${index}: ${failure.name}`);
    }
    step += 1;
    await new Promise((resolve) => setTimeout(resolve, Math.max(0, 2_000 - (Date.now() - started))));
  }
}

function u3Markdown(report) {
  const rows = Object.entries(report.p95Seconds)
    .map(([route, value]) => `| ${route} | ${value === null ? 'sem dados' : `${Math.round(value * 1000)} ms`} | ${U3_LIMITS_S[route] * 1000} ms |`)
    .join('\n');
  return `# Carga local — U3 Convites, contas e sessões (${report.date})

- Cenário: \`u3\` · usuários: ${report.users} · duração: ${report.durationSeconds} s
- Resultado: **${report.passed ? 'aprovado' : 'reprovado'}**

| Rota | p95 | Limite |
|---|---|---|
${rows}

- Verificação com convite: ${report.accountsCreated} contas criadas em verificações simultâneas (tempo medido por requisição no cliente)
- Respostas 429 na consulta do convite e na tela Conta (perfil \`loadtest\`): ${report.rateLimited}
- Espera do pool: máximo de ${report.pool.maxPending} conexões pendentes; maior sequência ${report.pool.longestPendingSeconds} s (limite 10 s)
- Memória máxima: api ${Math.round(report.maxMemoryMb.api ?? 0)} MB, postgres ${Math.round(report.maxMemoryMb.postgres ?? 0)} MB (limite ${MEMORY_LIMIT_MB} MB)
- Erros de cliente: ${report.errors}
- Falhas: ${report.failures.length === 0 ? 'nenhuma' : report.failures.join(', ')}
`;
}

async function runU3() {
  const codes = (process.env.SMOKE_INVITE_CODES ?? '').split(',').filter((code) => code.length > 0);
  if (codes.length < USERS) throw new Error(`SMOKE_INVITE_CODES has ${codes.length} codes, ${USERS} needed`);
  const created = await Promise.all(
    codes.slice(0, USERS).map(async (code) => ({ code, ...(await createAccount(code, u3Wallet())) })),
  );
  const state = { errors: 0, rateLimited: 0 };
  const deadline = Date.now() + DURATION_S * 1_000;
  const pool = { maxPending: 0, longestPendingSeconds: 0, poolReadErrors: 0 };
  const memory = { maxMemoryMb: {} };
  await Promise.all([
    ...created.map((account, i) => u3User(i, deadline, account, state)),
    watchPool(deadline, pool),
    watchMemory(deadline, memory),
  ]);
  const metrics = await (await fetch(`${MANAGEMENT}/actuator/prometheus`)).text();
  const report = {
    scenario: 'u3',
    date: new Date().toISOString().slice(0, 10),
    users: USERS,
    durationSeconds: DURATION_S,
    accountsCreated: created.length,
    errors: state.errors,
    rateLimited: state.rateLimited,
    p95Seconds: {
      verifyWithInvite: percentile95(created.map((account) => account.seconds)),
      lookup: p95(metrics, '/api/v1/invites/{code}'),
      account: p95(metrics, '/api/v1/account'),
    },
    pool,
    maxMemoryMb: memory.maxMemoryMb,
  };
  report.failures = [
    ...Object.entries(report.p95Seconds)
      .filter(([route, value]) => value === null || value > U3_LIMITS_S[route])
      .map(([route]) => `p95 ${route}`),
    ...(state.rateLimited > 0 ? ['429 under the loadtest profile'] : []),
    ...(state.errors > 0 ? ['client errors'] : []),
    ...(pool.longestPendingSeconds > 10 ? ['pool wait'] : []),
    ...Object.entries(memory.maxMemoryMb)
      .filter(([, mb]) => mb > MEMORY_LIMIT_MB)
      .map(([name]) => `memory ${name}`),
  ];
  report.passed = report.failures.length === 0;
  writeFileSync('load-test-report.json', JSON.stringify(report, null, 2));
  mkdirSync('docs/perf', { recursive: true });
  writeFileSync(`docs/perf/u3-identity-access-${report.date}.md`, u3Markdown(report));
  console.warn(JSON.stringify(report));
  process.exit(report.passed ? 0 : 1);
}

if (SCENARIO === 'u3') {
  await runU3();
}

// ----- U7 scenarios (cicd-pipeline.md) -----
//   LOAD_SCENARIO=u7      the load cycle driver of the loadtest profile seeds 20 people with 10000 fills each and
//                         runs 60 bots every 30 s; 20 users read P&L (30 and 366 days), ledger, trades, day usage and
//                         reconciliation, one action every 2 s, for DURATION_S. p95 execute <= 250 ms (NFR3.1, metric
//                         loadtest_paper_execute_seconds), p95 of each C5 route <= 1 s (NFR3.2, NFR3.3), and the plans
//                         of the P&L, ledger and balance queries without a Seq Scan on ledger_entry or fill (NFR2.2).
//   LOAD_SCENARIO=u7-jobs 60 bots with 5 positions for 30 minutes; every marking and pending close round at most 10 s
//                         (NFR3.5), read from the "round finished ... durationMs" lines of the api log.
// The people are the smoke account plus one account per invitation of SMOKE_INVITE_CODES, each with a wallet generated
// here in memory (never a real one). Each person signs in and reads the ledger once: the driver of the loadtest
// profile takes the person from that validated session (it never builds a principal), creates the bots round-robin
// over the people, funds them, opens the positions and seeds the fills, and reports through the health component
// loadtestLedger. Both scenarios write their own section of docs/perf/u7-ledger-pnl-<date>.md.

//   In u7 the driver also closes LOADTEST_CLOSE_BOTS bots (p95 closePositions <= 250 ms, NFR3.1, metric
//   loadtest_paper_close_seconds), every reconciliation round of the api log ("Reconciliation round finished ...
//   durationMs") takes at most 60 s and, after the measurement, "check now" of every person answers within 5 s
//   (NFR3.6, timed here per request).
// Memory guard: 900 MB per container, except PostgreSQL in u7 at 1 GB (1024 MiB). Decision of the project owner on
// 2026-11-04 (review repair R-02 of the U7 code generation): docker stats counts the page cache of the container, and
// the peak was 902 MB with 200 311 fills; every other limit and scenario keeps 900 MB.
const U7_POSTGRES_MEMORY_LIMIT_MB = 1024;
function u7MemoryLimitMb(container) {
  return SCENARIO === 'u7' && container === 'postgres' ? U7_POSTGRES_MEMORY_LIMIT_MB : MEMORY_LIMIT_MB;
}

const U7_LIMITS_S = {
  execute: 0.25,
  close: 0.25,
  pnl: 1.0,
  ledger: 1.0,
  trades: 1.0,
  dayUsage: 1.0,
  reconciliation: 1.0,
  checkNow: 5.0,
};
const RECONCILIATION_ROUND_LIMIT_MS = 60_000;
const U7_ROUTES = {
  pnl: '/api/v1/pnl',
  ledger: '/api/v1/ledger',
  trades: '/api/v1/bots/{botId}/trades',
  dayUsage: '/api/v1/bots/{botId}/day-usage',
  reconciliation: '/api/v1/reconciliation',
};

/** Signs a person in again with its wallet (the access token lasts 15 minutes; the preparation can take longer). */
async function signIn(person) {
  const challenge = await post('/api/v1/auth/challenge', { walletAddress: person.wallet.address });
  const signature = base58(sign(null, Buffer.from(challenge.body.message, 'utf8'), person.wallet.key));
  const verified = await post('/api/v1/auth/verify', {
    walletAddress: person.wallet.address,
    message: challenge.body.message,
    signature,
    provider: 'PHANTOM',
  });
  if (verified.status !== 200) throw new Error(`sign-in failed with ${verified.status}`);
  person.token = verified.body.accessToken;
  return person;
}

/** The smoke account plus one new account per invitation; each reads the ledger once so the driver sees it. */
async function u7People() {
  const codes = (process.env.SMOKE_INVITE_CODES ?? '').split(',').filter((code) => code.length > 0);
  if (codes.length < USERS - 1) throw new Error(`SMOKE_INVITE_CODES has ${codes.length} codes, ${USERS - 1} needed`);
  const smoke = await signIn({ wallet: { address: wallet.address, key } });
  const invited = await Promise.all(
    codes.slice(0, USERS - 1).map(async (code) => {
      const generated = u3Wallet();
      return { wallet: generated, token: (await createAccount(code, generated)).token };
    }),
  );
  const people = [smoke, ...invited];
  for (const person of people) {
    const response = await fetch(`${API}/api/v1/ledger?limit=1`, { headers: { Authorization: `Bearer ${person.token}` } });
    if (response.status !== 200) throw new Error(`first ledger read failed with ${response.status}`);
  }
  return people;
}

/**
 * Waits for the health component loadtestLedger to be UP (the preparation is over and the cycles run), at most 90
 * minutes, printing the progress every minute. A missing component means the profile or the size is wrong; FAILED
 * stops the scenario with the reason of the driver.
 */
async function waitForPreparation() {
  const deadline = Date.now() + 90 * 60 * 1_000;
  let lastProgress = 0;
  while (Date.now() < deadline) {
    const health = await (await fetch(`${MANAGEMENT}/actuator/health`)).json().catch(() => ({}));
    const component = health.components?.loadtestLedger;
    if (!component) {
      throw new Error('the load cycle driver is not active: SPRING_PROFILES_ACTIVE=test,loadtest and LOADTEST_LEDGER_PEOPLE > 0');
    }
    const details = component.details ?? {};
    if (details.state === 'FAILED') throw new Error(`the load cycle driver failed: ${details.failure}`);
    if (component.status === 'UP') return details;
    if (Date.now() - lastProgress > 60_000) {
      lastProgress = Date.now();
      console.warn(
        `preparation: ${details.state}, people ${details.peopleSeen}/${details.peopleExpected}, fills ${details.fillsRecorded}/${details.fillsExpected}`,
      );
    }
    await new Promise((resolve) => setTimeout(resolve, 10_000));
  }
  throw new Error('the preparation of the load ledger did not finish in 90 minutes');
}

function iso(ms) {
  return new Date(ms).toISOString().replace(/\.\d{3}Z$/, 'Z');
}

/** One reader per person, on that person's own data only; a 401 (expired token) signs the person in again. */
async function u7User(index, deadline, person, botId, state) {
  const to = Math.floor(Date.now() / 1_000) * 1_000 + 86_400_000;
  const actions = [
    `/api/v1/pnl?from=${iso(to - 30 * 86_400_000)}&to=${iso(to)}`,
    `/api/v1/pnl?from=${iso(to - 366 * 86_400_000)}&to=${iso(to)}`,
    '/api/v1/ledger?limit=20',
    botId ? `/api/v1/bots/${botId}/trades?limit=20` : '/api/v1/ledger',
    botId ? `/api/v1/bots/${botId}/day-usage` : '/api/v1/ledger',
    '/api/v1/reconciliation',
  ];
  let step = index;
  while (Date.now() < deadline) {
    const started = Date.now();
    try {
      const response = await fetch(`${API}${actions[step % actions.length]}`, {
        headers: { Authorization: `Bearer ${person.token}` },
      });
      if (response.status === 401) {
        state.reauthenticated += 1;
        await signIn(person);
      } else if (response.status !== 200) {
        state.errors += 1;
        console.error(`user ${index}: status ${response.status} on ${actions[step % actions.length].split('?')[0]}`);
      }
    } catch (failure) {
      state.errors += 1;
      console.error(`user ${index}: ${failure.name}`);
    }
    step += 1;
    await new Promise((resolve) => setTimeout(resolve, Math.max(0, 2_000 - (Date.now() - started))));
  }
}

/** p95 of a plain Micrometer timer histogram (no uri label). */
function timerP95(metrics, name) {
  const buckets = [];
  for (const line of metrics.split('\n')) {
    const m = new RegExp(`^${name}_bucket\\{[^}]*le="([^"]+)"[^}]*\\} (\\S+)$`).exec(line);
    if (m) buckets.push([m[1] === '+Inf' ? Infinity : Number(m[1]), Number(m[2])]);
  }
  buckets.sort((a, b) => a[0] - b[0]);
  const total = buckets.at(-1)?.[1] ?? 0;
  if (total === 0) return null;
  return buckets.find(([, count]) => count >= total * 0.95)?.[0] ?? null;
}

/** NFR2.2: the plans of the P&L, ledger and balance queries never read ledger_entry or fill sequentially. */
async function u7Plans() {
  if (!process.env.POSTGRES_CONTAINER) return { checked: false, seqScan: [] };
  const queries = {
    balance:
      "select coalesce(sum(amount), 0) from ledger_entry where bot_id = (select bot_id from paper_account limit 1) and mode = 'PAPER'",
    ledger:
      "select * from ledger_entry e where e.owner_user_id = (select owner_user_id from paper_account limit 1) and e.mode = 'PAPER' order by e.created_at desc, e.entry_id desc limit 21",
    pnl:
      "select f.order_id from fill f where f.owner_user_id = (select owner_user_id from paper_account limit 1) and f.mode = 'PAPER' and f.filled_at >= now() - interval '1 day' and f.filled_at < now()",
  };
  const seqScan = [];
  for (const [name, sql] of Object.entries(queries)) {
    const { stdout } = await run('docker', [
      'exec',
      process.env.POSTGRES_CONTAINER,
      'psql',
      '-U',
      process.env.DB_SUPERUSER ?? 'postgres',
      '-d',
      process.env.DB_NAME ?? 'memetrade',
      '-c',
      `EXPLAIN ${sql}`,
    ]);
    if (/Seq Scan on (ledger_entry|fill)\b/.test(stdout)) seqScan.push(name);
  }
  return { checked: true, seqScan };
}

/** NFR3.5 and NFR3.6: the durations of the marking, pending close and reconciliation rounds written in the api log. */
async function jobRounds() {
  if (!process.env.API_CONTAINER) return { marking: [], pendingClose: [], reconciliation: [] };
  const { stdout, stderr } = await run('docker', ['logs', process.env.API_CONTAINER], { maxBuffer: 512 * 1024 * 1024 });
  const durations = (pattern) =>
    [...`${stdout}\n${stderr}`.matchAll(new RegExp(`${pattern} round finished:[^"]*durationMs=(\\d+)`, 'g'))].map(
      (m) => Number(m[1]),
    );
  return {
    marking: durations('Equity marking'),
    pendingClose: durations('Pending close'),
    reconciliation: durations('Reconciliation'),
  };
}

/** NFR3.6: "check now" of every person, one request each, timed here; a 401 signs the person in again first. */
async function checkNow(people) {
  const seconds = [];
  let failures = 0;
  let divergent = 0;
  for (const person of people) {
    const call = () =>
      fetch(`${API}/api/v1/reconciliation/run`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${person.token}`, 'Idempotency-Key': randomUUID() },
      });
    let started = performance.now();
    let response = await call();
    if (response.status === 401) {
      await signIn(person);
      started = performance.now();
      response = await call();
    }
    const elapsed = (performance.now() - started) / 1000;
    const body = await response.json().catch(() => ({}));
    if (response.status !== 200) {
      failures += 1;
      console.error(`check now: status ${response.status}`);
      continue;
    }
    seconds.push(elapsed);
    if (body.status !== 'RECONCILED') {
      divergent += 1;
      console.error(`check now: status ${body.status}`);
    }
  }
  return { seconds, failures, divergent };
}

function u7Section(report) {
  const rows = Object.entries(report.p95Seconds ?? {})
    .map(([route, value]) => `| ${route} | ${value === null ? 'sem dados' : `${Math.round(value * 1000)} ms`} | ${U7_LIMITS_S[route] * 1000} ms |`)
    .join('\n');
  const rounds = report.rounds;
  const jobs =
    rounds && report.scenario === 'u7-jobs'
      ? `\n- Rodadas de marcação: ${rounds.marking.length}, maior ${Math.max(0, ...rounds.marking)} ms (limite 10000 ms)\n- Rodadas de fechamento pendente: ${rounds.pendingClose.length}, maior ${Math.max(0, ...rounds.pendingClose)} ms (limite 10000 ms)`
      : '';
  const reconciliation = rounds
    ? `\n- Rodadas de reconciliação (NFR3.6): ${rounds.reconciliation.length}, durações ${rounds.reconciliation.join(', ') || 'nenhuma'} ms, maior ${Math.max(0, ...rounds.reconciliation)} ms (limite ${RECONCILIATION_ROUND_LIMIT_MS} ms)`
    : '';
  const closing =
    report.scenario === 'u7'
      ? `\n- Bots encerrados com \`closePositions\` (NFR3.1): ${report.driver.closes ?? 0} de ${report.driver.closeBots ?? 0} (falhas: ${report.driver.closeFailures ?? 0})`
      : '';
  const checked = report.checkNow
    ? `\n- "Verificar agora" (NFR3.6): ${report.checkNow.seconds.length} pessoas, maior ${Math.round(Math.max(0, ...report.checkNow.seconds) * 1000)} ms (limite 5000 ms), falhas ${report.checkNow.failures}, divergentes ${report.checkNow.divergent}`
    : '';
  const prepared = report.preparation;
  return `## Cenário \`${report.scenario}\`

- Pessoas: ${prepared.peopleExpected} · bots: ${prepared.bots} · preenchimentos gravados antes da medição: ${prepared.fillsRecorded} · preparação: ${report.preparationSeconds} s
- Usuários lendo as rotas C5: ${report.users} · duração da medição: ${report.durationSeconds} s
- Ciclos do gerador ao fim: ${report.driver.cycles} (falhas técnicas: ${report.driver.cycleFailures})
- Resultado: **${report.passed ? 'aprovado' : 'reprovado'}**

| Medida | p95 | Limite |
|---|---|---|
${rows}
${jobs}${closing}${reconciliation}${checked}
- Planos sem \`Seq Scan\` em \`ledger_entry\` ou \`fill\`: ${report.plans.checked ? (report.plans.seqScan.length === 0 ? 'sim' : `não (${report.plans.seqScan.join(', ')})`) : 'não conferido neste cenário'}
- Espera do pool: máximo de ${report.pool.maxPending} conexões pendentes; maior sequência ${report.pool.longestPendingSeconds} s (limite 10 s)
- Memória máxima: api ${Math.round(report.maxMemoryMb.api ?? 0)} MB (limite ${u7MemoryLimitMb('api')} MB), postgres ${Math.round(report.maxMemoryMb.postgres ?? 0)} MB (limite ${u7MemoryLimitMb('postgres')} MB)
- Erros de cliente: ${report.errors} · novos logins por token vencido: ${report.reauthenticated}
- Falhas: ${report.failures.length === 0 ? 'nenhuma' : report.failures.join(', ')}
`;
}

/** Both scenarios share docs/perf/u7-ledger-pnl-<date>.md; each one replaces only its own marked section. */
function writeU7Report(report) {
  const path = `docs/perf/u7-ledger-pnl-${report.date}.md`;
  mkdirSync('docs/perf', { recursive: true });
  let text;
  try {
    text = readFileSync(path, 'utf8');
  } catch (missing) {
    if (missing.code !== 'ENOENT') throw missing;
    text = `# Carga local — U7 Ledger e P&L (${report.date})\n\nGerado por \`scripts/load-test.sh\` (\`LOAD_SCENARIO=u7\` e \`u7-jobs\`).\n`;
  }
  const begin = `<!-- section:${report.scenario} -->`;
  const end = `<!-- /section:${report.scenario} -->`;
  const section = `${begin}\n${u7Section(report)}${end}\n`;
  const at = text.indexOf(begin);
  text = at >= 0 ? text.slice(0, at) + section + text.slice(text.indexOf(end) + end.length + 1) : `${text}\n${section}`;
  writeFileSync(path, text);
}

async function runU7() {
  const preparationStarted = Date.now();
  const people = await u7People();
  const preparation = await waitForPreparation();
  const preparationSeconds = Math.round((Date.now() - preparationStarted) / 1_000);
  for (const person of people) {
    await signIn(person);
    const ledger = await (
      await fetch(`${API}/api/v1/ledger?limit=1`, { headers: { Authorization: `Bearer ${person.token}` } })
    ).json();
    person.botId = ledger.items?.[0]?.botId;
  }
  const state = { errors: 0, reauthenticated: 0 };
  const deadline = Date.now() + DURATION_S * 1_000;
  const pool = { maxPending: 0, longestPendingSeconds: 0, poolReadErrors: 0 };
  const memory = { maxMemoryMb: {} };
  const users = SCENARIO === 'u7' ? people.length : 0;
  await Promise.all([
    ...people.slice(0, users).map((person, i) => u7User(i, deadline, person, person.botId, state)),
    watchPool(deadline, pool),
    watchMemory(deadline, memory),
    new Promise((resolve) => setTimeout(resolve, Math.max(0, deadline - Date.now()))),
  ]);
  const checked = SCENARIO === 'u7' ? await checkNow(people) : undefined;
  const metrics = await (await fetch(`${MANAGEMENT}/actuator/prometheus`)).text();
  const health = await (await fetch(`${MANAGEMENT}/actuator/health`)).json().catch(() => ({}));
  const report = {
    scenario: SCENARIO,
    date: new Date().toISOString().slice(0, 10),
    users,
    durationSeconds: DURATION_S,
    preparation,
    preparationSeconds,
    driver: health.components?.loadtestLedger?.details ?? {},
    errors: state.errors,
    reauthenticated: state.reauthenticated,
    p95Seconds: {
      execute: timerP95(metrics, 'loadtest_paper_execute_seconds'),
      ...(SCENARIO === 'u7'
        ? {
            close: timerP95(metrics, 'loadtest_paper_close_seconds'),
            ...Object.fromEntries(Object.entries(U7_ROUTES).map(([name, uri]) => [name, p95(metrics, uri)])),
            checkNow: percentile95(checked.seconds),
          }
        : {}),
    },
    checkNow: checked,
    plans: SCENARIO === 'u7' ? await u7Plans() : { checked: false, seqScan: [] },
    rounds: await jobRounds(),
    pool,
    maxMemoryMb: memory.maxMemoryMb,
  };
  const slowRounds =
    SCENARIO === 'u7-jobs'
      ? [...report.rounds.marking, ...report.rounds.pendingClose].filter((ms) => ms > 10_000).length
      : 0;
  const slowReconciliation = report.rounds.reconciliation.filter((ms) => ms > RECONCILIATION_ROUND_LIMIT_MS).length;
  report.failures = [
    ...Object.entries(report.p95Seconds)
      .filter(([name, value]) => value === null || value > U7_LIMITS_S[name])
      .map(([name]) => `p95 ${name}`),
    ...(report.plans.seqScan.length > 0 ? ['query plan'] : []),
    ...(SCENARIO === 'u7-jobs' && report.rounds.marking.length < 60 ? ['fewer than 60 marking rounds'] : []),
    ...(slowRounds > 0 ? [`${slowRounds} job rounds above 10 s`] : []),
    ...(SCENARIO === 'u7' && report.rounds.reconciliation.length === 0 ? ['no reconciliation round'] : []),
    ...(slowReconciliation > 0 ? [`${slowReconciliation} reconciliation rounds above 60 s`] : []),
    ...(checked && checked.failures > 0 ? ['check now failures'] : []),
    ...(checked && checked.divergent > 0 ? ['check now divergent'] : []),
    ...(state.errors > 0 ? ['client errors'] : []),
    ...(Number(report.driver.cycleFailures ?? 0) > 0 ? ['cycle failures'] : []),
    ...(Number(report.driver.closeFailures ?? 0) > 0 ? ['close failures'] : []),
    ...(pool.longestPendingSeconds > 10 ? ['pool wait'] : []),
    ...Object.entries(memory.maxMemoryMb)
      .filter(([name, mb]) => mb > u7MemoryLimitMb(name))
      .map(([name]) => `memory ${name}`),
  ];
  report.passed = report.failures.length === 0;
  writeFileSync('load-test-report.json', JSON.stringify(report, null, 2));
  writeU7Report(report);
  console.warn(JSON.stringify(report));
  process.exit(report.passed ? 0 : 1);
}

if (SCENARIO === 'u7' || SCENARIO === 'u7-jobs') {
  await runU7();
}

const state = { token: await login(), errors: 0, mints: new Set() };
const deadline = Date.now() + DURATION_S * 1_000;
const pool = { maxPending: 0, longestPendingSeconds: 0, poolReadErrors: 0 };
const memory = { maxMemoryMb: {} };
await Promise.all([
  ...Array.from({ length: USERS }, (_, i) => user(i, deadline, state)),
  watchPool(deadline, pool),
  watchMemory(deadline, memory),
]);

const metrics = await (await fetch(`${MANAGEMENT}/actuator/prometheus`)).text();
const plan = await historyPlan().catch((failure) => `plan failed: ${failure.name}`);
const report = {
  scenario: SCENARIO,
  date: new Date().toISOString().slice(0, 10),
  users: USERS,
  durationSeconds: DURATION_S,
  tokensPerSource: Number(process.env.MARKET_SIMULATED_TOKENS_PER_SOURCE ?? 200),
  errors: state.errors,
  p95Seconds: {
    challenge: p95(metrics, '/api/v1/auth/challenge'),
    verify: p95(metrics, '/api/v1/auth/verify'),
    list: p95(metrics, '/api/v1/scanner/tokens'),
    ...(SCENARIO === 'u1' ? {} : { detail: p95(metrics, '/api/v1/tokens/{chain}/{mint}/assessment') }),
  },
  pool,
  maxMemoryMb: memory.maxMemoryMb,
  historyPlanWithoutSeqScan: !/Seq Scan on observation_sample/.test(plan),
};
const failures = [
  ...Object.entries(report.p95Seconds)
    .filter(([route, value]) => value === null || value > LIMITS_S[route])
    .map(([route]) => `p95 ${route}`),
  ...(pool.longestPendingSeconds > 10 ? ['pool wait'] : []),
  ...Object.entries(memory.maxMemoryMb)
    .filter(([, mb]) => mb > MEMORY_LIMIT_MB)
    .map(([name]) => `memory ${name}`),
  ...(report.historyPlanWithoutSeqScan ? [] : ['history plan']),
];
report.failures = failures;
report.passed = failures.length === 0;
writeFileSync('load-test-report.json', JSON.stringify(report, null, 2));
if (SCENARIO !== 'u1') {
  mkdirSync('docs/perf', { recursive: true });
  writeFileSync(`docs/perf/u4-discovery-${report.date}${SCENARIO === 'growth' ? '-growth' : ''}.md`, markdown(report));
}
console.warn(JSON.stringify(report));
process.exit(FAILS && failures.length > 0 ? 1 : 0);
