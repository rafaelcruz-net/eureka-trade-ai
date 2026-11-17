import { randomUUID } from 'node:crypto';
import {
  expect,
  test,
  type APIRequestContext,
  type APIResponse,
  type BrowserContext,
  type Page,
} from '@playwright/test';
import {
  installTestWallet,
  loadSmokeWallet,
  signWith,
  type SmokeWallet,
} from '../support/test-wallet';
import {
  LOGIN_WINDOW_MS,
  postWithinLoginLimit,
  signInWithinLoginLimit,
} from '../support/login-limit';
import { encode } from '../real-bot-wallet/local-validator';

const API = process.env.API_BASE_URL ?? 'http://localhost:8080';

type BotWallet = { mode: string; capLamports: string; status: string; pausedReason?: string };
type KillSwitch = { active: boolean };
type Divergence = { state: string };
type AdminUser = { userId: string; botWalletAddress: string | null; release?: { state?: string } };

/**
 * 261113-trading-real-bot U6 (infrastructure-specification.md "Spec novo `apps/web/e2e/real-ui/`", U6-NFR6.1,
 * U6-NFR6.5, U6-NFR6.6, U6-NFR8.5): the REAL screens on the local validator, with the smoke account and its bot wallet
 * of the specs before this one (real-bot-wallet, real-swap, real-ops).
 *
 * - Entry state (from real-swap and real-ops): bot wallet ACTIVE in AUTO_FULL, bot stopped, divergence resolved, kill
 *   switch REAL off. `beforeAll` checks it through the API of the administrator and FAILS with what it found; the spec
 *   never fixes that state on its own.
 * - Scenarios: overview and cap; mode; orders; deposit and withdrawal (open and cancel only); administrator; 320 px.
 * - Exit state equal to the entry: AUTO_FULL, the same cap, kill switch off, no new release.
 *
 * The signature of an order in the SIGNED mode is not reproduced here: the only recorded route was used by real-swap
 * (infrastructure-specification.md "limite do ambiente local"); component tests and the ITs of the U4 cover it.
 * Wallets and keys are generated in test code; nothing here talks to devnet, mainnet or a real KMS.
 */
