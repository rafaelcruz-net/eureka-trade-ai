// Load scenario "reacceptance" of u3-reacceptance (performance-design.md NFR3.1, NFR3.2; BR3.11;
// infrastructure-specification.md "Dados de teste para a trava"). The run of scripts/load-test.sh mounts the TEST catalog
// "reviewed" (legacy v1, reviewed v2 current). The scenario creates 20 OUTDATED guests through the same SQL path as the
// e2e spec (a BETA_USER whose only acceptance is v1, with a wallet generated here) and, for each one at the same time:
// signs in, calls a locked route (403 TERMS_REACCEPTANCE_REQUIRED), accepts the current version and calls the route
// again (200). It fails with a p95 of the acceptance above 500 ms or of the lock check above 5 ms, both read from the
// Prometheus histograms of the api (http_server_requests_seconds on the acceptance route and the timer
// memetrade.terms.reacceptance.check), or when any person does not go through lock -> acceptance -> pass. The p95 of
// the wait for a connection of the api pool (hikaricp_connections_acquire_seconds, pool memetrade-pool) is reported
// next to the lock check as information only.
// The functions take their I/O as arguments, so scripts/load/load-test-reacceptance.test.mjs runs them without a
// network or a database. Synthetic values only; never targets anything but localhost.
import { generateKeyPairSync, randomUUID, sign } from 'node:crypto';

export const REACCEPTANCE_LIMITS_MS = { accept: 500, check: 5 };
export const ACCEPT_ROUTE = '/api/v1/terms/acceptances';
export const LOCKED_ROUTE = '/api/v1/bots';
export const OUTDATED_VERSION = 'v1';
export const CHECK_TIMER = 'memetrade_terms_reacceptance_check_seconds';
// Build and Test loop-back 1 (Passo 16): the wait for a connection of the api pool, read between the same two
// snapshots and reported as acquireP95Ms next to checkP95Ms. Informative only: it never fails the run.
export const ACQUIRE_TIMER = 'hikaricp_connections_acquire_seconds';
export const API_POOL = 'memetrade-pool';

const ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';

export function base58(bytes) {
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

/** A throw-away Ed25519 wallet of this process (never a real wallet; the key never leaves the process). */
export function newWallet() {
  const { privateKey, publicKey } = generateKeyPairSync('ed25519');
  const spki = publicKey.export({ format: 'der', type: 'spki' });
  return { address: base58(spki.subarray(spki.length - 32)), key: privateKey };
}

/** Only loopback targets: the load never leaves the machine (synthetic traffic, team.md). */
export function assertLocal(base) {
  const host = new URL(base).hostname;
  if (!['127.0.0.1', 'localhost', '::1', '[::1]'].includes(host)) {
    throw new Error(`the reacceptance load scenario only targets localhost, not ${host}`);
  }
}

/**
 * The SQL of one outdated guest, the same rows as e2e/reacceptance/smoke.spec.ts: tenant, BETA_USER, public wallet and
 * ONE acceptance of `version` without an invitation (source REACCEPTANCE). Parameterised; values are never inlined.
 */
export function outdatedGuestStatements(address, version = OUTDATED_VERSION, ids = {}) {
  const tenantId = ids.tenantId ?? randomUUID();
  const userId = ids.userId ?? randomUUID();
  return [
    {
      text: "insert into tenant (tenant_id, name, status, created_at) values ($1, 'Reacceptance load tenant', 'ACTIVE', now())",
      values: [tenantId],
    },
    {
      text: "insert into app_user (user_id, tenant_id, role, status, created_at) values ($1, $2, 'BETA_USER', 'ACTIVE', now())",
      values: [userId, tenantId],
    },
    {
      text: "insert into wallet (wallet_id, user_id, address, provider, verified_at) values ($1, $2, $3, 'PHANTOM', now())",
      values: [ids.walletId ?? randomUUID(), userId, address],
    },
    {
      text:
        'insert into terms_acceptance (acceptance_id, user_id, tenant_id, terms_version, accepted_at, invite_id, source)' +
        " values ($1, $2, $3, $4, now() - interval '30 days', null, 'REACCEPTANCE')",
      values: [ids.acceptanceId ?? randomUUID(), userId, tenantId, version],
    },
  ];
}

/** Cumulative bucket counts of one histogram series of the Prometheus text, by upper bound in seconds. */
export function histogram(metrics, name, labels = {}) {
  const counts = new Map();
  for (const line of metrics.split('\n')) {
    if (!line.startsWith(`${name}_bucket{`)) continue;
    const labelText = line.slice(line.indexOf('{') + 1, line.lastIndexOf('}'));
    const matches = Object.entries(labels).every(([key, value]) => labelText.includes(`${key}="${value}"`));
    if (!matches) continue;
    const le = /le="([^"]+)"/.exec(labelText)?.[1];
    if (le === undefined) continue;
    const bound = le === '+Inf' ? Infinity : Number(le);
    counts.set(bound, (counts.get(bound) ?? 0) + Number(line.split(' ').at(-1)));
  }
  return counts;
}

