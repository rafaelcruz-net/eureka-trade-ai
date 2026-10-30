import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { waitForAuditAction } from '../support/audit-trail';
import { installTestWallet, loadSmokeWallet } from '../support/test-wallet';

const API = process.env.API_BASE_URL ?? 'http://localhost:8080';

/**
 * F7 smoke of the walking skeleton: browser → Next.js → Spring Boot → PostgreSQL with the simulated source. Signs in
 * with the test wallet, checks source, time and unknown-vs-zero on the list, then replays the verification with the
 * same nonce and expects the refusal and its LOGIN_REJECTED audit event.
 */
test('F7: login, scanner list with source, time and unknown, and nonce replay refused', async ({
  page,
  request,
}) => {
  const wallet = loadSmokeWallet();
  await installTestWallet(page, wallet);
  const verifyBody = page.waitForRequest(
    (r) => r.url().endsWith('/api/v1/auth/verify') && r.method() === 'POST',
  );

  await page.goto('/entrar?origem=/scanner');
  await page.getByTestId('login-connect-phantom').click();
  await page.waitForURL((url) => url.pathname === '/scanner');
  const replayPayload = (await verifyBody).postData() ?? '';

  await expect(page.getByTestId('scanner-table')).toBeVisible();
  await expect(page.getByTestId('scanner-source')).toContainText('simulada');

  const cells = page.locator('[data-testid^="scanner-"][data-status]');
  const known = page.locator('[data-status="KNOWN"]');
  expect(await cells.count()).toBeGreaterThan(0);
  for (const detail of await known.locator('[role="tooltip"]').all()) {
    await expect(detail).toContainText('Horário:');
  }
  const holdersUnknown = page.locator('[data-testid^="scanner-holders-"][data-status="UNKNOWN"]');
  await expect(holdersUnknown.first()).toContainText('unknown');
  const zeroLiquidity = page
    .locator('[data-testid^="scanner-liquidity-"][data-status="KNOWN"]')
    .filter({ hasText: /^0(?!,)/ });
  await expect(zeroLiquidity.first()).toBeVisible();

  const replay = await request.post(`${API}/api/v1/auth/verify`, {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
    data: replayPayload,
  });
  expect(replay.status()).toBe(401);
  const problem = await replay.json();
  expect(problem.code).toBe('LOGIN_CHALLENGE_REJECTED');
  expect(problem.reason).toBe('NONCE_ALREADY_USED');

  const correlationId = replay.headers()['x-correlation-id'] ?? '';
  expect(await waitForAuditAction(correlationId, 'LOGIN_REJECTED')).toBe(true);
});
