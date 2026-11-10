import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import {
  generateTestWallet,
  installTestWallet,
  loadSmokeWallet,
  signWith,
  type SmokeWallet,
} from '../support/test-wallet';

// The invitation link carries the code: the trace, the screenshots and the video stay off (as in the U3 smoke).
test.use({ trace: 'off', screenshot: 'off', video: 'off' });

/**
 * Preconditions set by scripts/verify.sh (cicd-pipeline.md of u5-anonymization): a SECOND single-use invitation
 * (SMOKE_ANONYMIZATION_INVITE_CODE, seeded by the test profile), so this spec never touches the account of the smoke;
 * the key file of the throw-away administrator wallet and the URLs of the administrator app and of the api. Without
 * them the spec fails with a clear message instead of silently skipping.
 */
function preconditions(): {
  inviteCode: string;
  adminWalletFile: string;
  adminBaseUrl: string;
  apiBaseUrl: string;
} {
  const inviteCode = process.env.SMOKE_ANONYMIZATION_INVITE_CODE;
  const adminWalletFile = process.env.SMOKE_ADMIN_WALLET_FILE;
  const adminBaseUrl = process.env.ADMIN_BASE_URL;
  const apiBaseUrl = process.env.API_BASE_URL;
  if (!inviteCode || !adminWalletFile || !adminBaseUrl || !apiBaseUrl) {
    throw new Error(
      'u5-anonymization smoke needs SMOKE_ANONYMIZATION_INVITE_CODE, SMOKE_ADMIN_WALLET_FILE, ADMIN_BASE_URL and' +
        ' API_BASE_URL (set by scripts/verify.sh, see docs/api/u5-anonymization.md).',
    );
  }
  return { inviteCode, adminWalletFile, adminBaseUrl, apiBaseUrl };
}

/** Accepts the terms of the invitation and creates the account; returns the access token the app received. */
async function createTheAccount(page: Page, inviteCode: string): Promise<string> {
  await page.goto(`/convite/${inviteCode}`);
  await expect(page.getByTestId('invite-terms')).toBeVisible();
  await page.getByTestId('invite-accept-terms').check();
  await page.getByTestId('invite-accept-risk').check();
  const verified = page.waitForResponse(
    (r) => r.url().endsWith('/api/v1/auth/verify') && r.request().method() === 'POST',
  );
  await page.getByTestId('invite-connect-phantom').click();
  const response = await verified;
  expect(response.status(), 'the invitation creates the throw-away account').toBe(200);
  await page.waitForURL((url) => url.pathname === '/');
  return ((await response.json()) as { accessToken: string }).accessToken;
}

/** A login without invitation through the api, signed by the test wallet: the status of the verification. */
async function loginStatus(
  request: APIRequestContext,
  apiBaseUrl: string,
  wallet: SmokeWallet,
): Promise<number> {
  const challenge = await request.post(`${apiBaseUrl}/api/v1/auth/challenge`, {
    headers: { 'Idempotency-Key': randomUUID() },
    data: { walletAddress: wallet.address },
  });
  expect(challenge.status()).toBe(200);
  const { message } = (await challenge.json()) as { message: string };
  const verify = await request.post(`${apiBaseUrl}/api/v1/auth/verify`, {
    headers: { 'Idempotency-Key': randomUUID() },
    data: {
      walletAddress: wallet.address,
      message,
      signature: base58(signWith(wallet, message)),
      provider: 'PHANTOM',
    },
  });
  return verify.status();
}

/**
 * u5-anonymization smoke (US6.1 to US6.3; BR5.1, BR5.2, BR5.7, BR5.9): a THROW-AWAY account (a wallet generated in this
 * test, a single-use invitation of the test profile) asks for the deletion of its data in the Account; the
 * administrator, with the throw-away wallet of this run, starts the request and anonymizes the account through T8 by
 * typing ANONIMIZAR; the request is DONE, the list says "Conta anonimizada", the access token issued before is refused
 * on the next request and a new login with the same wallet is refused. Synthetic text only, no real wallet.
 */
