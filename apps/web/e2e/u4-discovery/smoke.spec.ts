import { expect, test } from '@playwright/test';
import { installTestWallet, loadSmokeWallet } from '../support/test-wallet';
import { connectAndReachScanner } from './sign-in';

/**
 * U4 smoke (US2.2, US3.1, NFR11.1): sign in, open the scanner, apply a filter, see the counter of tokens left out by
 * an unknown field, open the assessment panel with the keyboard, see the indicator or its unavailability with the
 * reason, close it with Esc and get the focus back on the same row. Simulated sources only (C-06).
 */
test('U4: filter, unknown counter, assessment panel and Esc back to the row', async ({ page }) => {
  const wallet = loadSmokeWallet();
  await installTestWallet(page, wallet);

  await page.goto('/entrar?origem=/scanner');
  await connectAndReachScanner(page);
  await expect(page.getByTestId('scanner-table')).toBeVisible();
  await expect(page.getByTestId('scanner-source')).toContainText('simulada');

  // Holders are not informed for some simulated tokens: a holders filter leaves them out and counts them.
  await page.getByTestId('scanner-filter-minHolders').fill('1');
  await page.getByTestId('scanner-filters-apply').click();
  await expect(page).toHaveURL(/minHolders=1/);
  await expect(page.getByTestId('scanner-excluded')).toContainText('por dado ausente');
  await expect(page.getByTestId('scanner-table')).toBeVisible();

  const firstRow = page.locator('[data-testid^="scanner-row-select-"]').first();
  const rowTestId = (await firstRow.getAttribute('data-testid')) ?? '';
  await firstRow.focus();
  await page.keyboard.press('Enter');

  const panel = page.getByTestId('assessment-panel');
  await expect(panel).toBeVisible();
  await expect(panel.getByTestId('assessment-detail')).toBeVisible();
  await expect(panel.getByTestId('assessment-in-validation')).toContainText(
    'Indicador em validação',
  );
  const available = await panel.getByTestId('assessment-score').count();
  if (available > 0) {
    await expect(panel.getByTestId('assessment-score')).toContainText(/Indicador: \d+ de 100/);
  } else {
    await expect(panel.getByTestId('assessment-unavailable-reason')).not.toBeEmpty();
    await expect(panel.getByTestId('assessment-decision')).toContainText('NO_TRADE');
  }
  await expect(panel.getByTestId('assessment-factors')).toBeVisible();

  await page.keyboard.press('Escape');
  await expect(panel).toBeHidden();
  await expect(page.getByTestId(rowTestId)).toBeFocused();
});
