import { expect, test } from '@playwright/test';
import { installTestWallet, loadSmokeWallet } from '../support/test-wallet';
import { connectAndReachScanner } from '../u4-discovery/sign-in';

/**
 * U7 smoke (US5.1, US5.2, BR6.6, BR10.1; Q1 of the Infrastructure Design, option B): sign in with the smoke account of
 * U1, open the P&L screen and check its states without operations - "Nenhuma operação neste período", a ledger with at
 * most the initial virtual balance, "Reconciliado" after the check and the label "MODO PAPER (simulado)". The flow with
 * operations belongs to the smoke of U5. Simulated sources only, no real wallet, no transaction.
 */
test('U7: P&L, ledger and reconciliation of an account without operations', async ({ page }) => {
  const wallet = loadSmokeWallet();
  await installTestWallet(page, wallet);

  await page.goto('/entrar?origem=/scanner');
  await connectAndReachScanner(page);
  await page.getByTestId('app-nav-pnl-link').click();
  await page.waitForURL((url) => url.pathname === '/pnl');

  // 261121-paper-real-unico U1 (BR9.2): the h1 is "Resultado" with the two blocks by mode; the PAPER period report
  // below keeps the label "MODO PAPER (simulado)".
  await expect(page.getByTestId('pnl-title')).toHaveText('Resultado');
  await expect(page.getByTestId('pnl-paper-section')).toContainText('MODO PAPER (simulado)');
  await expect(page.getByTestId('pnl-empty')).toHaveText('Nenhuma operação neste período');
  await expect(page.getByTestId('pnl-report')).toHaveCount(0);
  // The smoke of U5 runs earlier with the same account: creating its PAPER bot records the initial virtual balance and
  // the scheduled reconciliation may not have run yet. So the ledger is either empty or holds only that balance, and
  // the panel shows either the last check or the wait for the first one; the click below always reconciles.
  await expect(page.getByTestId('ledger')).toContainText('MODO PAPER (simulado)');
  await expect(
    page
      .getByTestId('ledger-empty')
      .or(page.getByTestId('ledger').getByText('Saldo virtual inicial')),
  ).toBeVisible();
  await expect(
    page.getByTestId('reconciliation-ok').or(page.getByTestId('reconciliation-pending')),
  ).toBeVisible();
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