/** p95 in ms of the observations between two snapshots of the same histogram (upper bound of the bucket); null if none. */
export function p95BetweenMs(before, after) {
  const sorted = [...after.entries()]
    .map(([bound, count]) => [bound, count - (before.get(bound) ?? 0)])
    .sort((a, b) => a[0] - b[0]);
  const total = sorted.at(-1)?.[1] ?? 0;
  if (total === 0) return null;
  const bound = sorted.find(([, count]) => count >= total * 0.95)?.[0] ?? null;
  return bound === null ? null : bound === Infinity ? Infinity : Math.round(bound * 1000 * 1000) / 1000;
}

/** The histograms of the scenario in one Prometheus text (acquire: informative only, pool of the api). */
export function snapshot(metrics) {
  return {
    accept: histogram(metrics, 'http_server_requests_seconds', { method: 'POST', uri: ACCEPT_ROUTE }),
    check: histogram(metrics, CHECK_TIMER),
    acquire: histogram(metrics, ACQUIRE_TIMER, { pool: API_POOL }),
  };
}

/** Pass or fail: both p95 within their limits and every person went through lock -> acceptance -> pass. */
export function evaluateReacceptance({ acceptP95Ms, checkP95Ms, people }) {
  const incomplete = people.filter((person) => !(person.locked && person.accepted && person.passedAfter));
  const failures = [
    ...(acceptP95Ms === null ? ['no acceptance sample'] : acceptP95Ms > REACCEPTANCE_LIMITS_MS.accept ? ['p95 acceptance'] : []),
    ...(checkP95Ms === null ? ['no lock check sample'] : checkP95Ms > REACCEPTANCE_LIMITS_MS.check ? ['p95 lock check'] : []),
    ...(incomplete.length > 0 ? [`${incomplete.length} people without lock -> acceptance -> pass`] : []),
  ];
  return { failures, passed: failures.length === 0 };
}

