// Load test of U1 (NFR3.1): 20 virtual users, one action every 2 s, for DURATION_S seconds (default 300). Actions
// alternate between a login challenge (synthetic random addresses), the scanner list and, for the smoke wallet, a
// full verification. At the end the server-side p95 of challenge, verify and list is read from the Prometheus
// histogram on the management port; any p95 above 300 ms fails the run. Never targets anything but localhost.
import { createPrivateKey, randomBytes, randomUUID, sign } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

const API = process.env.API_BASE_URL ?? 'http://127.0.0.1:8080';
const MANAGEMENT = process.env.MANAGEMENT_URL ?? 'http://127.0.0.1:8081';
const USERS = Number(process.env.USERS ?? 20);
const DURATION_S = Number(process.env.DURATION_S ?? 300);
const P95_LIMIT_S = 0.3;
const ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';

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

async function user(index, deadline, state) {
  let step = index;
  while (Date.now() < deadline) {
    const started = Date.now();
    try {
      if (step % 3 === 0) {
        await post('/api/v1/auth/challenge', { walletAddress: base58(randomBytes(32)) });
      } else if (step % 10 === 1 && index === 0) {
        state.token = await login();
      } else {
        await fetch(`${API}/api/v1/scanner/tokens?limit=50`, { headers: { Authorization: `Bearer ${state.token}` } });
      }
    } catch (failure) {
      state.errors += 1;
      console.error(`user ${index}: ${failure.name}`);
    }
    step += 1;
    await new Promise((resolve) => setTimeout(resolve, Math.max(0, 2_000 - (Date.now() - started))));
  }
}

function p95(metrics, uri) {
  const pattern = new RegExp(
    `^http_server_requests_seconds_bucket\\{[^}]*le="([^"]+)"[^}]*uri="${uri.replace(/\//g, '\\/')}"[^}]*\\} (\\S+)$|` +
      `^http_server_requests_seconds_bucket\\{[^}]*uri="${uri.replace(/\//g, '\\/')}"[^}]*le="([^"]+)"[^}]*\\} (\\S+)$`,
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

const state = { token: await login(), errors: 0 };
const deadline = Date.now() + DURATION_S * 1_000;
await Promise.all(Array.from({ length: USERS }, (_, i) => user(i, deadline, state)));

const metrics = await (await fetch(`${MANAGEMENT}/actuator/prometheus`)).text();
const report = {
  users: USERS,
  durationSeconds: DURATION_S,
  errors: state.errors,
  p95Seconds: {
    challenge: p95(metrics, '/api/v1/auth/challenge'),
    verify: p95(metrics, '/api/v1/auth/verify'),
    list: p95(metrics, '/api/v1/scanner/tokens'),
  },
};
writeFileSync('load-test-report.json', JSON.stringify(report, null, 2));
console.warn(JSON.stringify(report));
const failed = Object.values(report.p95Seconds).some((value) => value === null || value > P95_LIMIT_S);
process.exit(failed ? 1 : 0);
