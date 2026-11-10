// Load scenario "data-requests" of u4-data-requests (performance-design.md NFR3.1 to NFR3.3; BR4.19;
// infrastructure-specification.md "Dados de teste"). scripts/load-test.sh brings the environment up with the profiles
// test,loadtest, 1000 synthetic requests seeded by the loadtest profile (LOADTEST_DATA_REQUESTS_SEED), one invitation per
// simulated guest (SMOKE_INVITE_CODES) and a throw-away administrator wallet (SMOKE_ADMIN_WALLET_FILE, its address in
// ADMIN_WALLET_ADDRESSES). The scenario creates each guest through the invitation, then for DURATION_S each guest creates
// requests (one per type, so the fifth would only meet the open ones) and lists its own, one action every 2 s; the
// administrator enters through the administrative entry and reads the list, the detail and the preview of the summary.
// It fails with a p95 above 500 ms on the guest routes, above 1 s on the administrative ones, or any unexpected status.
// The functions take their I/O as arguments, so scripts/load/load-test-data-requests.test.mjs runs them without a network.
// Synthetic values only; never targets anything but localhost.
import { createPrivateKey, generateKeyPairSync, randomUUID, sign } from 'node:crypto';

export const DATA_REQUEST_LIMITS_MS = { guest: 500, admin: 1000 };
export const GUEST_ROUTE = '/api/v1/privacy/requests';
export const ADMIN_ROUTE = '/api/v1/admin/privacy/requests';
export const TYPES = ['ACCESS', 'CORRECTION', 'DELETION', 'OTHER'];

const ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
const PKCS8_ED25519_PREFIX = '302e020100300506032b657004220420';

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

/** The throw-away administrator wallet of the run: {"address", "seed"} as written by SmokeWalletTool. */
export function walletFromSmokeFile(json) {
  const { address, seed } = JSON.parse(json);
  if (!address || !seed) throw new Error('the administrator wallet file has no address or seed');
  const der = Buffer.concat([Buffer.from(PKCS8_ED25519_PREFIX, 'hex'), Buffer.from(seed, 'base64')]);
  return { address, key: createPrivateKey({ key: der, format: 'der', type: 'pkcs8' }) };
}

/** Only loopback targets: the load never leaves the machine (synthetic traffic, team.md). */
export function assertLocal(base) {
  const host = new URL(base).hostname;
  if (!['127.0.0.1', 'localhost', '::1', '[::1]'].includes(host)) {
    throw new Error(`the data-requests load scenario only targets localhost, not ${host}`);
  }
}

/** p95 of client-side durations in ms (nearest rank); null without samples. */
export function percentile95(values) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * 0.95) - 1)];
}

/** Pass or fail: both p95 within their limits, samples on both sides and no unexpected status. */
export function evaluateDataRequests({ guestP95Ms, adminP95Ms, unexpected }) {
  const failures = [
    ...(guestP95Ms === null ? ['no guest sample'] : guestP95Ms > DATA_REQUEST_LIMITS_MS.guest ? ['p95 guest'] : []),
    ...(adminP95Ms === null ? ['no admin sample'] : adminP95Ms > DATA_REQUEST_LIMITS_MS.admin ? ['p95 admin'] : []),
    ...(unexpected > 0 ? [`${unexpected} unexpected statuses`] : []),
  ];
  return { failures, passed: failures.length === 0 };
}

