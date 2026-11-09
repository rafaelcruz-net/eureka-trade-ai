// Load scenario "legal" of u1-legal-documents (performance-design.md "Medição", NFR3.1 to NFR3.3; BR1.13): 20 virtual
// users for 5 minutes, half reading the pages /termos and /privacidade of the guest app and half reading the routes
// /api/v1/legal/** of the api, one request every 2 s each. It fails with a page p95 above 2 s, an api p95 above
// 300 ms or any 5xx. The times are measured by the client, so they cover the whole path (the page includes the server
// read of L1 by the app). Runs from the host against the environment of scripts/load-test.sh with the profiles
// test,loadtest (the per-IP limit of the legal documents is raised by application-loadtest.yml only).
// The functions take their I/O as arguments, so scripts/load/load-test-legal.test.mjs runs them without a network.

export const LEGAL_LIMITS_MS = { page: 2_000, api: 300 };
export const LEGAL_PAGES = ['/termos', '/privacidade'];
export const LEGAL_API_ROUTES = ['/api/v1/legal/current', '/api/v1/legal/versions'];
export const LEGAL_PACE_MS = 2_000;

/** Half of the users read the pages, half the api (even index: page). */
export function legalRole(index) {
  return index % 2 === 0 ? 'page' : 'api';
}

/** The path a user reads on its n-th request: alternates the two pages, or the two api routes. */
export function legalPath(role, step) {
  const paths = role === 'page' ? LEGAL_PAGES : LEGAL_API_ROUTES;
  return paths[step % paths.length];
}

/** Nearest-rank p95 in ms; null without samples. */
export function percentile95(values) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.max(0, Math.ceil(sorted.length * 0.95) - 1)];
}

/** Only loopback targets: the load never leaves the machine (synthetic traffic, team.md). */
export function assertLocal(base) {
  const host = new URL(base).hostname;
  if (!['127.0.0.1', 'localhost', '::1', '[::1]'].includes(host)) {
    throw new Error(`the legal load scenario only targets localhost, not ${host}`);
  }
}

/** Pass or fail of the samples: p95 of the pages and of the api, and the count of 5xx (and of failed requests). */
export function evaluateLegal(samples) {
  const p95PageMs = percentile95(samples.page);
  const p95ApiMs = percentile95(samples.api);
  const failures = [
    ...(p95PageMs === null ? ['no page sample'] : p95PageMs > LEGAL_LIMITS_MS.page ? ['p95 page'] : []),
    ...(p95ApiMs === null ? ['no api sample'] : p95ApiMs > LEGAL_LIMITS_MS.api ? ['p95 api'] : []),
    ...(samples.serverErrors > 0 ? ['5xx'] : []),
    ...(samples.networkErrors > 0 ? ['request failed'] : []),
    ...(samples.otherStatuses > 0 ? ['unexpected status'] : []),
  ];
  return { p95PageMs, p95ApiMs, failures, passed: failures.length === 0 };
}

/** Runs one user until the deadline, one request every LEGAL_PACE_MS. */
async function legalUser(index, deadline, io, samples) {
  const role = legalRole(index);
  const base = role === 'page' ? io.webBase : io.apiBase;
  for (let step = 0; io.now() < deadline; step++) {
    const started = io.now();
    try {
      const response = await io.fetch(`${base}${legalPath(role, step)}`, { headers: { Accept: role === 'page' ? 'text/html' : 'application/json' } });
      await response.arrayBuffer?.();
      samples[role].push(io.now() - started);
      if (response.status >= 500) samples.serverErrors += 1;
      else if (response.status !== 200) samples.otherStatuses += 1;
    } catch (failure) {
      samples.networkErrors += 1;
      io.warn?.(`legal ${role} request failed: ${failure?.name ?? 'unknown'}`);
    }
    await io.sleep(Math.max(0, LEGAL_PACE_MS - (io.now() - started)));
  }
}

/**
 * The whole scenario. `io` = { fetch, now, sleep, webBase, apiBase, warn? }; returns the report (no file written).
 */
export async function runLegal({ users = 20, durationS = 300, io }) {
  assertLocal(io.webBase);
  assertLocal(io.apiBase);
  const samples = { page: [], api: [], serverErrors: 0, otherStatuses: 0, networkErrors: 0 };
  const deadline = io.now() + durationS * 1_000;
  await Promise.all(Array.from({ length: users }, (_, index) => legalUser(index, deadline, io, samples)));
  const result = evaluateLegal(samples);
  return {
    scenario: 'legal',
    date: new Date(io.now()).toISOString().slice(0, 10),
    users,
    durationSeconds: durationS,
    requests: { page: samples.page.length, api: samples.api.length },
    serverErrors: samples.serverErrors,
    otherStatuses: samples.otherStatuses,
    networkErrors: samples.networkErrors,
    limitsMs: LEGAL_LIMITS_MS,
    ...result,
  };
}

/** The report of docs/perf/u1-legal-documents-<date>.md (pt-BR). */
export function legalMarkdown(report) {
  const ms = (value) => (value === null ? 'sem amostra' : `${value} ms`);
  return `# Carga local — U1 Documentos legais (${report.date})

Gerado por \`scripts/load-test.sh\` com \`LOAD_SCENARIO=legal\` (perfis \`test,loadtest\`).

- Usuários: ${report.users} (metade nas páginas, metade na API) · duração: ${report.durationSeconds} s
- Resultado: **${report.passed ? 'aprovado' : 'reprovado'}**${report.failures.length ? ` (${report.failures.join(', ')})` : ''}

| Medida | Valor | Limite |
|---|---|---|
| p95 das páginas \`/termos\` e \`/privacidade\` | ${ms(report.p95PageMs)} | ${report.limitsMs.page} ms |
| p95 de \`/api/v1/legal/**\` | ${ms(report.p95ApiMs)} | ${report.limitsMs.api} ms |
| Requisições (páginas / API) | ${report.requests.page} / ${report.requests.api} | — |
| Respostas 5xx | ${report.serverErrors} | 0 |
| Outros status ou falhas de rede | ${report.otherStatuses + report.networkErrors} | 0 |
`;
}
