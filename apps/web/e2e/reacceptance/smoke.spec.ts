import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import { Client } from 'pg';
import { generateTestWallet, installTestWallet, type SmokeWallet } from '../support/test-wallet';

/**
 * u3-reacceptance smoke (US4.1, US4.2; BR3.1, BR3.2, BR3.7, BR3.9; NFR4.1): scripts/verify.sh runs the environment with
 * the TEST catalog "reviewed" (legacy v1, reviewed v2 current). Through the DATABASE_URL that verify.sh already passes
 * to Playwright, this spec inserts a BETA_USER account whose ONLY acceptance is v1, with a wallet generated here
 * (never production code, never a real wallet). The person signs in, the bots screen meets the reacceptance lock and
 * the app takes it to /novo-aceite; by keyboard alone it reads the links, marks both boxes and accepts; it is back on
 * the bots screen and the route that was locked now passes, with the same session.
 */
const OUTDATED_VERSION = 'v1';

/** One window of the per-IP login limit (BR1.11 of U1: 10 per minute), plus a margin. */
const LOGIN_LIMIT_WINDOW_MS = 65_000;

/** Inserts a tenant, a BETA_USER, its public wallet and an acceptance of v1 only (no invitation). */
async function insertOutdatedGuest(wallet: SmokeWallet): Promise<void> {
  const url = process.env.DATABASE_URL;
  if (!url) {
    throw new Error('DATABASE_URL is not set (scripts/verify.sh sets it)');
  }
  const client = new Client({ connectionString: url });
  await client.connect();
  const tenantId = randomUUID();
  const userId = randomUUID();
  try {
    await client.query('begin');
    await client.query(
      "insert into tenant (tenant_id, name, status, created_at) values ($1, 'Reacceptance e2e tenant', 'ACTIVE', now())",
      [tenantId],
    );
    await client.query(
      "insert into app_user (user_id, tenant_id, role, status, created_at) values ($1, $2, 'BETA_USER', 'ACTIVE', now())",
      [userId, tenantId],
    );
    await client.query(
      "insert into wallet (wallet_id, user_id, address, provider, verified_at) values ($1, $2, $3, 'PHANTOM', now())",
      [randomUUID(), userId, wallet.address],
    );
    await client.query(
      `insert into terms_acceptance
         (acceptance_id, user_id, tenant_id, terms_version, accepted_at, invite_id, source)
       values ($1, $2, $3, $4, now() - interval '30 days', null, 'REACCEPTANCE')`,
      [randomUUID(), userId, tenantId, OUTDATED_VERSION],
    );
    await client.query('commit');
  } catch (failure) {
    await client.query('rollback');
    throw failure;
  } finally {
    await client.end();
  }
}

/** Signs in from /entrar?origem=/bots; on the real login limit (429) waits one window and tries once more. */
async function signInTowardsTheBots(page: Page): Promise<void> {
  await page.goto('/entrar?origem=/bots');
  await page.getByTestId('login-connect-phantom').click();
  const reacceptance = page
    .waitForURL((url) => url.pathname === '/novo-aceite')
    .then(() => 'reacceptance' as const);
  const limited = page
    .getByTestId('login-rejected-reason')
    .filter({ hasText: 'Muitas tentativas' })
    .waitFor()
    .then(() => 'rate-limited' as const);
  const outcome = await Promise.race([reacceptance, limited]);
  reacceptance.catch(() => undefined);
  limited.catch(() => undefined);
  if (outcome === 'reacceptance') {
    return;
  }
  test.info().setTimeout(test.info().timeout + LOGIN_LIMIT_WINDOW_MS);
  await page.waitForTimeout(LOGIN_LIMIT_WINDOW_MS);
  await page.goto('/entrar?origem=/bots');
  await page.getByTestId('login-connect-phantom').click();
  await page.waitForURL((url) => url.pathname === '/novo-aceite');
}

test('u3-reacceptance: versão nova → trava → novo aceite pelo teclado → a rota volta a passar', async ({
  page,
}) => {
  const wallet = generateTestWallet();
  await insertOutdatedGuest(wallet);
  await installTestWallet(page, wallet);

  // A locked route of the guest (the bot list) takes the person to the new acceptance, keeping the screen of origin.
  await signInTowardsTheBots(page);
  expect(new URL(page.url()).searchParams.get('voltar')).toBe('/bots');
  const title = page.getByRole('heading', { level: 1, name: 'Novo aceite dos termos' });
  await expect(title).toBeVisible();
  await expect(title).toBeFocused();
  await expect(page.getByTestId('reacceptance-version')).toContainText('Versão v2');
  await expect(
    page.getByTestId('reacceptance-terms-summary').getByRole('listitem').first(),
  ).toBeVisible();
  await expect(page.getByTestId('reacceptance-accept')).toBeDisabled();
  await expect(page.getByTestId('reacceptance-accept-reason')).toHaveText(
    'Marque as duas caixas para continuar.',
  );
  await expect(page.getByTestId('reacceptance-terms-link')).toHaveAttribute('target', '_blank');

  // Keyboard only, in order: links, the two boxes, the button.
  await page.keyboard.press('Tab');
  await expect(page.getByTestId('reacceptance-terms-link')).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(page.getByTestId('reacceptance-privacy-link')).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(page.getByTestId('reacceptance-accept-terms')).toBeFocused();
  await page.keyboard.press('Space');
  await page.keyboard.press('Tab');
  await expect(page.getByTestId('reacceptance-accept-risk')).toBeFocused();
  await page.keyboard.press('Space');
  await expect(page.getByTestId('reacceptance-accept')).toBeEnabled();
  await page.keyboard.press('Tab');
  await expect(page.getByTestId('reacceptance-accept')).toBeFocused();
  await page.keyboard.press('Enter');

  // Back to the screen of origin; the same session passes the route that was locked.
  await page.waitForURL((url) => url.pathname === '/bots');
  await expect(page.getByTestId('bots-create')).toBeVisible();
  await expect(page).not.toHaveURL(/novo-aceite/);

  // The Account shows the person up to date with the current version.
  await page.goto('/conta');
  await expect(page.getByTestId('terms-status-up-to-date')).toBeVisible();
  await expect(page.getByTestId('terms-status-accepted')).toContainText('versão v2');
});