test('u5-anonymization: a Deletion request anonymizes the throw-away account', async ({
  browser,
  page,
  request,
}) => {
  const { inviteCode, adminWalletFile, adminBaseUrl, apiBaseUrl } = preconditions();
  test.info().setTimeout(240_000);
  const wallet = generateTestWallet();
  const marker = `smoke-u5-${Date.now()}`;

  // 1. The throw-away account enters through the invitation and asks for the deletion in the Account.
  await installTestWallet(page, wallet);
  const accessToken = await createTheAccount(page, inviteCode);
  await page.getByTestId('app-nav-account-link').click();
  await expect(page.getByTestId('data-requests-section')).toBeVisible();
  await page.getByTestId('data-request-type').selectOption('DELETION');
  await page.getByTestId('data-request-details').fill(`Pedido de exclusão do smoke ${marker}`);
  const created = page.waitForResponse(
    (r) => r.url().endsWith('/api/v1/privacy/requests') && r.request().method() === 'POST',
  );
  await page.getByTestId('data-request-submit').click();
  const createdResponse = await created;
  expect(createdResponse.status()).toBe(201);
  const requestId = ((await createdResponse.json()) as { requestId: string }).requestId;

  // 2. The administrator starts the request and anonymizes the account through the dialog T8, by keyboard.
  const adminContext = await browser.newContext({ baseURL: adminBaseUrl, locale: 'pt-BR' });
  const admin = await adminContext.newPage();
  await installTestWallet(admin, loadSmokeWallet(adminWalletFile));
  await admin.goto('/');
  await admin.getByTestId('admin-sign-in-connect-phantom').click();
  await admin.getByTestId('admin-nav-data-requests').click();
  await expect(admin.getByTestId('data-requests-page')).toBeVisible();
  await admin.goto(`/pedidos/${requestId}`);
  await admin.getByTestId('data-request-start').click();
  await expect(admin.getByTestId('data-request-status')).toContainText('Em andamento');
  await admin.getByTestId('anonymize-open').click();
  await expect(admin.getByRole('dialog')).toBeVisible();
  await expect(admin.getByTestId('anonymize-dialog-submit')).toBeDisabled();
  await admin.keyboard.type('ANONIMIZAR');
  const anonymized = admin.waitForResponse((r) =>
    r.url().endsWith(`/api/v1/admin/privacy/requests/${requestId}/anonymization`),
  );
  await admin.keyboard.press('Enter');
  const anonymizedResponse = await anonymized;
  expect(anonymizedResponse.status()).toBe(200);
  expect(await anonymizedResponse.text()).not.toContain(wallet.address);

  // 3. The request is DONE and the account is "Conta anonimizada" in the detail and in the list.
  await expect(admin.getByTestId('data-request-status')).toContainText('Concluído');
  await expect(admin.getByTestId('account-state-anonymized')).toHaveText(/Conta anonimizada/);
  await admin.goto('/pedidos');
  await expect(admin.getByTestId(`data-request-account-${requestId}-anonymized`)).toContainText(
    'Conta anonimizada',
  );
  await adminContext.close();

  // 4. The token issued before is refused on the next request, and a new login with the same wallet is refused.
  const account = await request.get(`${apiBaseUrl}/api/v1/account`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  expect(account.status()).toBe(401);
  expect(await loginStatus(request, apiBaseUrl, wallet)).toBe(401);
});

const ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';

/** Base58 of the signature, as a browser wallet adapter sends it to the api. */
function base58(bytes: Buffer): string {
  let value = BigInt('0x' + (bytes.length > 0 ? bytes.toString('hex') : '0'));
  let out = '';
  while (value > 0n) {
    out = ALPHABET[Number(value % 58n)] + out;
    value /= 58n;
  }
  for (const byte of bytes) {
    if (byte !== 0) {
      break;
    }
    out = '1' + out;
  }
  return out;
}
