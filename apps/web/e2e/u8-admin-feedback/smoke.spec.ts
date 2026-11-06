import { expect, test } from '@playwright/test';
import { installTestWallet, loadSmokeWallet } from '../support/test-wallet';
import { connectAndReachScanner } from '../u4-discovery/sign-in';

const API = process.env.API_BASE_URL ?? 'http://localhost:8080';

/** NFR3.1 (Q5 of the NFR Design): each administrative screen is visible in at most 2 s. */
const SCREEN_BUDGET_MS = 2_000;

/**
 * Preconditions set by scripts/verify.sh (cicd-pipeline.md "Ação pendente do responsável do projeto"): the key file
 * of the throw-away administrator wallet, whose address the api received in ADMIN_WALLET_ADDRESSES, and the URL of the
 * administrator app. Without them the smoke fails with a clear message instead of silently skipping.
 */
function preconditions(): { adminWalletFile: string; adminBaseUrl: string } {
  const adminWalletFile = process.env.SMOKE_ADMIN_WALLET_FILE;
  const adminBaseUrl = process.env.ADMIN_BASE_URL;
  if (!adminWalletFile || !adminBaseUrl) {
    throw new Error(
      'U8 smoke needs SMOKE_ADMIN_WALLET_FILE and ADMIN_BASE_URL (set by scripts/verify.sh, see' +
        ' docs/api/u8-admin-feedback.md "Mudanças no verify.sh").',
    );
  }
  return { adminWalletFile, adminBaseUrl };
}

/**
 * U8 smoke (US1.2 AC1.2.6, US7.4, US7.1, US7.2, US6.1): the administrator enters with the throw-away wallet; sees the
 * system status in at most 2 s; issues and revokes an invitation; switches the kill switch on and off, checking the
 * banner; the smoke guest sends a comment with a marker that the administrator then reads as plain text; a guest token
 * on an administrative route is refused with 403. Simulated sources only, no real wallet, no transaction.
 */
test('U8: administrator entry, status, invitations, kill switch and feedback', async ({
  browser,
  page,
  request,
}) => {
  const { adminWalletFile, adminBaseUrl } = preconditions();
  test.info().setTimeout(180_000);
  // scripts/verify.sh passes the marker so it can scan the logs for the feedback text (NFR7.3); it only goes into the
  // comment, never into the kill switch reason.
  const marker = process.env.SMOKE_FEEDBACK_MARKER ?? `smoke-u8-${Date.now()}`;

  // 1. The administrator enters with the throw-away wallet of this run.
  const adminContext = await browser.newContext({ baseURL: adminBaseUrl, locale: 'pt-BR' });
  const admin = await adminContext.newPage();
  await installTestWallet(admin, loadSmokeWallet(adminWalletFile));
  await admin.goto('/');
  await admin.getByTestId('admin-sign-in-connect-phantom').click();

  // 2. The system status in at most 2 s, counts only.
  const statusStarted = Date.now();
  await expect(admin.getByTestId('status-guests')).toHaveAttribute('data-state', 'known');
  expect(Date.now() - statusStarted).toBeLessThan(SCREEN_BUDGET_MS);
  await expect(admin.getByTestId('admin-no-personal-data')).toBeVisible();

  // 3. Issue an invitation (code shown once) and revoke it.
  await admin.getByTestId('admin-nav-invites').click();
  await admin.getByTestId('invites-issue').click();
  await admin.getByTestId('issue-invite-tenant').selectOption({ index: 1 });
  await admin.getByTestId('issue-invite-submit').click();
  await expect(admin.getByTestId('issue-invite-link')).toHaveValue(/\/convite\/[A-Za-z0-9_-]+$/);
  await admin.getByTestId('issue-invite-close').click();
  await admin.locator('[data-testid^="invite-revoke-"]').first().click();
  await admin.getByTestId('revoke-invite-confirm').click();
  await expect(admin.locator('[data-testid="invite-status"]').first()).toHaveText('REVOGADO');

  // 4. Kill switch ON, the banner on every screen, then OFF.
  await admin.getByTestId('admin-nav-kill-switch').click();
  await admin.getByTestId('kill-switch-activate').click();
  await admin.getByTestId('kill-switch-dialog-reason').fill('Início do smoke U8');
  await admin.getByTestId('kill-switch-dialog-confirm').click();
  await expect(admin.getByTestId('kill-switch-banner')).toContainText('Kill switch ACIONADO');
  await admin.getByTestId('admin-nav-feedback').click();
  await expect(admin.getByTestId('kill-switch-banner')).toBeVisible();
  await admin.getByTestId('admin-nav-kill-switch').click();
  await admin.getByTestId('kill-switch-deactivate').click();
  await admin.getByTestId('kill-switch-dialog-reason').fill('Fim do smoke U8');
  await admin.getByTestId('kill-switch-dialog-confirm').click();
  await expect(admin.getByTestId('kill-switch-banner')).toHaveCount(0);

  // 5. The smoke guest sends a comment with the marker; the administrator reads it as plain text.
  await installTestWallet(page, loadSmokeWallet());
  const verified = page.waitForResponse(
    (r) => r.url().endsWith('/api/v1/auth/verify') && r.request().method() === 'POST',
  );
  await page.goto('/entrar?origem=/scanner');
  await connectAndReachScanner(page);
  const guestSession = (await (await verified).json()) as { accessToken: string };
  await page.getByTestId('app-feedback-button').click();
  await page.getByTestId('feedback-dialog-text').fill(`Comentário do smoke <b>${marker}</b>`);
  await page.getByTestId('feedback-dialog-send').click();
  await expect(page.getByTestId('feedback-dialog')).toHaveCount(0);
  await expect(page.getByTestId('app-feedback-button')).toBeFocused();

  await admin.getByTestId('admin-nav-feedback').click();
  const feedbackStarted = Date.now();
  await expect(admin.getByTestId('feedback-text').filter({ hasText: marker })).toHaveText(
    `Comentário do smoke <b>${marker}</b>`,
  );
  expect(Date.now() - feedbackStarted).toBeLessThan(SCREEN_BUDGET_MS);

  // 6. A guest token on an administrative route: 403 without data.
  const refused = await request.get(`${API}/api/v1/admin/system-status`, {
    headers: { Authorization: `Bearer ${guestSession.accessToken}` },
  });
  expect(refused.status()).toBe(403);
  expect(((await refused.json()) as Record<string, unknown>).code).toBe('FORBIDDEN_ROLE');

  await admin.getByTestId('admin-sign-out').click();
  await expect(admin.getByTestId('admin-sign-in')).toBeVisible();
  await adminContext.close();
});
