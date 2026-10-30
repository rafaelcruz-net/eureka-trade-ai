import { expect, test } from '@playwright/test';
import { waitForAuditAction } from '../support/audit-trail';
import { installTestWallet, loadSmokeWallet } from '../support/test-wallet';

const API = process.env.API_BASE_URL ?? 'http://localhost:8080';
const MANAGEMENT = process.env.MANAGEMENT_BASE_URL ?? 'http://localhost:8081';
const TRAIL_ROUTE = '/api/v1/admin/audit-events?from=2026-10-01T00:00:00Z&to=2026-10-02T00:00:00Z';

interface HealthBody {
  status: string;
  components: Record<string, { status: string }>;
}

/**
 * U2 smoke (BR2.1, NFR9.1): the administrator entry only arrives in U8, so the smoke checks the guest side of the
 * trail route: a guest token gets 403 FORBIDDEN_ROLE without data and leaves exactly the REQUEST_FORBIDDEN event of
 * its correlation id; the three health components of the trail are UP (relay, purge and outbox cleanup).
 */
test('U2: a guest token on the trail route is refused with 403 and audited; the trail is healthy', async ({
  page,
  request,
}) => {
  const wallet = loadSmokeWallet();
  await installTestWallet(page, wallet);
  const verified = page.waitForResponse(
    (r) => r.url().endsWith('/api/v1/auth/verify') && r.request().method() === 'POST',
  );
  await page.goto('/entrar?origem=/scanner');
  await page.getByTestId('login-connect-phantom').click();
  await page.waitForURL((url) => url.pathname === '/scanner');
  const session = (await (await verified).json()) as { accessToken: string };

  const refused = await request.get(`${API}${TRAIL_ROUTE}`, {
    headers: { Authorization: `Bearer ${session.accessToken}` },
  });
  expect(refused.status()).toBe(403);
  const problem = (await refused.json()) as Record<string, unknown>;
  expect(problem.code).toBe('FORBIDDEN_ROLE');
  expect(problem).not.toHaveProperty('items');

  const correlationId = refused.headers()['x-correlation-id'] ?? '';
  expect(await waitForAuditAction(correlationId, 'REQUEST_FORBIDDEN')).toBe(true);

  const health = (await (await request.get(`${MANAGEMENT}/actuator/health`)).json()) as HealthBody;
  for (const component of ['auditRelay', 'auditRetention', 'auditOutboxCleanup']) {
    expect(health.components[component]?.status, component).toBe('UP');
  }
});