async function call(io, method, path, { token, body } = {}) {
  const headers = { Accept: 'application/json', 'X-Correlation-Id': randomUUID() };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method === 'POST') headers['Idempotency-Key'] = randomUUID();
  const response = await io.fetch(`${io.apiBase}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await response.json().catch(() => ({}));
  return { status: response.status, body: json };
}

/** Challenge, signature and verification of the guest entry; returns the access token. */
export async function signIn(io, wallet) {
  const challenge = await call(io, 'POST', '/api/v1/auth/challenge', { body: { walletAddress: wallet.address } });
  if (challenge.status !== 200) throw new Error(`challenge failed with ${challenge.status}`);
  const signature = base58(sign(null, Buffer.from(challenge.body.message, 'utf8'), wallet.key));
  const verified = await call(io, 'POST', '/api/v1/auth/verify', {
    body: { walletAddress: wallet.address, message: challenge.body.message, signature, provider: 'PHANTOM' },
  });
  if (verified.status !== 200) throw new Error(`login failed with ${verified.status}`);
  return verified.body.accessToken;
}

/** One outdated guest: sign in, meet the lock, accept the current version, pass. Never throws: records the steps. */
async function person(io, wallet, currentVersion) {
  const steps = { locked: false, accepted: false, passedAfter: false, acceptClientMs: null };
  try {
    const token = await signIn(io, wallet);
    const before = await call(io, 'GET', LOCKED_ROUTE, { token });
    steps.locked = before.status === 403 && before.body.code === 'TERMS_REACCEPTANCE_REQUIRED';
    const started = io.now();
    const accepted = await call(io, 'POST', ACCEPT_ROUTE, { token, body: { version: currentVersion } });
    steps.acceptClientMs = io.now() - started;
    steps.accepted = accepted.status === 200 && accepted.body.upToDate === true;
    const after = await call(io, 'GET', LOCKED_ROUTE, { token });
    steps.passedAfter = after.status === 200;
  } catch (failure) {
    io.warn?.(`reacceptance person failed: ${failure?.message ?? 'unknown'}`);
  }
  return steps;
}

/**
 * The whole scenario. `io` = { fetch, now, apiBase, managementBase, insertOutdatedGuest(statements), warn? };
 * returns the report (no file written).
 */
export async function runReacceptance({ users = 20, io, wallets = () => newWallet() }) {
  assertLocal(io.apiBase);
  assertLocal(io.managementBase);
  const legal = await call(io, 'GET', '/api/v1/legal/current');
  const currentVersion = legal.body.version;
  if (legal.status !== 200 || !currentVersion || currentVersion === OUTDATED_VERSION) {
    throw new Error(`the catalog of the run must have a current version newer than ${OUTDATED_VERSION}`);
  }
  const guests = Array.from({ length: users }, () => wallets());
  for (const wallet of guests) {
    await io.insertOutdatedGuest(outdatedGuestStatements(wallet.address));
  }
  const before = snapshot(await (await io.fetch(`${io.managementBase}/actuator/prometheus`)).text());
  const people = await Promise.all(guests.map((wallet) => person(io, wallet, currentVersion)));
  const after = snapshot(await (await io.fetch(`${io.managementBase}/actuator/prometheus`)).text());
  const acceptP95Ms = p95BetweenMs(before.accept, after.accept);
  const checkP95Ms = p95BetweenMs(before.check, after.check);
  const acquireP95Ms = p95BetweenMs(before.acquire, after.acquire);
  const result = evaluateReacceptance({ acceptP95Ms, checkP95Ms, people });
  return {
    scenario: 'reacceptance',
    date: new Date(io.now()).toISOString().slice(0, 10),
    users,
    currentVersion,
    outdatedVersion: OUTDATED_VERSION,
    acceptP95Ms,
    checkP95Ms,
    acquireP95Ms,
    limitsMs: REACCEPTANCE_LIMITS_MS,
    locked: people.filter((p) => p.locked).length,
    accepted: people.filter((p) => p.accepted).length,
    passedAfter: people.filter((p) => p.passedAfter).length,
    ...result,
  };
}

/** The report of docs/perf/u3-reacceptance-<date>.md (pt-BR). */
export function reacceptanceMarkdown(report) {
  const ms = (value) => (value === null ? 'sem amostra' : `${value} ms`);
  return `# Carga local — U3 Novo aceite dos termos (${report.date})

Gerado por \`scripts/load-test.sh\` com \`LOAD_SCENARIO=reacceptance\` (perfis \`test,loadtest\`, catálogo de teste \`reviewed\`).

- Pessoas desatualizadas: ${report.users} (aceite só da ${report.outdatedVersion}; vigente ${report.currentVersion})
- Resultado: **${report.passed ? 'aprovado' : 'reprovado'}**${report.failures.length ? ` (${report.failures.join(', ')})` : ''}

| Medida | Valor | Limite |
|---|---|---|
| p95 de \`POST ${ACCEPT_ROUTE}\` (histograma da API) | ${ms(report.acceptP95Ms)} | ${report.limitsMs.accept} ms |
| p95 da consulta da trava (\`${CHECK_TIMER}\`) | ${ms(report.checkP95Ms)} | ${report.limitsMs.check} ms |
| p95 da espera por conexão do pool \`${API_POOL}\` (\`${ACQUIRE_TIMER}\`) | ${ms(report.acquireP95Ms ?? null)} | só informativo |
| Pessoas barradas / aceitas / liberadas | ${report.locked} / ${report.accepted} / ${report.passedAfter} | ${report.users} / ${report.users} / ${report.users} |
`;
}
