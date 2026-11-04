import { expect, test } from '@playwright/test';
import { installTestWallet, loadSmokeWallet } from '../support/test-wallet';
import { connectAndReachScanner } from '../u4-discovery/sign-in';

/**
 * U7 smoke (US5.1, US5.2, BR6.6, BR10.1; Q1 of the Infrastructure Design, option B): sign in with the smoke account of
 * U1, open the P&L screen and check its EMPTY states - "Nenhuma operação neste período", an empty ledger,
 * "Reconciliado" and the label "MODO PAPER (simulado)". The flow with operations belongs to the smoke of U5. Simulated
 * sources only, no real wallet, no transaction.
 */
test('U7: P&L, ledger and reconciliation of an account without operations', async ({ page }) => {
  const wallet = loadSmokeWallet();
  await installTestWallet(page, wallet);

  await page.goto('/entrar?origem=/scanner');
  await connectAndReachScanner(page);
  await page.getByTestId('app-pnl-link').click();
  await page.waitForURL((url) => url.pathname === '/pnl');

  await expect(page.getByTestId('pnl-title')).toContainText('MODO PAPER (simulado)');
  await expect(page.getByTestId('pnl-empty')).toHaveText('Nenhuma operação neste período');
  await expect(page.getByTestId('pnl-report')).toHaveCount(0);
  await expect(page.getByTestId('ledger-empty')).toBeVisible();
  await expect(page.getByTestId('ledger')).toContainText('MODO PAPER (simulado)');
  await expect(page.getByTestId('reconciliation-ok')).toContainText('Reconciliado');
  await expect(page.getByRole('button', { name: /editar|apagar|excluir/i })).toHaveCount(0);

  // "Verificar agora" really runs the check (POST /api/v1/reconciliation/run with Idempotency-Key): the result now
  // carries the time of the check, which the state before the click (no check yet) does not.
  const run = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/v1/reconciliation/run') &&
      response.request().method() === 'POST',
  );
  await page.getByTestId('reconciliation-check-now').click();
  expect((await run).status()).toBe(200);
  await expect(page.getByTestId('reconciliation-ok')).toContainText(/Reconciliado às \d{2}:\d{2}/);
  await expect(page.getByTestId('reconciliation-check-message')).toHaveCount(0);
});
