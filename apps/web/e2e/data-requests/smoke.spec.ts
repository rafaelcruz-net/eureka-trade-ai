import { expect, test } from '@playwright/test';
import { installTestWallet, loadSmokeWallet } from '../support/test-wallet';
import { connectAndReachScanner } from '../u4-discovery/sign-in';

/**
 * Preconditions set by scripts/verify.sh: the key file of the throw-away administrator wallet (whose address the api
 * received in ADMIN_WALLET_ADDRESSES) and the URL of the administrator app, the same as the U8 smoke. Without them the
 * spec fails with a clear message instead of silently skipping.
 */
function preconditions(): { adminWalletFile: string; adminBaseUrl: string } {
  const adminWalletFile = process.env.SMOKE_ADMIN_WALLET_FILE;
  const adminBaseUrl = process.env.ADMIN_BASE_URL;
  if (!adminWalletFile || !adminBaseUrl) {
    throw new Error(
      'u4-data-requests smoke needs SMOKE_ADMIN_WALLET_FILE and ADMIN_BASE_URL (set by scripts/verify.sh, see' +
        ' docs/api/u4-data-requests.md).',
    );
  }
  return { adminWalletFile, adminBaseUrl };
}

/**
 * u4-data-requests smoke (US5.1 to US5.3; BR4.1, BR4.4, BR4.5, BR4.18): the smoke guest (already up to date with the
 * terms) creates a request in the section "Meus dados (LGPD)" of the Account, by keyboard, and sees it in the list; the
 * administrator, with the throw-away wallet of this run, opens it, starts it and concludes it with a response; back on
 * the Account, the guest sees the response. Synthetic text only, no real wallet, no transaction.
 */
test('u4-data-requests: a request from the Account is answered by the administrator', async ({
  browser,
  page,
}) => {
  const { adminWalletFile, adminBaseUrl } = preconditions();
  test.info().setTimeout(180_000);
  const marker = `smoke-u4-${Date.now()}`;
  const answer = `Resposta do smoke ${marker}`;

  // 1. The smoke guest signs in and opens the Account.
  await installTestWallet(page, loadSmokeWallet());
  await page.goto('/entrar?origem=/scanner');
  await connectAndReachScanner(page);
  await page.getByTestId('app-nav-account-link').click();
  await expect(page.getByTestId('data-requests-section')).toBeVisible();

  // 2. By keyboard only: type, text, submit (the type select, the field and the button in reading order).
  // OTHER: an earlier run that stopped half-way never blocks this one with an open ACCESS of the smoke account.
  await page.getByTestId('data-request-type').selectOption('OTHER');
  await page.getByTestId('data-request-type').focus();
  await page.keyboard.press('Tab');
  await expect(page.getByTestId('data-request-details')).toBeFocused();
  await page.keyboard.type(`Pedido do smoke ${marker}`);
  await expect(page.getByTestId('data-request-counter')).toContainText('de 1.000 caracteres');
  const created = page.waitForResponse(
    (r) => r.url().endsWith('/api/v1/privacy/requests') && r.request().method() === 'POST',
  );
  await page.keyboard.press('Tab');
  await expect(page.getByTestId('data-request-submit')).toBeFocused();
  await page.keyboard.press('Enter');
  const response = await created;
  expect(response.status()).toBe(201);
  const requestId = ((await response.json()) as { requestId: string }).requestId;
  await expect(page.getByTestId('data-request-created')).toContainText('Pedido registrado');
  await expect(page.getByTestId('data-requests-row').first()).toContainText('Recebido');

  // 3. The administrator opens the request, starts it and concludes it with a response.
  const adminContext = await browser.newContext({ baseURL: adminBaseUrl, locale: 'pt-BR' });
  const admin = await adminContext.newPage();
  await installTestWallet(admin, loadSmokeWallet(adminWalletFile));
  await admin.goto('/');
  await admin.getByTestId('admin-sign-in-connect-phantom').click();
  await admin.getByTestId('admin-nav-data-requests').click();
  await expect(admin.getByTestId('data-requests-page')).toBeVisible();
  await admin.goto(`/pedidos/${requestId}`);
  await expect(admin.getByTestId('data-request-details')).toHaveText(`Pedido do smoke ${marker}`);
  await admin.getByTestId('data-request-start').click();
  await expect(admin.getByTestId('data-request-status')).toContainText('Em andamento');
  await admin.getByTestId('data-request-response').fill(answer);
  await admin.getByTestId('data-request-done').click();
  await expect(admin.getByTestId('data-request-final-response')).toHaveText(answer);
  await adminContext.close();

  // 4. Back on the Account, the guest sees the response in the detail of the request.
  await page.reload();
  await expect(page.getByTestId('data-requests-section')).toBeVisible();
  const row = page.getByTestId('data-requests-row').first();
  await expect(row).toContainText('Concluído');
  await row.getByTestId('data-requests-row-open').focus();
  await page.keyboard.press('Enter');
  await expect(page.getByTestId('data-request-detail-response')).toHaveText(answer);
});