test.describe.serial('REAL screens on the local validator', () => {
  test.setTimeout(240_000);

  let main: SmokeWallet;
  let admin: SmokeWallet;
  let guestToken = '';
  let adminToken = '';
  let entryCap = '';
  let page: Page;
  let adminContext: BrowserContext;
  let adminPage: Page;

  async function signIn(request: APIRequestContext, wallet: SmokeWallet, prefix: string) {
    const challenge = await postWithinLoginLimit(request, `${API}${prefix}/challenge`, {
      walletAddress: wallet.address,
    });
    expect(challenge.ok(), `challenge of ${prefix}`).toBe(true);
    const { message } = (await challenge.json()) as { message: string };
    const verify = await postWithinLoginLimit(request, `${API}${prefix}/verify`, {
      walletAddress: wallet.address,
      message,
      signature: encode(signWith(wallet, message)),
      provider: 'PHANTOM',
    });
    expect(verify.ok(), `verify of ${prefix}`).toBe(true);
    return ((await verify.json()) as { accessToken: string }).accessToken;
  }

  async function adminCall(
    request: APIRequestContext,
    method: 'GET' | 'DELETE',
    path: string,
    data?: object,
  ): Promise<APIResponse> {
    return request.fetch(`${API}/api/v1/admin/real${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${adminToken}`,
        'Content-Type': 'application/json',
        ...(method === 'GET' ? {} : { 'Idempotency-Key': randomUUID() }),
      },
      data,
    });
  }

  async function botWallet(request: APIRequestContext): Promise<BotWallet> {
    const response = await request.get(`${API}/api/v1/real/bot-wallet`, {
      headers: { Authorization: `Bearer ${guestToken}` },
    });
    expect(response.ok()).toBe(true);
    return (await response.json()) as BotWallet;
  }

  async function killSwitch(request: APIRequestContext): Promise<KillSwitch> {
    const response = await adminCall(request, 'GET', '/kill-switch');
    expect(response.ok()).toBe(true);
    return (await response.json()) as KillSwitch;
  }

  /** The shell reached a final state of the screen, on the expected path (a lost session fails at once). */
  async function open(path: string, ready: string) {
    await page.goto(path);
    await expect(page.getByTestId(ready)).toBeVisible({ timeout: 30_000 });
    expect(new URL(page.url()).pathname, 'the session was lost on the navigation').toBe(path);
  }

  test.beforeAll(async ({ browser, request }) => {
    test.setTimeout(LOGIN_WINDOW_MS * 4 + 120_000);
    const adminFile = process.env.SMOKE_ADMIN_WALLET_FILE;
    const adminBaseUrl = process.env.ADMIN_BASE_URL;
    if (!adminFile || !adminBaseUrl) {
      throw new Error(
        'the REAL screens spec needs SMOKE_ADMIN_WALLET_FILE and ADMIN_BASE_URL (scripts/verify.sh)',
      );
    }
    main = loadSmokeWallet();
    admin = loadSmokeWallet(adminFile);
    guestToken = await signIn(request, main, '/api/v1/auth');
    adminToken = await signIn(request, admin, '/api/v1/admin/auth');

    // The entry state is checked, never fixed: a wrong one fails with what was found.
    const wallet = await botWallet(request);
    const kill = await killSwitch(request);
    const divergences = await adminCall(request, 'GET', '/divergences?limit=50');
    expect(divergences.ok()).toBe(true);
    const open = ((await divergences.json()) as { items: Divergence[] }).items.filter(
      (item) => item.state === 'OPEN' || item.state === 'COMPENSATED',
    );
    expect(
      {
        status: wallet.status,
        mode: wallet.mode,
        killSwitch: kill.active,
        openDivergences: open.length,
      },
      'entry state of the REAL screens spec (left by real-swap and real-ops)',
    ).toEqual({ status: 'ACTIVE', mode: 'AUTO_FULL', killSwitch: false, openDivergences: 0 });
    entryCap = wallet.capLamports;

    page = await browser.newPage();
    await installTestWallet(page, main);
    await signInWithinLoginLimit(page);
    adminContext = await browser.newContext({ baseURL: adminBaseUrl, locale: 'pt-BR' });
    adminPage = await adminContext.newPage();
    await installTestWallet(adminPage, admin);
    await adminPage.goto('/');
    await adminPage.getByTestId('admin-sign-in-connect-phantom').click();
    await expect(adminPage.getByTestId('admin-navigation')).toBeVisible({ timeout: 30_000 });
  });

  test.afterAll(async ({ request }) => {
    test.setTimeout(LOGIN_WINDOW_MS + 60_000);
    // Exit state equal to the entry: AUTO_FULL, the same cap, kill switch off.
    if (adminToken && (await killSwitch(request)).active) {
      await adminCall(request, 'DELETE', '/kill-switch', {
        reason: 'fim do teste de ponta a ponta (garantia)',
      });
    }
    if (guestToken) {
      const wallet = await botWallet(request);
      expect(wallet.mode, 'exit state: the mode of the entry').toBe('AUTO_FULL');
      expect(wallet.capLamports, 'exit state: the cap of the entry').toBe(entryCap);
    }
    await page?.close();
    await adminContext?.close();
    // The specs after this one sign in from the same IP: leave them a whole login window.
    await new Promise((resolve) => setTimeout(resolve, LOGIN_WINDOW_MS));
  });

  test('1. overview: balance, P&L REAL apart, the time of the balance; the cap goes down straight and up with the consent', async ({
    request,
  }) => {
    await open('/real', 'real-overview-free');
    await expect(page.getByTestId('real-overview-mode')).toContainText('Automática total');
    await expect(page.getByTestId('real-pnl-card')).toContainText('REAL');
    await expect(page.getByTestId('real-overview-as-of')).toBeVisible();

    // Down: straight, with PATCH, no consent.
    const lowered = (BigInt(entryCap) / 2n).toString();
    const loweredText = `${lowered.slice(0, -9) || '0'},${lowered.slice(-9).padStart(9, '0')}`;
    await page.getByTestId('real-cap-input').fill(loweredText);
    await expect(page.getByText('Baixar o teto vale na hora, sem assinatura.')).toBeVisible();
    await page.getByTestId('real-cap-save').click();
    await expect(page.getByTestId('real-cap-notice')).toContainText('Teto salvo.');
    expect((await botWallet(request)).capLamports).toBe(lowered);

    // Up again to the cap of the entry: the consent RAISE_CAP signed by the test main wallet.
    const entryText = `${entryCap.slice(0, -9) || '0'},${entryCap.slice(-9).padStart(9, '0')}`;
    await page.getByTestId('real-cap-input').fill(entryText);
    await expect(
      page.getByText('Subir o teto pede uma assinatura de consentimento na sua carteira.'),
    ).toBeVisible();
    await page.getByTestId('real-cap-save').click();
    await page.getByTestId('real-cap-confirm-submit').click();
    await expect(page.getByTestId('real-cap-notice')).toContainText('Teto salvo.', {
      timeout: 30_000,
    });
    expect((await botWallet(request)).capLamports).toBe(entryCap);
  });

  test('2. mode: back to SIGNED with the confirmation only, then up to AUTO_FULL with the consent, by keyboard', async ({
    request,
  }) => {
    await open('/real/modo', 'real-mode-form');
    await page.getByLabel('Assinada', { exact: true }).focus();
    await page.keyboard.press('Space');
    await page.getByTestId('real-mode-save').focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('alertdialog')).toContainText(
      'As ordens ainda não enviadas serão canceladas',
    );
    await expect(page.getByTestId('real-mode-confirm-cancel')).toBeFocused();
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('real-mode-notice')).toContainText('Modo Assinada salvo.');
    expect((await botWallet(request)).mode).toBe('SIGNED');

    await page.getByLabel('Automática total').focus();
    await page.keyboard.press('Space');
    await page.getByTestId('real-mode-save').focus();
    await page.keyboard.press('Enter');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('real-mode-notice')).toContainText(
      'Modo Automática total salvo.',
      {
        timeout: 30_000,
      },
    );
    expect((await botWallet(request)).mode).toBe('AUTO_FULL');
  });

  test('3. orders: the confirmed order of real-swap with "Confirmada"; on the local network no link, the signature abbreviated', async () => {
    await open('/real/ordens', 'real-orders-table');
    const confirmed = page.getByTestId('real-orders-table').getByText('Confirmada').first();
    await expect(confirmed).toBeVisible();
    await expect(page.getByTestId('real-orders-table').locator('a[href*="solscan"]')).toHaveCount(
      0,
    );
    await expect(
      page
        .getByTestId('real-orders-table')
        .locator('[data-testid^="real-order-row-signature-"]')
        .first(),
    ).toContainText('…');
  });

  test('4. deposit and withdrawal: the destination abbreviated without any field, and the confirmation names value and destination', async () => {
    await open('/real/deposito-saque', 'real-withdraw-available');
    const short = `${main.address.slice(0, 4)}…${main.address.slice(-4)}`;
    await expect(page.getByTestId('real-withdraw-destination')).toContainText(short);
    await expect(page.getByTestId('real-withdraw')).not.toContainText(main.address);
    await page.getByTestId('real-withdraw-amount').fill('0,000000001');
    await page.getByTestId('real-withdraw-submit').click();
    await expect(page.getByTestId('real-withdraw-confirm-amount')).toHaveText('0,000000001 SOL');
    await expect(page.getByTestId('real-withdraw-confirm-destination')).toHaveText(short);
    await page.getByTestId('real-withdraw-confirm-cancel').click();
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
  });

  test('5. administrator: the smoke account released, "Liberar" refused, the kill switch REAL on and off, divergences and restore', async ({
    request,
  }) => {
    const users = await adminCall(request, 'GET', '/users?limit=50');
    const wallet = await request.get(`${API}/api/v1/real/bot-wallet`, {
      headers: { Authorization: `Bearer ${guestToken}` },
    });
    const botAddress = ((await wallet.json()) as { address: string }).address;
    const user = ((await users.json()) as { items: AdminUser[] }).items.find(
      (item) => item.botWalletAddress === botAddress,
    );
    expect(user, 'the smoke account is in the list').toBeDefined();

    await adminPage.getByTestId('admin-nav-real').click();
    const row = adminPage.getByTestId(`admin-real-user-${user!.userId}`);
    await expect(row).toContainText('Ativa');
    await adminPage.getByTestId(`admin-real-user-detail-${user!.userId}`).click();
    await adminPage.getByTestId('admin-real-release').click();
    await adminPage.getByTestId('admin-real-network').selectOption('LOCALNET');
    await adminPage
      .getByTestId('admin-real-release-dialog-reason')
      .fill('teste de ponta a ponta: liberação repetida');
    await adminPage.getByTestId('admin-real-release-dialog-submit').click();
    await expect(adminPage.getByTestId('admin-real-release-dialog-error')).toContainText(
      'Esta pessoa já tem uma liberação aberta nesta rede.',
    );
    await adminPage.getByTestId('admin-real-release-dialog-cancel').click();

    await adminPage.getByTestId('admin-real-nav-kill-switch').click();
    await expect(adminPage.getByRole('heading', { level: 1 })).toContainText('Kill switch REAL');
    try {
      await adminPage.getByTestId('admin-real-kill-switch-toggle').click();
      await adminPage
        .getByTestId('admin-real-kill-switch-dialog-reason')
        .fill('teste de ponta a ponta do kill switch REAL');
      await adminPage.getByTestId('admin-real-kill-switch-dialog-submit').click();
      await expect(adminPage.getByTestId('admin-real-kill-switch-state')).toContainText(
        'Novas ordens REAL bloqueadas para todos; saques liberados',
      );
      await adminPage.getByTestId('admin-real-kill-switch-toggle').click();
      await adminPage
        .getByTestId('admin-real-kill-switch-dialog-reason')
        .fill('fim do teste de ponta a ponta');
      await adminPage.getByTestId('admin-real-kill-switch-dialog-submit').click();
      await expect(adminPage.getByTestId('admin-real-kill-switch-state')).toContainText(
        'Desligado',
      );
    } finally {
      if ((await killSwitch(request)).active) {
        await adminCall(request, 'DELETE', '/kill-switch', {
          reason: 'fim do teste de ponta a ponta (finally)',
        });
      }
    }
    expect((await killSwitch(request)).active).toBe(false);

    await adminPage.getByTestId('admin-real-nav-divergences').click();
    await expect(
      adminPage
        .getByTestId('admin-real-divergences-list')
        .or(adminPage.getByTestId('admin-real-divergences-empty'))
        .first(),
    ).toBeVisible();
    await expect(adminPage.getByTestId('admin-real-divergences-error')).toHaveCount(0);
    await adminPage.getByTestId('admin-real-nav-restore').click();
    await expect(adminPage.getByTestId('admin-real-restore-confirm')).toBeVisible();
  });

  test('6. at 320 px the REAL screens have no horizontal scroll and the focus is visible', async () => {
    await page.setViewportSize({ width: 320, height: 800 });
    for (const [path, ready] of [
      ['/real', 'real-overview-free'],
      ['/real/ordens', 'real-orders-cards'],
      ['/real/deposito-saque', 'real-withdraw-available'],
    ] as const) {
      await open(path, ready);
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      );
      expect(overflow, `horizontal scroll on ${path}`).toBeLessThanOrEqual(0);
    }
    // Reached by the keyboard, so the browser shows :focus-visible (U6-NFR6.6).
    await page.getByTestId('real-withdraw-amount').focus();
    await page.keyboard.press('Tab');
    const button = page.getByTestId('real-withdraw-submit');
    await expect(button).toBeFocused();
    const outline = await button.evaluate((element) => getComputedStyle(element).outlineStyle);
    expect(outline).not.toBe('none');
    await page.setViewportSize({ width: 1440, height: 900 });
  });
});
