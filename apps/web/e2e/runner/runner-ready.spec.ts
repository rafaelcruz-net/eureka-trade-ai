import { expect, test } from '@playwright/test';

// Proves the Playwright runner and Chromium work without the application environment.
test('runner renders a page in Chromium', async ({ page }) => {
  await page.setContent('<main><h1>pronto</h1><button type="button">ok</button></main>');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('pronto');
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: 'ok' })).toBeFocused();
});
