import { test, type Page } from '@playwright/test';

/** One window of the per-IP login limit (BR1.11: 10 per minute), plus a margin. */
const LOGIN_LIMIT_WINDOW_MS = 65_000;

/**
 * Clicks "Conectar Phantom" on the login screen already open and waits for the scanner. The whole e2e run signs in
 * from the same IP and this smoke runs after the U1 and U2 ones, so it can meet the real login limit
 * (429 RATE_LIMITED): then it waits one limit window and tries once more. The limit itself is never raised.
 */
export async function connectAndReachScanner(page: Page): Promise<void> {
  await page.getByTestId('login-connect-phantom').click();
  const scanner = page
    .waitForURL((url) => url.pathname === '/scanner')
    .then(() => 'scanner' as const);
  const rejected = page
    .getByTestId('login-rejected-reason')
    .filter({ hasText: 'Muitas tentativas' })
    .waitFor()
    .then(() => 'rate-limited' as const);
  const outcome = await Promise.race([scanner, rejected]);
  scanner.catch(() => undefined);
  rejected.catch(() => undefined);
  if (outcome === 'scanner') {
    return;
  }
  test.info().setTimeout(test.info().timeout + LOGIN_LIMIT_WINDOW_MS);
  await page.waitForTimeout(LOGIN_LIMIT_WINDOW_MS);
  await page.goto('/entrar?origem=/scanner');
  await page.getByTestId('login-connect-phantom').click();
  await page.waitForURL((url) => url.pathname === '/scanner');
}
