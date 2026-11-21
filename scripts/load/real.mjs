// Load scenario "real" of 261113-trading-real-bot (operation/performance-validation, Q1 to Q5). scripts/load-test.sh
// brings up docker-compose.yml + infra/docker-compose.test.yml + infra/docker-compose.real.yml +
// infra/docker-compose.loadtest-real.yml with the profiles test,loadtest: the local Solana validator, the kms-sim and
// the recorded Jupiter, the same stack as the e2e REAL specs. Nothing here talks to devnet, mainnet or a real KMS.
//
// Traffic (Q2, the pilot times 2): 21 bot wallets (the smoke account as the founder plus 20 invited people), 3 REAL bots
// each (one bot of the founder buys the recorded mint once), 20 people on the screen with one action every 2 s and one
// administrator, for 15 minutes; then "vender tudo e sacar" in 5 wallets at once, for 5 minutes; then a burst of 21
// withdrawals in 10 s every minute, for 5 minutes. It measures p50, p95 and p99 of every target of scripts/load/
// real-stats.mjs and the bottlenecks of Q4 (pools, person lock, TxSigner, signatures per wallet and minute, queues,
// EXPLAIN plans) and fails on any missed target, any error, any duplicate transaction or any growing queue.
//
// Every function takes its I/O as `io`, so scripts/load/load-test-real.test.mjs runs them with doubles. Lamports are
// strings on the wire and BigInt here: never a floating-point number for money (project.md).
import { createPublicKey, randomUUID, sign } from 'node:crypto';
import { adminSignIn, base58, newWallet, signUp } from './data-requests.mjs';
import {
  burstOffsets,
  evaluateReal,
  highestMs,
  histogramDelta,
  histogramOf,
  maxOf,
  phaseAt,
  poolSummary,
  queueGrowing,
  realMarkdown,
  seededRandom,
  seqScanOn,
  signingsPerWalletMinute,
  sumOf,
  summarize,
  summarizeHistogram,
  timeline,
} from './real-stats.mjs';

export { realMarkdown };

export const REAL_DEFAULTS = {
  people: 21,
  screenUsers: 20,
  botsPerPerson: 3,
  steadyS: 900,
  peakS: 300,
  burstS: 300,
  peakWallets: 5,
  burstEveryS: 60,
  burstSpreadS: 10,
  actionEveryMs: 2000,
  sampleEveryMs: 10_000,
  drainS: 120,
  // The cap of the activation (1 SOL, as the e2e) and the amounts of the run, in lamports.
  capLamports: '1000000000',
  airdropLamports: 2_000_000_000n,
  depositLamports: 500_000_000n,
  partialWithdrawalLamports: '1000000',
  seed: 261113,
};

const ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
const SENT_STATES = new Set(['SENT', 'CONFIRMED']);
const FAILED_STATES = new Set(['FAILED', 'AMBIGUOUS']);
const WORKER_RISK_JOBS = ['day', 'daily-loss', 'reservation-expiry'];

/** Only loopback targets: the load never leaves the machine of the CI (Q1: CI environment, not production). */
export function assertLocal(...bases) {
  for (const base of bases) {
    const host = new URL(base).hostname;
    if (!['127.0.0.1', 'localhost', '::1', '[::1]'].includes(host)) {
      throw new Error(`the real load scenario only targets localhost, not ${host}`);
    }
  }
}

export function decodeBase58(text) {
  let value = 0n;
  for (const char of text) {
    const digit = ALPHABET.indexOf(char);
    if (digit < 0) throw new Error('not base58');
    value = value * 58n + BigInt(digit);
  }
  const bytes = [];
  while (value > 0n) {
    bytes.unshift(Number(value & 0xffn));
    value >>= 8n;
  }
  for (const char of text) {
    if (char !== '1') break;
    bytes.unshift(0);
  }
  return Buffer.from(bytes);
}

function u64(value) {
  const buffer = Buffer.alloc(8);
  buffer.writeBigUInt64LE(value);
  return buffer;
}

/** The raw 32-byte public key of an Ed25519 key of this process (test wallets only). */
export function rawPublicKey(key) {
  const spki = createPublicKey(key).export({ format: 'der', type: 'spki' });
  return spki.subarray(spki.length - 32);
}

/**
 * The message of a legacy System transfer of `lamports` (BigInt) from `fromPublic` to `to`, as the e2e helper
 * apps/web/e2e/real-bot-wallet/local-validator.ts builds it: header 1/0/1, three keys, blockhash, one instruction.
 */
export function transferMessage(fromPublic, to, blockhash, lamports) {
  if (typeof lamports !== 'bigint' || lamports <= 0n) throw new Error('lamports must be a positive BigInt');
  return Buffer.concat([
    Buffer.from([1, 0, 1, 3]),
    Buffer.from(fromPublic),
    decodeBase58(to),
    Buffer.alloc(32),
    decodeBase58(blockhash),
    Buffer.from([1, 2, 2, 0, 1, 12]),
    Buffer.from([2, 0, 0, 0]),
    u64(lamports),
  ]);
}

async function waitFinalized(io, signature, timeoutMs = 90_000) {
  const deadline = io.now() + timeoutMs;
  while (io.now() < deadline) {
    const status = await io.rpc('getSignatureStatuses', [[signature]]);
    if (status.value[0]?.confirmationStatus === 'finalized') return io.now();
    await io.sleep(1000);
  }
  throw new Error('a test transfer was not finalized in time');
}

