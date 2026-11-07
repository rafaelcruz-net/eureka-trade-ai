import { expect, test } from '@playwright/test';
import { installTestWallet, loadSmokeWallet } from '../support/test-wallet';

/** NFR11.1 / CT-UX-4: S1 and S3 operable with the keyboard only, with visible focus. */
test('S1 and S3 work with the keyboard only', async ({ page }) => {
  const wallet = loadSmokeWallet();
  await installTestWallet(page, wallet);
  await page.goto('/entrar?origem=/scanner');

  await expect(page.getByRole('heading', { level: 1, name: 'Entrar' })).toBeFocused();
  const connect = page.getByTestId('login-connect-phantom');
  for (let i = 0; i < 10 && !(await connect.evaluate((el) => el === document.activeElement)); i++) {
    await page.keyboard.press('Tab');
  }
  await expect(connect).toBeFocused();
  await page.keyboard.press('Enter');
  await page.waitForURL((url) => url.pathname === '/scanner');
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
