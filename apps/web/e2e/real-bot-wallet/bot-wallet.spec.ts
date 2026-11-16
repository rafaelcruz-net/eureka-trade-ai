import { randomUUID } from 'node:crypto';
import {
  expect,
  test,
  type APIRequestContext,
  type APIResponse,
  type Page,
} from '@playwright/test';
import {
  generateTestWallet,
  installTestWallet,
  loadSmokeWallet,
  signWith,
  type SmokeWallet,
} from '../support/test-wallet';
import { LAMPORTS_PER_SOL, airdrop, balance, encode, transfer } from './local-validator';

const API = process.env.API_BASE_URL ?? 'http://localhost:8080';

/**
 * Walking skeleton of 261113-trading-real-bot (team.md "Fatia do intent", U1 u1-bot-wallet): browser → API →
 * BotWallet → TxSigner (kms-sim) → ChainWatcher → local validator → REAL ledger. Activates the bot wallet with the
 * consent signed by the test main wallet (generated in test code), deposits 1 SOL from it, sees the balance, withdraws
 * everything back to the main wallet and checks that a withdrawal to another address is refused. Needs the REAL
 * overlay infra/docker-compose.real.yml (scripts/verify.sh); nothing here talks to devnet, mainnet or a real KMS.
 */
test.describe.serial('REAL bot wallet on the local validator', () => {
  test.setTimeout(240_000);

  let main: SmokeWallet;
  let botAddress = '';
  // One sign-in for the whole serial run: the login is limited per minute (memetrade.identity
  // login-rate-limit-per-minute) and the specs before this one already sign in with the same smoke wallet.
  let page: Page;

  const LOGIN_WINDOW_MS = 65_000;

  test.beforeAll(async ({ browser }) => {
    test.setTimeout(300_000);
    main = loadSmokeWallet();
    page = await browser.newPage();
    await installTestWallet(page, main);
    // The login limit counts challenges and verifications per IP (LoginRateLimiter), and every spec before this one
    // signs in from the same machine: a refusal is waited out once per window, at most three times.
    for (let attempt = 1; ; attempt++) {
      await page.goto('/entrar?origem=/scanner');
      await page.getByTestId('login-connect-phantom').click();
      // Each wait is bounded and the loser of the race is settled, so nothing is left pending on the page.
      const outcome = await Promise.race([
        page
          .waitForURL((url) => url.pathname === '/scanner', { timeout: 30_000 })
          .then(() => 'signed-in' as const)
          .catch(() => 'timeout' as const),
        page
          .getByText('Muitas tentativas')
          .waitFor({ timeout: 30_000 })
          .then(() => 'rate-limited' as const)
          .catch(() => 'timeout' as const),
      ]);
      expect(outcome, 'the login neither finished nor was rate limited').not.toBe('timeout');
      if (outcome === 'signed-in') {
        return;
      }
      expect(attempt, 'login still rate limited after three windows').toBeLessThan(3);
      await page.waitForTimeout(LOGIN_WINDOW_MS);
    }
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
   * the session is revoked: from then on every load lands on /entrar. That is how the deposit test failed in the CI
   * (run 43777057925): it reloaded right after the navigation, and then every 2 s, without waiting for the screen. So
   * no navigation here starts before the previous one reached a final state of the screen, and a lost session fails at
   * once, by its URL, instead of looking like a missing balance.
   */
  async function settleWallet() {
    await expect(
      page
        .getByTestId('real-wallet-total')
        .or(page.getByTestId('real-wallet-activation'))
        .or(page.getByTestId('real-wallet-error'))
        .or(page.getByTestId('login-connect-phantom'))
        .first(),
    ).toBeVisible({ timeout: 30_000 });
    expect(new URL(page.url()).pathname, 'the session was lost on the navigation').toBe(
      '/real/carteira',
    );
    await expect(page.getByTestId('real-wallet-error')).toHaveCount(0);
  }

  async function openWallet() {
    await page.goto('/real/carteira');
    await settleWallet();
  }

  async function reloadWallet() {
    await page.reload();
    await settleWallet();
  }

  /** POST that waits out one 429 of the login rate limit before giving up. */
  async function postWithinLoginLimit(
    request: APIRequestContext,
    url: string,
    data: object,
  ): Promise<APIResponse> {
    const send = () =>
      request.post(url, {
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
        data,
      });
    const first = await send();
    if (first.status() !== 429) {
      return first;
    }
    await new Promise((resolve) => setTimeout(resolve, LOGIN_WINDOW_MS));
    return send();
  }

  test('activates the bot wallet with the consent signed by the main wallet', async () => {
    await openWallet();
    await expect(page.getByTestId('real-wallet-activation')).toBeVisible();
    await page.getByTestId('real-wallet-cap-input').fill('1');
    await page.getByTestId('real-wallet-activate-button').click();

    const address = page.getByTestId('real-wallet-address');
    await expect(address).toBeVisible();
    botAddress = (await address.textContent())?.trim() ?? '';
    expect(botAddress).toMatch(/^[1-9A-HJ-NP-Za-km-z]{32,44}$/);
    await expect(page.getByTestId('real-wallet-main')).toHaveText(main.address);
    await expect(page.getByTestId('real-wallet-total')).toHaveText('0 SOL');
  });

  test('a deposit of 1 SOL from the main wallet appears in the balance', async () => {
    await airdrop(main.address, 2n * LAMPORTS_PER_SOL);
    await transfer(main, botAddress, LAMPORTS_PER_SOL);

    await openWallet();
    await expect(async () => {
      await reloadWallet();
      await expect(page.getByTestId('real-wallet-total')).toHaveText('1 SOL', { timeout: 2_000 });
    }).toPass({ timeout: 120_000, intervals: [5_000] });
    await expect(page.getByTestId('real-wallet-fee-reserve')).toHaveText('0,03 SOL');
  });

  test('withdrawing everything goes back to the main wallet', async () => {
    const before = await balance(main.address);

    await openWallet();
    await page.getByTestId('real-wallet-withdraw-all-button').click();
    await expect(page.getByTestId('real-wallet-withdrawal-state')).toContainText('REQUESTED');

    await expect(async () => {
      expect(await balance(main.address)).toBeGreaterThan(before + LAMPORTS_PER_SOL - 10_000n);
    }).toPass({ timeout: 150_000, intervals: [5_000] });
    await expect(async () => {
      await reloadWallet();
      await expect(page.getByTestId('real-wallet-total')).toHaveText('0 SOL', { timeout: 2_000 });
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
