import { expect, test, type Page } from '@playwright/test';
import { generateTestWallet, installTestWallet } from '../support/test-wallet';

/** One window of the per-IP login limit (BR1.11 of U1: 10 per minute), plus a margin. */
const LOGIN_LIMIT_WINDOW_MS = 65_000;

/** NFR3.4: page request to visible content (terms or account data) within 2 s for one user. */
const SCREEN_BUDGET_MS = 2_000;

// The invitation link carries the code: the trace (which records URLs and screenshots) is off for this test so the
// CI report never keeps it (Infrastructure Design review finding, Passo 20).
test.use({ trace: 'off', screenshot: 'off', video: 'off' });

/** Clicks "Conectar Phantom"; on the real login limit (429) waits one window and asks for a new challenge once. */
async function connectAndCreateTheAccount(page: Page): Promise<void> {
  await page.getByTestId('invite-connect-phantom').click();
  // U9: the home page after the entry is the dashboard.
  const home = page.waitForURL((url) => url.pathname === '/').then(() => 'home' as const);
  const limited = page
    .getByTestId('invite-rejected-reason')
    .filter({ hasText: 'Muitas tentativas' })
    .waitFor()
    .then(() => 'rate-limited' as const);
  const outcome = await Promise.race([home, limited]);
  home.catch(() => undefined);
  limited.catch(() => undefined);
  if (outcome === 'home') {
    return;
  }
  test.info().setTimeout(test.info().timeout + LOGIN_LIMIT_WINDOW_MS);
  await page.waitForTimeout(LOGIN_LIMIT_WINDOW_MS);
  await page.getByTestId('invite-try-again').click();
  await page.getByTestId('invite-connect-phantom').click();
  await page.waitForURL((url) => url.pathname === '/');
}

/**
 * U3 smoke (US1.1, US1.3, US1.5; NFR3.4, NFR6.5): opens the invitation link seeded by the test profile, accepts the
 * terms and the risk notice, connects a wallet generated in this test, creates the account, opens the Account screen,
 * checks REAL is unavailable without any control, and signs out.
 */
test('U3: convite → conta criada → tela Conta → Sair', async ({ page }) => {
  const code = process.env.SMOKE_INVITE_CODE;
  if (!code) {
    throw new Error('SMOKE_INVITE_CODE is not set (scripts/verify.sh exports it)');
  }
  const wallet = generateTestWallet();
  await installTestWallet(page, wallet);

  const started = Date.now();
  const response = await page.goto(`/convite/${code}`);
  await expect(page.getByTestId('invite-terms')).toBeVisible();
  const invitationMs = Date.now() - started;
  expect(response?.headers()['referrer-policy']).toBe('no-referrer');
  expect(response?.headers()['cache-control']).toBe('no-store');
  await expect(page.getByTestId('invite-connect-reason')).toContainText('Marque os dois aceites');
  // u1-legal-documents: the version comes from the effective current version of the catalog (the e2e run reads the
  // test catalog, whose current version is v2), so the account must show the version the invitation showed.
  const heading = await page.locator('#invite-terms-heading').innerText();
  const termsVersion = /versão ([A-Za-z0-9._-]+)\)/.exec(heading)?.[1];
  expect(termsVersion, 'the invitation names the version of the terms').toBeTruthy();

  await page.getByTestId('invite-accept-terms').check();
  await page.getByTestId('invite-accept-risk').check();
  await connectAndCreateTheAccount(page);

  const accountStarted = Date.now();
  await page.getByTestId('app-nav-account-link').click();
  await expect(page.getByTestId('account-wallet')).toBeVisible();
  const accountMs = Date.now() - accountStarted;
  await expect(page.getByTestId('account-wallet')).toHaveText(
    `${wallet.address.slice(0, 4)}…${wallet.address.slice(-4)}`,
  );
  await expect(page.getByTestId('account-terms-version')).toContainText(termsVersion ?? '');
  await expect(page.getByTestId('account-real-mode')).toHaveText('Modo REAL: indisponível no beta');
  await expect(page.getByRole('button', { name: /real/i })).toHaveCount(0);
  await expect(page.getByRole('checkbox')).toHaveCount(0);

  await page.getByTestId('app-sign-out-button').click();
  await page.waitForURL((url) => url.pathname === '/entrar');

  test
    .info()
    .annotations.push(
      { type: 'invitation-screen-ms', description: String(invitationMs) },
      { type: 'account-screen-ms', description: String(accountMs) },
    );
  expect(invitationMs).toBeLessThan(SCREEN_BUDGET_MS);
  expect(accountMs).toBeLessThan(SCREEN_BUDGET_MS);
});