async function call(io, method, path, { token, body } = {}) {
  const headers = { Accept: 'application/json', 'X-Correlation-Id': randomUUID() };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method === 'POST') headers['Idempotency-Key'] = randomUUID();
  const started = io.now();
  const response = await io.fetch(`${io.apiBase}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  // A test double reports its own latency, since the guests share one fake clock; a real fetch is timed here.
  const ms = typeof response.elapsedMs === 'number' ? response.elapsedMs : io.now() - started;
  const json = await response.json().catch(() => ({}));
  return { status: response.status, body: json, ms };
}

function signed(wallet, message) {
  return base58(sign(null, Buffer.from(message, 'utf8'), wallet.key));
}

/** The guest account of one invitation: challenge, then the verification with the code and the current terms. */
export async function signUp(io, wallet, code, termsVersion) {
  const challenge = await call(io, 'POST', '/api/v1/auth/challenge', { body: { walletAddress: wallet.address } });
  if (challenge.status !== 200) throw new Error(`challenge failed with ${challenge.status}`);
  const verified = await call(io, 'POST', '/api/v1/auth/verify', {
    body: {
      walletAddress: wallet.address,
      message: challenge.body.message,
      signature: signed(wallet, challenge.body.message),
      provider: 'PHANTOM',
      inviteCode: code,
      acceptedTermsVersion: termsVersion,
    },
  });
  if (verified.status !== 200) throw new Error(`account creation failed with ${verified.status}`);
  return verified.body.accessToken;
}

/** The administrator entry with the throw-away wallet of the run. */
export async function adminSignIn(io, wallet) {
  const challenge = await call(io, 'POST', '/api/v1/admin/auth/challenge', {
    body: { walletAddress: wallet.address },
  });
  if (challenge.status !== 200) throw new Error(`admin challenge failed with ${challenge.status}`);
  const verified = await call(io, 'POST', '/api/v1/admin/auth/verify', {
    body: {
      walletAddress: wallet.address,
      message: challenge.body.message,
      signature: signed(wallet, challenge.body.message),
      provider: 'PHANTOM',
    },
  });
  if (verified.status !== 200) throw new Error(`admin login failed with ${verified.status}`);
  return verified.body.accessToken;
}

/** One guest: a request of each type while there is one left, otherwise its list; one action every 2 s. */
async function guest(io, token, deadline, samples) {
  let step = 0;
  while (io.now() < deadline) {
    const type = TYPES[step];
    const result =
      type === undefined
        ? await call(io, 'GET', GUEST_ROUTE, { token })
        : await call(io, 'POST', GUEST_ROUTE, { token, body: { type, details: `Pedido sintético de carga ${step}` } });
    samples.guest.push(result.ms);
    const expected = type === undefined ? result.status === 200 : result.status === 201;
    if (!expected) samples.unexpected++;
    step++;
    await io.sleep(2000);
  }
}

/** The administrator: a page of the list, the detail of its first item (with the preview of an Access). */
async function administrator(io, token, deadline, samples) {
  while (io.now() < deadline) {
    const page = await call(io, 'GET', `${ADMIN_ROUTE}?limit=50`, { token });
    samples.admin.push(page.ms);
    if (page.status !== 200) samples.unexpected++;
    const first = page.body.items?.[0]?.requestId;
    if (first) {
      const detail = await call(io, 'GET', `${ADMIN_ROUTE}/${encodeURIComponent(first)}`, { token });
      samples.admin.push(detail.ms);
      if (detail.status !== 200) samples.unexpected++;
    }
    await io.sleep(2000);
  }
}

/**
 * The whole scenario. `io` = { fetch, now, sleep, apiBase, warn? }; `codes` one invitation per guest; returns the
 * report (no file written).
 */
export async function runDataRequests({ users = 20, durationS = 300, codes, adminWallet, io, wallets = () => newWallet() }) {
  assertLocal(io.apiBase);
  if (codes.length < users) throw new Error(`SMOKE_INVITE_CODES has ${codes.length} codes, ${users} needed`);
  const legal = await call(io, 'GET', '/api/v1/legal/current');
  if (legal.status !== 200 || !legal.body.version) throw new Error('the current terms could not be read');
  const tokens = [];
  for (let index = 0; index < users; index++) {
    tokens.push(await signUp(io, wallets(), codes[index], legal.body.version));
  }
  const adminToken = await adminSignIn(io, adminWallet);
  const samples = { guest: [], admin: [], unexpected: 0 };
  const deadline = io.now() + durationS * 1000;
  await Promise.all([
    ...tokens.map((token) => guest(io, token, deadline, samples)),
    administrator(io, adminToken, deadline, samples),
  ]);
  const guestP95Ms = percentile95(samples.guest);
  const adminP95Ms = percentile95(samples.admin);
  const result = evaluateDataRequests({ guestP95Ms, adminP95Ms, unexpected: samples.unexpected });
  return {
    scenario: 'data-requests',
    date: new Date(io.now()).toISOString().slice(0, 10),
    users,
    durationS,
    guestSamples: samples.guest.length,
    adminSamples: samples.admin.length,
    guestP95Ms,
    adminP95Ms,
    unexpected: samples.unexpected,
    limitsMs: DATA_REQUEST_LIMITS_MS,
    ...result,
  };
}

/** The report of docs/perf/u4-data-requests-<date>.md (pt-BR). */
export function dataRequestsMarkdown(report) {
  const ms = (value) => (value === null ? 'sem amostra' : `${Math.round(value)} ms`);
  return `# Carga local — U4 Pedidos de titular (${report.date})

Gerado por \`scripts/load-test.sh\` com \`LOAD_SCENARIO=data-requests\` (perfis \`test,loadtest\`, 1000 pedidos semeados).

- Convidados: ${report.users}, por ${report.durationS} s; um administrador
- Resultado: **${report.passed ? 'aprovado' : 'reprovado'}**${report.failures.length ? ` (${report.failures.join(', ')})` : ''}

| Medida | Valor | Limite |
|---|---|---|
| p95 de criar e listar (convidado, ${report.guestSamples} chamadas) | ${ms(report.guestP95Ms)} | ${report.limitsMs.guest} ms |
| p95 da lista e do detalhe com prévia (administrador, ${report.adminSamples} chamadas) | ${ms(report.adminP95Ms)} | ${report.limitsMs.admin} ms |
| Respostas inesperadas | ${report.unexpected} | 0 |
`;
}
