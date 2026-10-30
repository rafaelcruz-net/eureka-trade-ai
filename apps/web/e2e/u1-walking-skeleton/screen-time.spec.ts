import { expect, test } from '@playwright/test';
import { signInThroughTheApp, loadSmokeWallet } from '../support/test-wallet';

/**
 * NFR3.2 measured in the browser: from the request of the scanner page to the list visible with values. The 20-user
 * p95 belongs to the manual load-test workflow; here a single user must stay within the same 2 s budget.
 */
test('S3 shows the list with values within 2 seconds', async ({ page }) => {
  await signInThroughTheApp(page, loadSmokeWallet());

  const started = Date.now();
  await page.goto('/scanner');
  await expect(page.locator('[data-testid^="scanner-price-"][data-status]').first()).toBeVisible();
  const elapsed = Date.now() - started;

  test.info().annotations.push({ type: 'screen-time-ms', description: String(elapsed) });
  expect(elapsed).toBeLessThan(2_000);
});
