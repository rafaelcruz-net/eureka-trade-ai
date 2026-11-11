import { expect, test, type Page } from '@playwright/test';
import { installTestWallet, loadSmokeWallet } from '../support/test-wallet';

/** One window of the per-IP login limit (BR1.11: 10 per minute), plus a margin. */
const LOGIN_LIMIT_WINDOW_MS = 65_000;

/** Opens S1, reaches "Conectar Phantom" with Tab only and presses Enter. */
async function connectByKeyboard(page: Page): Promise<void> {
  await page.goto('/entrar?origem=/scanner');
  await expect(page.getByRole('heading', { level: 1, name: 'Entrar' })).toBeFocused();
  const connect = page.getByTestId('login-connect-phantom');
  for (let i = 0; i < 10 && !(await connect.evaluate((el) => el === document.activeElement)); i++) {
    await page.keyboard.press('Tab');
  }
  await expect(connect).toBeFocused();
  await page.keyboard.press('Enter');
}

/** NFR11.1 / CT-UX-4: S1 and S3 operable with the keyboard only, with visible focus. */
test('S1 and S3 work with the keyboard only', async ({ page }) => {
  const wallet = loadSmokeWallet();
  await installTestWallet(page, wallet);

  // The whole e2e run signs in from the same IP, so this spec can meet the real login limit (429 RATE_LIMITED):
  // then it waits one limit window and tries once more by keyboard. The limit itself is never raised.
  await connectByKeyboard(page);
  const scanner = page
    .waitForURL((url) => url.pathname === '/scanner')
    .then(() => 'scanner' as const);
  const limited = page
    .getByTestId('login-rejected-reason')
    .filter({ hasText: 'Muitas tentativas' })
    .waitFor()
    .then(() => 'rate-limited' as const);
  const outcome = await Promise.race([scanner, limited]);
  scanner.catch(() => undefined);
  limited.catch(() => undefined);
  if (outcome === 'rate-limited') {
    test.info().setTimeout(test.info().timeout + LOGIN_LIMIT_WINDOW_MS);
    await page.waitForTimeout(LOGIN_LIMIT_WINDOW_MS);
    await connectByKeyboard(page);
    await page.waitForURL((url) => url.pathname === '/scanner');
  }
  await expect(page.getByTestId('scanner-table')).toBeVisible();

  const firstValue = page.locator('[data-testid^="scanner-price-"][data-status]').first();
  for (
    let i = 0;
    // U9: the shell adds the main navigation (5 links) before the content of S3.
    i < 30 && !(await firstValue.evaluate((el) => el === document.activeElement));
    i++
  ) {
    await page.keyboard.press('Tab');
  }
  await expect(firstValue).toBeFocused();
  await expect(firstValue.locator('[role="tooltip"]')).toBeVisible();
  const outline = await firstValue.evaluate((el) => getComputedStyle(el).outlineStyle);
  expect(outline).not.toBe('none');
});