/** Airdrop of the local validator to a test main wallet, waited until finalized. */
export async function airdrop(io, address, lamports) {
  return waitFinalized(io, await io.rpc('requestAirdrop', [address, Number(lamports)]));
}

/** A SOL transfer signed in this process by a test wallet; returns the instant it was finalized. */
export async function transfer(io, wallet, to, lamports) {
  const { value } = await io.rpc('getLatestBlockhash', [{ commitment: 'finalized' }]);
  const message = transferMessage(rawPublicKey(wallet.key), to, value.blockhash, lamports);
  const signature = sign(null, message, wallet.key);
  const wire = Buffer.concat([Buffer.from([1]), signature, message]);
  const sent = await io.rpc('sendTransaction', [wire.toString('base64'), { encoding: 'base64' }]);
  return waitFinalized(io, sent);
}

/** Collects client samples and the unexpected answers of the run, tagged by the phase of the instant. */
export function newSamples() {
  return {
    walletReads: [],
    withdrawalPost: [],
    withdrawalToSend: [],
    burstWithdrawalToSend: [],
    activation: [],
    depositVisible: [],
    ordersAndPnl: [],
    adminRoutes: [],
    other: [],
    liquidationPost: [],
    byPhase: {},
    unexpected: {},
  };
}

function record(samples, metric, ms, phase) {
  samples[metric].push(ms);
  if (phase) {
    samples.byPhase[phase] ??= {};
    (samples.byPhase[phase][metric] ??= []).push(ms);
  }
}

function unexpected(samples, route, status) {
  const key = `${route} ${status}`;
  samples.unexpected[key] = (samples.unexpected[key] ?? 0) + 1;
}

function signed(wallet, message) {
  return base58(sign(null, Buffer.from(message, 'utf8'), wallet.key));
}

/** A person signs in again with its wallet (the access token lasts 15 minutes, the run 25). */
export async function signIn(io, person) {
  const challenge = await raw(io, 'POST', '/api/v1/auth/challenge', { body: { walletAddress: person.wallet.address } });
  if (challenge.status !== 200) throw new Error(`sign-in challenge failed with ${challenge.status}`);
  const verified = await raw(io, 'POST', '/api/v1/auth/verify', {
    body: {
      walletAddress: person.wallet.address,
      message: challenge.body.message,
      signature: signed(person.wallet, challenge.body.message),
      provider: 'PHANTOM',
    },
  });
  if (verified.status !== 200) throw new Error(`sign-in failed with ${verified.status}`);
  person.token = verified.body.accessToken;
  return person;
}

