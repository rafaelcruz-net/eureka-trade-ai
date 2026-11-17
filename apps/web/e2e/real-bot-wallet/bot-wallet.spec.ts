import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import {
  generateTestWallet,
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
import { LAMPORTS_PER_SOL, airdrop, balance, encode, transfer } from './local-validator';

const API = process.env.API_BASE_URL ?? 'http://localhost:8080';

/**
 * Walking skeleton of 261113-trading-real-bot (team.md "Fatia do intent", U1 u1-bot-wallet): browser → API →
 * BotWallet → TxSigner (kms-sim) → ChainWatcher → local validator → REAL ledger. Activates the bot wallet with the
 * consent signed by the test main wallet (generated in test code), deposits 1 SOL from it, sees the balance, withdraws
 * everything back to the main wallet and checks that a withdrawal to another address is refused. Needs the REAL
 * overlay infra/docker-compose.real.yml (scripts/verify.sh); nothing here talks to devnet, mainnet or a real KMS.
 *
 * 261113-trading-real-bot U6 (infrastructure-specification.md "Ativação pela tela nova"): the minimal screen
 * `/real/carteira` now redirects; the activation goes through `/real/ativacao` with the keyboard (the terms when they
 * are still to accept, then the cap and the consent, then the address), and the deposit and the withdrawal through
 * `/real/deposito-saque`.
 */
test.describe.serial('REAL bot wallet on the local validator', () => {
  test.setTimeout(240_000);

  let main: SmokeWallet;
  let botAddress = '';
  // One sign-in for the whole serial run: the login is limited per minute (memetrade.identity
  // login-rate-limit-per-minute) and the specs before this one already sign in with the same smoke wallet.
  let page: Page;

  test.beforeAll(async ({ browser }) => {
    test.setTimeout(300_000);
    main = loadSmokeWallet();
    page = await browser.newPage();
    await installTestWallet(page, main);
    await signInWithinLoginLimit(page);
  });

  test.afterAll(async () => {
    test.setTimeout(LOGIN_WINDOW_MS + 30_000);
    await page?.close();
    // The specs that run after this file (u1, u2, ...) sign in from the same IP: leave them a whole login window.
    await new Promise((resolve) => setTimeout(resolve, LOGIN_WINDOW_MS));
  });

  /**
   * Every page load renews the session ONCE through the refresh cookie, and the server rotates that cookie with reuse
   * detection (BR1.15): a refresh token used twice revokes the whole session. If the page is reloaded while that
   * renewal is still in flight, the browser drops the answer with the new cookie, the next load sends the old one and
   * the session is revoked: from then on every load lands on /entrar. So no navigation here starts before the previous
   * one reached a final state of the screen, and a lost session fails at once, by its URL, instead of looking like a
   * missing balance.
   */
  async function settleWithdrawal() {
    await expect(
      page
        .getByTestId('real-withdraw-available')
        .or(page.getByTestId('real-wallet-error'))
        .or(page.getByTestId('login-connect-phantom'))
        .first(),
    ).toBeVisible({ timeout: 30_000 });
    expect(new URL(page.url()).pathname, 'the session was lost on the navigation').toBe(
      '/real/deposito-saque',
    );
    await expect(page.getByTestId('real-wallet-error')).toHaveCount(0);
  }

  async function openWithdrawal() {
    await page.goto('/real/deposito-saque');
    await settleWithdrawal();
  }

  async function reloadWithdrawal() {
    await page.reload();
    await settleWithdrawal();
  }

  const short = (address: string) => `${address.slice(0, 4)}…${address.slice(-4)}`;

  test('activates the bot wallet through /real/ativacao with the keyboard and the consent of the main wallet', async () => {
    await page.goto('/real/carteira');
    // BR1.3 and BR1.2: the old screen goes to /real, which sends a person without a wallet to the activation.
    await page.waitForURL((url) => url.pathname === '/real/ativacao', { timeout: 30_000 });
    const consent = page.getByTestId('real-activation-consent');
    const terms = page.getByTestId('real-activation-terms');
    await expect(consent.or(terms).first()).toBeVisible({ timeout: 30_000 });
    if (await terms.isVisible()) {
      await page.getByTestId('real-terms-box').focus();
      await page.keyboard.press('Space');
      await page.keyboard.press('Tab');
      await expect(page.getByTestId('real-terms-continue')).toBeFocused();
      await page.keyboard.press('Enter');
      await expect(consent).toBeVisible({ timeout: 30_000 });
    }
    await expect(consent).toContainText('Esta assinatura não é a do login e não movimenta fundos.');

    const cap = page.getByTestId('real-activation-cap');
    await cap.focus();
    await cap.fill('1');
    await page.keyboard.press('Tab');
    await expect(page.getByTestId('real-activation-sign')).toBeFocused();
    await page.keyboard.press('Enter');
    // The confirmation starts on "Cancelar"; Tab reaches "Confirmar".
    await expect(page.getByTestId('real-activation-confirm-cancel')).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(page.getByTestId('real-activation-confirm-submit')).toBeFocused();
    await page.keyboard.press('Enter');

    const address = page.getByTestId('real-activation-address');
    await expect(address).toBeVisible({ timeout: 60_000 });
    botAddress = (await address.textContent())?.trim() ?? '';
    expect(botAddress).toMatch(/^[1-9A-HJ-NP-Za-km-z]{32,44}$/);
    await expect(page.getByTestId('real-activation-copy')).toBeVisible();
    await expect(page.getByTestId('real-activation-deposit-link')).toHaveAttribute(
      'href',
      '/real/deposito-saque',
    );
  });

  test('a deposit of 1 SOL from the main wallet appears in the balance', async () => {
    await airdrop(main.address, 2n * LAMPORTS_PER_SOL);
    await transfer(main, botAddress, LAMPORTS_PER_SOL);

    await openWithdrawal();
    await expect(page.getByTestId('real-deposit-address')).toHaveText(botAddress);
    await expect(page.getByTestId('real-withdraw-destination')).toContainText(short(main.address));
    await expect(async () => {
      await reloadWithdrawal();
      await expect(page.getByTestId('real-withdraw-available')).not.toHaveText('0,000000000 SOL', {
        timeout: 2_000,
      });
    }).toPass({ timeout: 120_000, intervals: [5_000] });
    await page.goto('/real');
    await expect(page.getByTestId('real-overview-fee-reserve')).toHaveText('0,030000000 SOL', {
      timeout: 30_000,
    });
  });

  test('withdrawing everything goes back to the main wallet', async () => {
    const before = await balance(main.address);

    await openWithdrawal();
    await page.getByTestId('real-withdraw-all').click();
    await expect(page.getByTestId('real-withdraw-confirm-destination')).toHaveText(
      short(main.address),
    );
    await page.getByTestId('real-withdraw-confirm-submit').click();
    await expect(page.getByTestId('real-withdraw-state')).toContainText('Saque:');

    await expect(async () => {
      expect(await balance(main.address)).toBeGreaterThan(before + LAMPORTS_PER_SOL - 10_000n);
    }).toPass({ timeout: 150_000, intervals: [5_000] });
    await expect(async () => {
      await reloadWithdrawal();
      await expect(page.getByTestId('real-withdraw-available')).toHaveText('0,000000000 SOL', {
        timeout: 2_000,
      });
    }).toPass({ timeout: 90_000, intervals: [5_000] });
    expect(await balance(botAddress)).toBe(0n);
  });

  test('a withdrawal to another address is refused', async ({ request }) => {
    const challenge = await postWithinLoginLimit(request, `${API}/api/v1/auth/challenge`, {
      walletAddress: main.address,
    });
    expect(challenge.ok()).toBe(true);
    const { message } = (await challenge.json()) as { message: string };
    const verify = await postWithinLoginLimit(request, `${API}/api/v1/auth/verify`, {
      walletAddress: main.address,
      message,
      signature: encode(signWith(main, message)),
      provider: 'PHANTOM',
    });
    expect(verify.ok()).toBe(true);
    const { accessToken } = (await verify.json()) as { accessToken: string };
    const stranger = generateTestWallet().address;

    const refused = await request.post(`${API}/api/v1/real/bot-wallet/withdrawals`, {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': randomUUID(),
        Authorization: `Bearer ${accessToken}`,
      },
      data: { amount: { lamports: '1000' }, destination: stranger },
    });

    expect(refused.status()).toBe(400);
    expect((await refused.json()).code).toBe('VALIDATION_FAILED');
    expect(await balance(stranger)).toBe(0n);
  });
});