async function raw(io, method, path, { token, body, key } = {}) {
  const headers = { Accept: 'application/json', 'X-Correlation-Id': randomUUID() };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET') headers['Idempotency-Key'] = key ?? randomUUID();
  const started = io.now();
  const response = await io.fetch(`${io.apiBase}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const ms = typeof response.elapsedMs === 'number' ? response.elapsedMs : io.now() - started;
  const json = await response.json().catch(() => ({}));
  return { status: response.status, body: json, ms };
}

/** A call of a person; a 401 (expired token) signs the person in again once and repeats with the same key. */
export async function call(io, person, method, path, { body, key } = {}) {
  const idempotencyKey = key ?? (method === 'GET' ? undefined : randomUUID());
  const first = await raw(io, method, path, { token: person.token, body, key: idempotencyKey });
  if (first.status !== 401) return first;
  if (person.reauth) await person.reauth();
  else await signIn(io, person);
  return raw(io, method, path, { token: person.token, body, key: idempotencyKey });
}

/** Issues a consent challenge and signs it with the main wallet of the person (test wallet of this process). */
async function consent(io, person, body) {
  const challenge = await call(io, person, 'POST', '/api/v1/real/consents/challenge', { body });
  if (challenge.status !== 201) throw new Error(`consent challenge ${body.kind} failed with ${challenge.status}`);
  return { challengeId: challenge.body.challengeId, signature: signed(person.wallet, challenge.body.message) };
}

/** U1:NFR3.4: activation of the bot wallet; the sample is the POST only (the signing is not counted). */
export async function activate(io, person, capLamports, samples) {
  const { challengeId, signature } = await consent(io, person, { kind: 'ACTIVATE', capLamports });
  const activated = await call(io, person, 'POST', '/api/v1/real/bot-wallet', { body: { challengeId, signature } });
  if (activated.status !== 201) throw new Error(`activation failed with ${activated.status}`);
  record(samples, 'activation', activated.ms, 'setup');
  person.botAddress = activated.body.address;
  return person.botAddress;
}

/** Raises the bot wallet to AUTO_FULL with the consent of the main wallet (as e2e/real-swap). */
export async function raiseMode(io, person) {
  const { challengeId, signature } = await consent(io, person, { kind: 'RAISE_MODE', mode: 'AUTO_FULL' });
  const raised = await call(io, person, 'PUT', '/api/v1/real/mode', {
    body: { mode: 'AUTO_FULL', challengeId, signature },
  });
  if (raised.status !== 200) throw new Error(`raising the mode failed with ${raised.status}`);
}

async function totalLamports(io, person) {
  const wallet = await call(io, person, 'GET', '/api/v1/real/bot-wallet');
  if (wallet.status !== 200) throw new Error(`bot wallet read failed with ${wallet.status}`);
  return BigInt(wallet.body.balance?.totalLamports ?? '0');
}

/**
 * U1:NFR3.5: deposits `lamports` from the main wallet and records how long the balance takes to show it after the
 * transfer is finalized. `fundMain` airdrops to the main wallet first (the setup; the re-deposits after the peak use
 * what the liquidation returned).
 */
export async function deposit(io, person, lamports, samples, phase, { fundMain = false, timeoutMs = 120_000 } = {}) {
  if (fundMain) await airdrop(io, person.wallet.address, REAL_DEFAULTS.airdropLamports);
  const before = await totalLamports(io, person);
  const finalizedAt = await transfer(io, person.wallet, person.botAddress, lamports);
  const deadline = finalizedAt + timeoutMs;
  while (io.now() < deadline) {
    if ((await totalLamports(io, person)) >= before + lamports) {
      record(samples, 'depositVisible', io.now() - finalizedAt, phase);
      return;
    }
    await io.sleep(1000);
  }
  throw new Error('a deposit did not show in the balance in time');
}

/** The REAL bots of a person, made as the wizard makes them (e2e/real-swap): the first selects `mint` when given. */
export async function createBots(io, person, count, mint) {
  person.bots = [];
  const templates = await call(io, person, 'GET', '/api/v1/strategy-templates');
  const template = Array.isArray(templates.body) ? templates.body.find((t) => t.templateId === 'MOMENTUM_VOLUME') : null;
  if (!template) throw new Error('the MOMENTUM_VOLUME template is missing');
  const parameters = Object.fromEntries(template.parameters.map((p) => [p.key, p.default]));
  for (let n = 1; n <= count; n += 1) {
    const created = await call(io, person, 'POST', '/api/v1/bots', {
      body: { templateId: 'MOMENTUM_VOLUME', name: `Carga REAL ${n}`, market: 'REAL' },
    });
    if (created.status !== 201) throw new Error(`REAL bot creation failed with ${created.status}`);
    const botId = created.body.botId;
    const selection =
      n === 1 && mint
        ? { tokens: [{ chain: 'solana', mint }], rules: [] }
        : { tokens: [], rules: [{ field: 'LIQUIDITY', operator: 'GTE', value: '1000000' }] };
    const steps = [
      { step: 1, parameters, tokenSelection: selection },
      {
        step: 2,
        virtualBalance: '1000.000000',
        limits: {
          maxOrderSize: '100.000000',
          maxTokenExposure: '200.000000',
          maxTotalExposure: '500.000000',
          maxDailyLoss: '50.000000',
          maxDrawdown: '10.0000',
          maxSlippage: '2.0000',
          minLiquidity: '10000.000000',
          minIntervalSeconds: 240,
        },
      },
      { step: 3 },
      { step: 4 },
    ];
    for (const step of steps) {
      const saved = await call(io, person, 'PUT', `/api/v1/bots/${botId}/draft`, { body: step });
      if (saved.status !== 200) throw new Error(`REAL bot draft step ${step.step} failed with ${saved.status}`);
    }
    const started = await call(io, person, 'POST', `/api/v1/bots/${botId}/commands/start`);
    if (started.status !== 200) throw new Error(`REAL bot start failed with ${started.status}`);
    person.bots.push(botId);
  }
}

/** The actions of a person on the screen, in turn (Q2: one every 2 s). */
export const SCREEN_ACTIONS = [
  { metric: 'walletReads', method: 'GET', path: '/api/v1/real/bot-wallet', expect: 200 },
  { metric: 'walletReads', method: 'GET', path: '/api/v1/real/bot-wallet/deposits', expect: 200 },
  { metric: 'ordersAndPnl', method: 'GET', path: '/api/v1/real/orders', expect: 200 },
  { metric: 'walletReads', method: 'GET', path: '/api/v1/real/bot-wallet/withdrawals', expect: 200 },
  { metric: 'ordersAndPnl', method: 'GET', path: '/api/v1/real/pnl', expect: 200 },
  // A consent challenge that is never signed (U1:NFR3.1 counts the challenge): RAISE_CAP above the cap of 1 SOL.
  {
    metric: 'walletReads',
    method: 'POST',
    path: '/api/v1/real/consents/challenge',
    body: { kind: 'RAISE_CAP', capLamports: '2000000000' },
    expect: 201,
  },
  { metric: 'other', method: 'GET', path: '/api/v1/real/release', expect: 200 },
];

/** One person on the screen until `untilMs`; `phaseOf(now)` names the phase of each sample. */
export async function screenUser(io, person, untilMs, samples, phaseOf, offset = 0) {
  let step = offset;
  while (io.now() < untilMs) {
    const started = io.now();
    const action = SCREEN_ACTIONS[step % SCREEN_ACTIONS.length];
    try {
      const result = await call(io, person, action.method, action.path, { body: action.body });
      record(samples, action.metric, result.ms, phaseOf(started));
      if (result.status !== action.expect) unexpected(samples, action.path, result.status);
    } catch (failure) {
      unexpected(samples, action.path, failure.name);
    }
    step += 1;
    await io.sleep(Math.max(0, REAL_DEFAULTS.actionEveryMs - (io.now() - started)));
  }
}

/** The administrator (U5-NFR3.4): a page of 50 people and the view of the first one, every 2 s. */
export async function administrator(io, admin, untilMs, samples, phaseOf) {
  while (io.now() < untilMs) {
    const started = io.now();
    try {
      const page = await call(io, admin, 'GET', '/api/v1/admin/real/users?limit=50');
      record(samples, 'adminRoutes', page.ms, phaseOf(started));
      if (page.status !== 200) unexpected(samples, '/api/v1/admin/real/users', page.status);
      const first = page.body.items?.[0]?.userId;
      if (first) {
        const view = await call(io, admin, 'GET', `/api/v1/admin/real/users/${encodeURIComponent(first)}`);
        record(samples, 'adminRoutes', view.ms, phaseOf(io.now()));
        if (view.status !== 200) unexpected(samples, '/api/v1/admin/real/users/{userId}', view.status);
      }
    } catch (failure) {
      unexpected(samples, '/api/v1/admin/real/users', failure.name);
    }
    await io.sleep(Math.max(0, REAL_DEFAULTS.actionEveryMs - (io.now() - started)));
  }
}

/**
 * U1:NFR3.3: from the request of a withdrawal (client instant before the POST) to its first SENT or CONFIRMED read,
 * polled every 250 ms for 30 s (so the sample is at most 250 ms above the truth, never below), then every 2 s until
 * CONFIRMED. A FAILED or AMBIGUOUS withdrawal is an error. Returns the ms to the send, or null.
 */
export async function trackWithdrawal(io, person, withdrawalId, requestedAt, samples, { burst = false } = {}) {
  const path = `/api/v1/real/bot-wallet/withdrawals/${encodeURIComponent(withdrawalId)}`;
  let toSend = null;
  const deadline = requestedAt + 150_000;
  while (io.now() < deadline) {
    const started = io.now();
    const read = await call(io, person, 'GET', path);
    record(samples, 'walletReads', read.ms, null);
    if (read.status !== 200) {
      unexpected(samples, '/api/v1/real/bot-wallet/withdrawals/{withdrawalId}', read.status);
    } else if (FAILED_STATES.has(read.body.state)) {
      unexpected(samples, 'withdrawal', read.body.state);
      return toSend;
    } else if (SENT_STATES.has(read.body.state) && toSend === null) {
      toSend = started - requestedAt;
      record(samples, 'withdrawalToSend', toSend, 'burst');
      if (burst) record(samples, 'burstWithdrawalToSend', toSend, 'burst');
    }
    if (read.body.state === 'CONFIRMED') return toSend;
    await io.sleep(toSend === null && io.now() - requestedAt < 30_000 ? 250 : 2000);
  }
  if (toSend === null) unexpected(samples, 'withdrawal', 'NOT_SENT');
  else unexpected(samples, 'withdrawal', 'NOT_CONFIRMED');
  return toSend;
}

/**
 * One burst (U1:NFR3.2, NFR3.3, NFR3.8): one partial withdrawal per wallet, spread over `spreadMs` in a seeded order;
 * the first request is repeated with the same Idempotency-Key and must return the same withdrawal (no second one).
 * `ledger` counts the distinct requests and the replays that came back different.
 */
export async function burst(io, people, spreadMs, random, samples, ledger) {
  const startedAt = io.now();
  const offsets = burstOffsets(people.length, spreadMs, random);
  const tracked = await Promise.all(
    offsets.map(async ({ index, offsetMs }, position) => {
      await io.sleep(Math.max(0, startedAt + offsetMs - io.now()));
      const person = people[index];
      const key = randomUUID();
      const body = { amount: { lamports: REAL_DEFAULTS.partialWithdrawalLamports } };
      const requestedAt = io.now();
      const posted = await call(io, person, 'POST', '/api/v1/real/bot-wallet/withdrawals', { body, key });
      record(samples, 'withdrawalPost', posted.ms, 'burst');
      if (posted.status !== 201 && posted.status !== 202) {
        unexpected(samples, '/api/v1/real/bot-wallet/withdrawals', posted.status);
        return null;
      }
      ledger.requested += 1;
      if (position === 0) {
        const again = await call(io, person, 'POST', '/api/v1/real/bot-wallet/withdrawals', { body, key });
        if (again.body?.withdrawalId !== posted.body.withdrawalId) ledger.replayMismatch += 1;
      }
      return trackWithdrawal(io, person, posted.body.withdrawalId, requestedAt, samples, { burst: true });
    }),
  );
  return tracked;
}

/** "Vender tudo e sacar" of a person (peak of Q2), then waits for the bot wallet to be empty. */
export async function liquidate(io, person, samples, timeoutMs = 240_000) {
  const posted = await call(io, person, 'POST', '/api/v1/real/liquidations');
  record(samples, 'liquidationPost', posted.ms, 'peak');
  if (posted.status !== 202) {
    unexpected(samples, '/api/v1/real/liquidations', posted.status);
    return false;
  }
  const deadline = io.now() + timeoutMs;
  while (io.now() < deadline) {
    if ((await totalLamports(io, person)) === 0n) return true;
    await io.sleep(3000);
  }
  unexpected(samples, 'liquidation', 'NOT_EMPTY');
  return false;
}

/** Depths of the queues of Q4, from the database of the run (one query). */
export const QUEUES_SQL = [
  "select (select count(*) from real_decision_request where state = 'PENDING')",
  "(select count(*) from withdrawal where state in ('REQUESTED', 'SIGNED'))",
  "(select count(*) from real_order where state in ('PROPOSED', 'QUOTED', 'SIGNED', 'SENT'))",
  '(select count(*) from real_pending_txsigner_command where confirmed_at is null)',
].join(', ');
export const QUEUE_NAMES = ['decisoes_pendentes', 'saques_em_andamento', 'ordens_em_andamento', 'comandos_txsigner'];

export function parseQueues(line) {
  const values = line.trim().split('|').map(Number);
  return Object.fromEntries(QUEUE_NAMES.map((name, i) => [name, Number.isFinite(values[i]) ? values[i] : null]));
}

function pool(text, name) {
  return {
    active: sumOf(text, 'hikaricp_connections_active', { pool: name }),
    pending: sumOf(text, 'hikaricp_connections_pending', { pool: name }),
    max: maxOf(text, 'hikaricp_connections_max', { pool: name }),
  };
}

/** Every 10 s: queues, pools of the api, the worker and the TxSigner, the `_max` of the jobs and the person lock. */
export async function sampler(io, untilMs, state) {
  while (io.now() < untilMs) {
    const atMs = io.now() - state.loadStartedAt;
    try {
      const queues = parseQueues(await io.sql(QUEUES_SQL));
      for (const [name, depth] of Object.entries(queues)) (state.queues[name] ??= []).push({ atMs, depth });
      const [api, worker, signer] = await Promise.all([io.apiMetrics(), io.workerMetrics(), io.txsignerMetrics()]);
      state.pools.api.push(pool(api, 'memetrade-pool'));
      state.pools.worker.push(pool(worker, 'memetrade-worker-pool'));
      state.pools.txsigner.push(pool(signer, 'txsigner-pool'));
      (state.queues.audit_outbox ??= []).push({ atMs, depth: sumOf(api, 'audit_outbox_pending') });
      for (const text of [api, worker]) {
        state.max.reconciliationRound.push(maxOf(text, 'real_ops_job_seconds_max', { job: 'reconciliation' }));
        state.max.gatesJob.push(maxOf(text, 'real_ops_job_seconds_max', { job: 'gates' }));
        state.max.lockWait.push(maxOf(text, 'real_risk_lock_wait_seconds_max'));
        for (const job of WORKER_RISK_JOBS) state.max.riskJobs.push(maxOf(text, 'real_risk_job_seconds_max', { job }));
      }
    } catch (failure) {
      state.sampleErrors += 1;
      io.warn?.(`sample failed: ${failure.name}`);
    }
    await io.sleep(REAL_DEFAULTS.sampleEveryMs);
  }
}

function merged(texts, base, filter) {
  const total = new Map();
  for (const text of texts) {
    for (const [bound, count] of histogramOf(text, base, filter)) total.set(bound, (total.get(bound) ?? 0) + count);
  }
  return [...total.entries()].sort((a, b) => a[0] - b[0]);
}

/** Server histograms of the load window: the buckets read at the end minus those read before the load. */
export function serverWindow(before, after, base, filter = {}) {
  return histogramDelta(merged(after, base, filter), merged(before, base, filter));
}

/** The hot queries of Q4 with their parameters taken from the run (one wallet, its tenant and person). */
export function hotQueries({ wallet, tenant, user }) {
  return [
    {
      name: 'saques prontos (worker)',
      table: 'withdrawal',
      sql: "select withdrawal_id from withdrawal where state in ('REQUESTED', 'SIGNED', 'SENT', 'AMBIGUOUS') and next_attempt_at <= now() order by next_attempt_at limit 10",
    },
    {
      name: 'saques da carteira (tela)',
      table: 'withdrawal',
      sql: `select withdrawal_id, state from withdrawal where tenant_id = '${tenant}' and bot_wallet_id = '${wallet}' order by requested_at desc, withdrawal_id desc limit 20`,
    },
    {
      name: 'depósitos da carteira (tela)',
      table: 'observed_deposit',
      sql: `select tx_signature from observed_deposit where bot_wallet_id = '${wallet}' order by finalized_at desc, tx_signature desc limit 20`,
    },
    {
      name: 'saldo pelo ledger REAL',
      table: 'real_ledger_entry',
      sql: `select entry_kind, sum(lamports) from real_ledger_entry where bot_wallet_id = '${wallet}' group by entry_kind`,
    },
    {
      name: 'fila de decisões REAL',
      table: 'real_decision_request',
      sql: "select decision_request_id from real_decision_request where state = 'PENDING' order by created_at limit 10",
    },
    {
      name: 'ordens da pessoa (tela)',
      table: 'real_order',
      sql: `select real_order_id, state from real_order where user_id = '${user}' order by created_at desc limit 50`,
    },
    {
      name: 'última reconciliação da carteira',
      table: 'real_reconciliation_run',
      sql: `select started_at from real_reconciliation_run where bot_wallet_id = '${wallet}' order by started_at desc limit 1`,
    },
  ];
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** EXPLAIN of each hot query: the natural plan (ANALYZE) is recorded, and with enable_seqscan off it needs an index. */
export async function plans(io) {
  const row = (await io.sql('select bot_wallet_id, tenant_id, user_id from bot_wallet_account limit 1')).trim().split('|');
  if (!row.every((value) => UUID_PATTERN.test(value))) return [];
  const [wallet, tenant, user] = row;
  const out = [];
  for (const query of hotQueries({ wallet, tenant, user })) {
    const natural = await io.sql(`explain (analyze, buffers) ${query.sql}`);
    const forced = await io.sql(`explain ${query.sql}`, { seqScanOff: true });
    out.push({
      name: query.name,
      table: query.table,
      indexPath: !seqScanOn(forced, query.table),
      firstLine: natural.split('\n')[0]?.trim() ?? '',
      plan: natural,
    });
  }
  return out;
}

/** Errors and duplicates read from the database after the drain (`since` = ISO instant of the start of the setup). */
export async function databaseChecks(io, since) {
  const count = async (sql) => Number((await io.sql(sql)).trim() || 0);
  const errors = {
    ordensFalhas: await count(
      `select count(*) from real_order where state in ('FAILED', 'AMBIGUOUS', 'CANCELLED') and created_at >= '${since}'`,
    ),
    saquesFalhos: await count(
      `select count(*) from withdrawal where state in ('FAILED', 'AMBIGUOUS') and requested_at >= '${since}'`,
    ),
    assinaturasRecusadas: await count(
      `select count(*) from txsigner.signing_record where decision = 'REFUSED' and decided_at >= '${since}'`,
    ),
    ciclosFalhos: await count(`select count(*) from bot_cycle where status = 'FAILED' and started_at >= '${since}'`),
    divergenciasAbertas: await count(`select count(*) from real_divergence where opened_at >= '${since}'`),
    liberacoesBloqueadas: await count("select count(*) from real_release where state = 'BLOCKED'"),
  };
  const duplicates = {
    assinaturasRepetidas: await count(
      `select count(*) from (select signature from txsigner.signing_record where decision = 'SIGNED' and decided_at >= '${since}' group by signature having count(*) > 1) d`,
    ),
    transacoesDeSaqueRepetidas: await count(
      `select count(*) from (select tx_signature from withdrawal where tx_signature is not null and requested_at >= '${since}' group by tx_signature having count(*) > 1) d`,
    ),
    transacoesDeOrdemRepetidas: await count(
      `select count(*) from (select tx_signature from real_order where tx_signature is not null and created_at >= '${since}' group by tx_signature having count(*) > 1) d`,
    ),
    ordensPorDecisaoRepetidas: await count(
      `select count(*) from (select decision_request_id from real_order where decision_request_id is not null and created_at >= '${since}' group by decision_request_id having count(*) > 1) d`,
    ),
  };
  const partials = await count(`select count(*) from withdrawal where kind = 'PARTIAL' and requested_at >= '${since}'`);
  const signings = signingsPerWalletMinute(
    await io.sql(
      `select bot_wallet_address, date_trunc('minute', decided_at), count(*) from txsigner.signing_record where decision = 'SIGNED' and decided_at >= '${since}' group by 1, 2`,
    ),
  );
  return { errors, duplicates, partials, signings };
}

/** The founder (smoke account) and the 20 invited people get the REAL release and terms the test seed gives the smoke. */
export function realAccessSql(smokeAddress, addresses) {
  const list = addresses.map((a) => `'${a.replace(/[^1-9A-HJ-NP-Za-km-z]/g, '')}'`).join(', ');
  const smoke = smokeAddress.replace(/[^1-9A-HJ-NP-Za-km-z]/g, '');
  return [
    `insert into real_release (release_id, user_id, network, requested_by, requested_at, effective_at, state)
       select gen_random_uuid(), u.user_id, r.network, u.user_id, r.requested_at, r.effective_at, 'ACTIVE'
       from real_release r
       join wallet sw on sw.user_id = r.user_id and sw.address = '${smoke}'
       cross join (select u.user_id from wallet w join app_user u on u.user_id = w.user_id where w.address in (${list})) u
       where r.revoked_at is null
         and not exists (select 1 from real_release x where x.user_id = u.user_id and x.network = r.network)`,
    `insert into terms_acceptance (acceptance_id, user_id, tenant_id, terms_version, accepted_at, invite_id, source)
       select gen_random_uuid(), u.user_id, u.tenant_id, t.terms_version, now(), null, 'REACCEPTANCE'
       from terms_acceptance t
       join wallet sw on sw.user_id = t.user_id and sw.address = '${smoke}'
       cross join (select u.user_id, u.tenant_id from wallet w join app_user u on u.user_id = w.user_id where w.address in (${list})) u
       on conflict on constraint uq_terms_acceptance_user_version do nothing`,
  ];
}

async function waitForSmokeRelease(io, smokeAddress, timeoutMs = 120_000) {
  const deadline = io.now() + timeoutMs;
  const smoke = smokeAddress.replace(/[^1-9A-HJ-NP-Za-km-z]/g, '');
  while (io.now() < deadline) {
    const found = Number(
      (
        await io.sql(
          `select count(*) from real_release r join wallet w on w.user_id = r.user_id where w.address = '${smoke}' and r.state = 'ACTIVE'`,
        )
      ).trim() || 0,
    );
    if (found > 0) return;
    await io.sleep(2000);
  }
  throw new Error('the REAL test seed of the smoke account (MEMETRADE_REAL_SEED_TEST_RELEASE) did not run');
}

async function inBatches(items, size, task) {
  for (let i = 0; i < items.length; i += size) await Promise.all(items.slice(i, i + size).map(task));
}

/**
 * The whole scenario. `io` = { fetch, now, sleep, rpc, sql, apiMetrics, workerMetrics, txsignerMetrics, apiBase,
 * rpcBase, warn }; `founder` the smoke wallet ({ address, key }); `codes` one invitation per invited person; `recorded`
 * the values of infra/solana-fixtures/recorded.env. Returns the report (no file written).
 */
export async function runReal({ config = REAL_DEFAULTS, io, founder, adminWallet, codes, recorded, wallets = newWallet }) {
  assertLocal(io.apiBase, io.rpcBase);
  if (codes.length < config.people - 1) throw new Error(`SMOKE_INVITE_CODES has ${codes.length} codes, ${config.people - 1} needed`);
  if (!recorded.RECORDED_MINT || !recorded.RECORDED_BOT_WALLET) throw new Error('the recorded swap fixtures are missing');
  const setupStartedAt = new Date(io.wallNow()).toISOString();
  const samples = newSamples();

  // Setup: the founder signs in; the invited people sign up with the current terms and get the REAL release and terms.
  const legal = await raw(io, 'GET', '/api/v1/legal/current');
  if (legal.status !== 200 || !legal.body.version) throw new Error('the current terms could not be read');
  const people = [await signIn(io, { wallet: founder })];
  for (let i = 0; i < config.people - 1; i += 1) {
    const wallet = wallets();
    people.push({ wallet, token: await signUp(io, wallet, codes[i], legal.body.version) });
  }
  await waitForSmokeRelease(io, founder.address);
  for (const statement of realAccessSql(founder.address, people.slice(1).map((p) => p.wallet.address))) {
    await io.sql(statement);
  }
  // The founder activates first: the first key of the kms-sim (fixed seed) is the wallet the swap was recorded for.
  await activate(io, people[0], config.capLamports, samples);
  const setupFailures = [];
  if (people[0].botAddress !== recorded.RECORDED_BOT_WALLET) {
    setupFailures.push('a carteira do bot do fundador não é a do swap gravado (a compra e a venda gravadas não rodam)');
  }
  await Promise.all(people.slice(1).map((person) => activate(io, person, config.capLamports, samples)));
  await inBatches(people, 7, (person) => raiseMode(io, person));
  await inBatches(people, 7, (person) =>
    deposit(io, person, config.depositLamports, samples, 'setup', { fundMain: true }),
  );
  for (const [index, person] of people.entries()) {
    await createBots(io, person, config.botsPerPerson, index === 0 ? recorded.RECORDED_MINT : null);
  }
  const admin = { token: await adminSignIn(io, adminWallet) };
  admin.reauth = async () => {
    admin.token = await adminSignIn(io, adminWallet);
  };

  // The load: 25 minutes from here (Q2).
  const phases = timeline(config);
  const before = [await io.apiMetrics(), await io.workerMetrics()];
  const signerBefore = await io.txsignerMetrics();
  const state = {
    loadStartedAt: io.now(),
    queues: {},
    pools: { api: [], worker: [], txsigner: [] },
    max: { reconciliationRound: [], gatesJob: [], lockWait: [], riskJobs: [] },
    sampleErrors: 0,
  };
  const start = state.loadStartedAt;
  const endAt = start + phases.endMs;
  const phaseOf = (at) => phaseAt(phases, at - start);
  const random = seededRandom(config.seed);
  const ledger = { requested: 0, replayMismatch: 0 };
  const onScreen = people.slice(1, 1 + config.screenUsers);
  const tracked = [];
  const controller = async () => {
    await io.sleep(Math.max(0, start + phases.peak.fromMs - io.now()));
    const sellers = people.slice(0, config.peakWallets);
    await Promise.all(sellers.map((person) => liquidate(io, person, samples)));
    // The liquidated wallets get a new deposit (from what came back to the main wallet) before the burst.
    await Promise.all(sellers.map((person) => deposit(io, person, config.depositLamports, samples, 'peak')));
    for (const at of phases.bursts) {
      await io.sleep(Math.max(0, start + at - io.now()));
      tracked.push(burst(io, people, config.burstSpreadS * 1000, random, samples, ledger));
    }
  };
  await Promise.all([
    ...onScreen.map((person, i) => screenUser(io, person, endAt, samples, phaseOf, i)),
    administrator(io, admin, endAt, samples, phaseOf),
    sampler(io, endAt + config.drainS * 1000, state),
    controller(),
  ]);
  await Promise.all(tracked);
  const after = [await io.apiMetrics(), await io.workerMetrics()];
  const signerAfter = await io.txsignerMetrics();
  const checks = await databaseChecks(io, setupStartedAt);
  const hot = await plans(io);

  const window = (base, filter) => serverWindow(before, after, base, filter);
  const counterDelta = (base, filter = {}) =>
    after.reduce((t, text) => t + sumOf(text, base, filter), 0) -
    before.reduce((t, text) => t + sumOf(text, base, filter), 0);
  // The jobs are judged by their slowest run (polled `_max`); the runs come from `_count`, so the target has samples
  // even where no histogram is published, and their buckets (loadtest profile) give p50, p95 and p99.
  const job = (base, filters, maxima) => {
    const buckets = filters
      .map((filter) => window(base, filter))
      .reduce((sum, list) => (sum.length === 0 ? list : sum.map(([b, c], i) => [b, c + (list[i]?.[1] ?? 0)])), []);
    const runs = filters.reduce((t, filter) => t + counterDelta(`${base}_count`, filter), 0);
    return { ...summarizeHistogram(buckets), n: runs, max: runs > 0 ? highestMs(maxima) : null };
  };
  const measured = {
    walletReads: summarize(samples.walletReads),
    withdrawalPost: summarize(samples.withdrawalPost),
    withdrawalToSend: summarize(samples.withdrawalToSend),
    burstWithdrawalToSend: summarize(samples.burstWithdrawalToSend),
    activation: summarize(samples.activation),
    depositVisible: summarize(samples.depositVisible),
    ordersAndPnl: summarize(samples.ordersAndPnl),
    adminRoutes: summarize(samples.adminRoutes),
    riskBuy: summarizeHistogram(window('real_risk_evaluation_seconds', { side: 'BUY' })),
    riskSell: summarizeHistogram(window('real_risk_evaluation_seconds', { side: 'SELL' })),
    lockWait: job('real_risk_lock_wait_seconds', [{}], state.max.lockWait),
    riskJobs: job(
      'real_risk_job_seconds',
      WORKER_RISK_JOBS.map((name) => ({ job: name })),
      state.max.riskJobs,
    ),
    decisionToSend: summarizeHistogram(window('real_order_decision_to_send_seconds', { mode: 'AUTO_FULL' })),
    decisionQueueWait: summarizeHistogram(window('real_decision_queue_wait_seconds')),
    reconciliationWallet: summarizeHistogram(window('real_reconciliation_seconds')),
    reconciliationRound: job('real_ops_job_seconds', [{ job: 'reconciliation' }], state.max.reconciliationRound),
    gatesJob: job('real_ops_job_seconds', [{ job: 'gates' }], state.max.gatesJob),
    botCycle: summarizeHistogram(window('loadtest_bots_cycle_seconds')),
  };
  const txsigner = {};
  for (const kind of ['WITHDRAWAL', 'SWAP_BUY', 'SWAP_SELL']) {
    txsigner[kind] = summarizeHistogram(
      histogramDelta(histogramOf(signerAfter, 'txsigner_latency_seconds', { kind }), histogramOf(signerBefore, 'txsigner_latency_seconds', { kind })),
      maxOf(signerAfter, 'txsigner_latency_seconds_max', { kind }),
    );
  }
  const queues = {};
  for (const [name, list] of Object.entries(state.queues)) {
    const depths = list.map((s) => s.depth).filter((d) => d !== null);
    queues[name] = {
      start: list[0]?.depth ?? null,
      max: depths.length ? Math.max(...depths) : null,
      end: list.at(-1)?.depth ?? null,
      ...queueGrowing(list, phases.steady, list[0]?.depth ?? null, list.at(-1)?.depth ?? null),
    };
  }
  const httpUnexpected = Object.values(samples.unexpected).reduce((t, n) => t + n, 0);
  const report = {
    scenario: 'real',
    date: new Date(io.wallNow()).toISOString().slice(0, 10),
    environment: 'CI (GitHub Actions, Docker Compose, validador Solana local, kms-sim, Jupiter simulada) — não é produção',
    config: { ...config, airdropLamports: String(config.airdropLamports), depositLamports: String(config.depositLamports) },
    measured,
    byPhase: Object.fromEntries(
      Object.entries(samples.byPhase).map(([phase, metrics]) => [
        phase,
        Object.fromEntries(Object.entries(metrics).map(([metric, values]) => [metric, summarize(values)])),
      ]),
    ),
    pools: Object.fromEntries(Object.entries(state.pools).map(([name, readings]) => [name, poolSummary(readings)])),
    txsigner,
    signings: checks.signings,
    queues,
    plans: hot,
    errors: {
      respostasInesperadas: httpUnexpected,
      leiturasDeAmostraFalhas: state.sampleErrors,
      ...checks.errors,
      saquesPerdidos: Math.max(0, ledger.requested - checks.partials),
      errosDaBarreira: counterDelta('real_risk_errors_total'),
      errosDoKms: sumOf(signerAfter, 'txsigner_kms_errors_total') - sumOf(signerBefore, 'txsigner_kms_errors_total'),
      falhasDeFornecedor: counterDelta('real_upstream_failures_total'),
      errosDeCiclo: counterDelta('bots_cycle_errors_total'),
    },
    unexpected: samples.unexpected,
    duplicates: {
      ...checks.duplicates,
      saquesAlemDosPedidos: Math.max(0, checks.partials - ledger.requested),
      repeticoesComOutroSaque: ledger.replayMismatch,
    },
    withdrawalsRequested: ledger.requested,
    setupFailures,
  };
  return { ...report, ...evaluateReal(report) };
}
