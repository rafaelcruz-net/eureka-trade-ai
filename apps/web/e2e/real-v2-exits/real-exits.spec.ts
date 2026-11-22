import { randomUUID } from 'node:crypto';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
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
import { LAMPORTS_PER_SOL, balance, encode } from '../real-bot-wallet/local-validator';

const API = process.env.API_BASE_URL ?? 'http://localhost:8080';
/** The read and cancel jobs of the worker run every 30 s and 1 min; a final state comes within a few runs. */
const FINAL_WITHIN_MS = 180_000;

type BotWallet = { mode: string; status: string };
type Position = { mint: string; baseUnits: string };
type Quote = { priceStatus: 'KNOWN' | 'UNKNOWN'; priceUsd?: string };
type Conditional = { conditionalOrderId: string; state: string };
type Order = { side: string; state: string; input: { mint: string } };

/**
 * 261113-trading-real-bot U7 (infrastructure-specification.md "Spec `apps/web/e2e/real-exits/`", U7-NFR8.5; BR4, BR6.1,
 * BR8): the conditional exits through the screens on the local validator, with the SIMULATED Trigger adapter that
 * exists only in the test profile (infra/docker-compose.real.yml turns it on for this run; RealProperties refuses it
 * outside LOCALNET). The simulated adapter never signs with the bot key nor calls the TxSigner, and nothing goes to
 * the network: the consent is signed by the test main wallet with signMessage.
 *
 * THIS SPEC IS ALWAYS THE LAST REAL SPEC: Playwright (workers: 1) runs the spec files in the alphabetical order of
 * their paths, not in the order of the list in scripts/verify.sh, so the folder is named real-v2-exits to come after
 * real-ui and real-v1-exports (regression of the CI run 44113862674, where real-exits ran before real-swap). Only
 * e2e/zz-paper-real-unico (261121-paper-real-unico U1), the last spec of all, reads its exit state: the bot wallet in
 * AUTO_FULL (checked there, never fixed); nothing depends on its other rows (real_conditional_order and the audit). There is no simulated fill (the fill has no e2e until the real recording, the
 * gap accepted in code-summary.md); the position of real-swap is sold at the end by "vender tudo e sacar" (test 4,
 * moved here from e2e/real-swap).
 * - Entry state (from real-ui): bot wallet ACTIVE in AUTO_FULL, kill switch REAL off, a position of real-swap. Checked
 *   in `beforeAll`, never fixed.
 * - Scenarios: the mode to "Automática nas saídas"; an OCO by the screen, OPEN in the tab "Condicionais"; the
 *   cancellation by DELETE, CANCELLED; another one cancelled by the kill switch REAL, inside try/finally.
 * - Exit state: AUTO_FULL again (with the consent RAISE_MODE), the kill switch off, the position sold and the balance
 *   back in the test main wallet.
 */
test.describe.serial('REAL conditional exits on the local validator (always the last spec)', () => {
  test.setTimeout(300_000);

  let main: SmokeWallet;
  let admin: SmokeWallet;
  let guestToken = '';
  let adminToken = '';
  let mint = '';
  let page: Page;

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

  function guest(
    request: APIRequestContext,
    method: 'GET' | 'POST' | 'PUT' | 'DELETE',
    path: string,
    data?: object,
  ) {
    return request.fetch(`${API}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${guestToken}`,
        'Content-Type': 'application/json',
        ...(method === 'GET' ? {} : { 'Idempotency-Key': randomUUID() }),
      },
      data,
    });
  }

  function killSwitch(
    request: APIRequestContext,
    method: 'GET' | 'PUT' | 'DELETE',
    reason?: string,
  ) {
    return request.fetch(`${API}/api/v1/admin/real/kill-switch`, {
      method,
      headers: {
        Authorization: `Bearer ${adminToken}`,
        'Content-Type': 'application/json',
        ...(method === 'GET' ? {} : { 'Idempotency-Key': randomUUID() }),
      },
      data: reason ? { reason } : undefined,
    });
  }

  async function killSwitchActive(request: APIRequestContext): Promise<boolean> {
    const response = await killSwitch(request, 'GET');
    expect(response.ok()).toBe(true);
    return ((await response.json()) as { active: boolean }).active;
  }

  async function botWallet(request: APIRequestContext): Promise<BotWallet> {
    const response = await guest(request, 'GET', '/api/v1/real/bot-wallet');
    expect(response.ok()).toBe(true);
    return (await response.json()) as BotWallet;
  }

  async function conditional(request: APIRequestContext, id: string): Promise<Conditional> {
    const response = await guest(request, 'GET', '/api/v1/real/conditional-orders?limit=50');
    expect(response.ok()).toBe(true);
    const found = ((await response.json()) as { items: Conditional[] }).items.find(
      (item) => item.conditionalOrderId === id,
    );
    expect(found, `the conditional ${id} is in the list`).toBeDefined();
    return found!;
  }

  /** The decimal text `price × numerator / denominator`, exact at 18 places (no floating point). */
  function times(price: string, numerator: bigint, denominator: bigint): string {
    const [whole, fraction = ''] = price.split('.');
    const scaled = BigInt(whole!) * 10n ** 18n + BigInt(fraction.padEnd(18, '0').slice(0, 18));
    const result = (scaled * numerator) / denominator;
    const digits = result.toString().padStart(19, '0');
    const text = `${digits.slice(0, -18)}.${digits.slice(-18)}`.replace(/\.?0+$/, '');
    return text;
  }

  /** Back to AUTO_FULL with the consent RAISE_MODE signed by the test main wallet (exit state). */
  async function backToAutoFull(request: APIRequestContext) {
    if ((await botWallet(request)).mode === 'AUTO_FULL') {
      return;
    }
    const challenge = await guest(request, 'POST', '/api/v1/real/consents/challenge', {
      kind: 'RAISE_MODE',
      mode: 'AUTO_FULL',
    });
    expect(challenge.ok(), 'challenge RAISE_MODE').toBe(true);
    const { challengeId, message } = (await challenge.json()) as {
      challengeId: string;
      message: string;
    };
    const saved = await guest(request, 'PUT', '/api/v1/real/mode', {
      mode: 'AUTO_FULL',
      challengeId,
      signature: encode(signWith(main, message)),
    });
    expect(saved.ok(), 'the mode back to AUTO_FULL').toBe(true);
  }

  test.beforeAll(async ({ browser, request }) => {
    test.setTimeout(LOGIN_WINDOW_MS * 4 + 120_000);
    const adminFile = process.env.SMOKE_ADMIN_WALLET_FILE;
    if (!adminFile) {
      throw new Error(
        'the conditional exits spec needs SMOKE_ADMIN_WALLET_FILE (scripts/verify.sh)',
      );
    }
    main = loadSmokeWallet();
    admin = loadSmokeWallet(adminFile);
    guestToken = await signIn(request, main, '/api/v1/auth');
    adminToken = await signIn(request, admin, '/api/v1/admin/auth');

    // The entry state is checked, never fixed: a wrong one fails with what was found.
    const wallet = await botWallet(request);
    const pnl = await guest(request, 'GET', '/api/v1/real/pnl');
    expect(pnl.ok()).toBe(true);
    const position = ((await pnl.json()) as { positions: Position[] }).positions.find(
      (item) => BigInt(item.baseUnits) > 0n,
    );
    expect(
      {
        status: wallet.status,
        mode: wallet.mode,
        killSwitch: await killSwitchActive(request),
        position: position !== undefined,
      },
      'entry state of the conditional exits spec (left by real-swap and real-ui)',
    ).toEqual({ status: 'ACTIVE', mode: 'AUTO_FULL', killSwitch: false, position: true });
    mint = position!.mint;

    page = await browser.newPage();
    await installTestWallet(page, main);
    await signInWithinLoginLimit(page);
  });

  test.afterAll(async ({ request }) => {
    test.setTimeout(LOGIN_WINDOW_MS + 60_000);
    // Exit state, also after a failure: kill switch off and AUTO_FULL.
    if (adminToken && (await killSwitchActive(request))) {
      await killSwitch(request, 'DELETE', 'fim do teste de ponta a ponta das saídas (garantia)');
    }
    if (guestToken) {
      await backToAutoFull(request);
      expect((await botWallet(request)).mode, 'exit state: AUTO_FULL').toBe('AUTO_FULL');
    }
    await page?.close();
  });

  test('1. the mode goes to "Automática nas saídas", offered because the exits are on', async ({
    request,
  }) => {
    await page.goto('/real/modo');
    await expect(page.getByTestId('real-mode-form')).toBeVisible({ timeout: 30_000 });
    const option = page.getByLabel('Automática nas saídas', { exact: true });
    await expect(option).toBeEnabled();
    await option.check();
    await page.getByTestId('real-mode-save').click();
    await page.getByTestId('real-mode-confirm-submit').click();
    await expect(page.getByTestId('real-mode-notice')).toContainText('Automática nas saídas', {
      timeout: 30_000,
    });
    expect((await botWallet(request)).mode).toBe('AUTO_EXITS');
  });

  test('2. an OCO by the screen, signed by the test main wallet, OPEN in the tab "Condicionais"; then cancelled by DELETE', async ({
    request,
  }) => {
    const quote = await guest(
      request,
      'GET',
      `/api/v1/real/conditional-orders/quote?positionMint=${encodeURIComponent(mint)}`,
    );
    expect(quote.ok()).toBe(true);
    const { priceStatus, priceUsd } = (await quote.json()) as Quote;
    expect(priceStatus, 'the position of real-swap has a current price in USD').toBe('KNOWN');

    await page.goto('/real');
    await page.getByTestId(`real-exits-define-${mint}`).click();
    await expect(page.getByTestId('real-exits-price')).toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId('real-exits-stop-warning')).toBeVisible();
    await page.getByTestId('real-exits-stop').fill(times(priceUsd!, 8n, 10n));
    await page.getByTestId('real-exits-take-profit').fill(times(priceUsd!, 3n, 2n));
    await page.getByTestId('real-exits-confirm').click();
    await expect(page.getByTestId('real-exits-created')).toBeVisible({ timeout: 30_000 });

    await page.goto('/real/ordens');
    await page.getByTestId('real-orders-tab-conditionals').click();
    const list = page.getByTestId('real-exits-list');
    await expect(list).toContainText('Aberta', { timeout: 30_000 });
    const created = await guest(request, 'GET', '/api/v1/real/conditional-orders?limit=1');
    const id = ((await created.json()) as { items: Conditional[] }).items[0]!.conditionalOrderId;
    expect((await conditional(request, id)).state).toBe('OPEN');

    // The cancellation by the person: the DELETE of the button, with its confirmation.
    await page.getByTestId(`real-exits-cancel-${id}`).click();
    await page.getByTestId('real-exits-cancel-confirm-submit').click();
    await expect
      .poll(async () => (await conditional(request, id)).state, { timeout: FINAL_WITHIN_MS })
      .toBe('CANCELLED');
    await page.reload();
    await page.getByTestId('real-orders-tab-conditionals').click();
    await expect(page.getByTestId(`real-exits-state-${id}`)).toContainText('Cancelada');
  });

  test('3. the kill switch REAL cancels an open conditional (BR6.1), inside try/finally', async ({
    request,
  }) => {
    const quote = await guest(
      request,
      'GET',
      `/api/v1/real/conditional-orders/quote?positionMint=${encodeURIComponent(mint)}`,
    );
    const { priceUsd } = (await quote.json()) as Quote;
    const created = await guest(request, 'POST', '/api/v1/real/conditional-orders', {
      positionMint: mint,
      kind: 'STOP',
      stopPriceUsd: times(priceUsd!, 7n, 10n),
    });
    expect(created.status(), 'the second conditional').toBe(201);
    const { conditionalOrderId: id, signingSession } = (await created.json()) as {
      conditionalOrderId: string;
      signingSession: { kind: string; message: string };
    };
    expect(signingSession.kind).toBe('MESSAGE');
    const signed = await guest(request, 'POST', `/api/v1/real/conditional-orders/${id}/signature`, {
      signature: encode(signWith(main, signingSession.message)),
    });
    expect(signed.ok()).toBe(true);
    expect((await conditional(request, id)).state).toBe('OPEN');

    try {
      const on = await killSwitch(request, 'PUT', 'teste de ponta a ponta das saídas automáticas');
      expect(on.ok(), 'kill switch REAL on').toBe(true);
      await expect
        .poll(async () => (await conditional(request, id)).state, { timeout: FINAL_WITHIN_MS })
        .toBe('CANCELLED');
    } finally {
      if (await killSwitchActive(request)) {
        await killSwitch(request, 'DELETE', 'fim do teste de ponta a ponta das saídas automáticas');
      }
    }
    expect(await killSwitchActive(request)).toBe(false);
    await page.goto('/real/ordens');
    await page.getByTestId('real-orders-tab-conditionals').click();
    await expect(page.getByTestId(`real-exits-state-${id}`)).toContainText('Cancelada', {
      timeout: 30_000,
    });
  });

  /**
   * Moved from e2e/real-swap (U4): the position of the recorded buy stays open until here. A conditional left OPEN is
   * cancelled by the way back to AUTO_FULL (BR6.2), and only then "vender tudo e sacar" sells the position and returns
   * the balance to the test main wallet.
   */
  test('4. back to AUTO_FULL cancels the open conditional (BR6.2), then selling everything returns the balance', async ({
    request,
  }) => {
    test.setTimeout(LOGIN_WINDOW_MS + FINAL_WITHIN_MS * 3);
    // A fresh token: the spec can take longer than the 15 minutes of one.
    guestToken = await signIn(request, main, '/api/v1/auth');
    const quote = await guest(
      request,
      'GET',
      `/api/v1/real/conditional-orders/quote?positionMint=${encodeURIComponent(mint)}`,
    );
    const { priceUsd } = (await quote.json()) as Quote;
    const created = await guest(request, 'POST', '/api/v1/real/conditional-orders', {
      positionMint: mint,
      kind: 'STOP',
      stopPriceUsd: times(priceUsd!, 7n, 10n),
    });
    expect(created.status(), 'the conditional left open').toBe(201);
    const { conditionalOrderId: id, signingSession } = (await created.json()) as {
      conditionalOrderId: string;
      signingSession: { message: string };
    };
    const signed = await guest(request, 'POST', `/api/v1/real/conditional-orders/${id}/signature`, {
      signature: encode(signWith(main, signingSession.message)),
    });
    expect(signed.ok()).toBe(true);

    await backToAutoFull(request);
    await expect
      .poll(async () => (await conditional(request, id)).state, { timeout: FINAL_WITHIN_MS })
      .toBe('CANCELLED');

    const before = await balance(main.address);
    const liquidation = await guest(request, 'POST', '/api/v1/real/liquidations');
    expect(liquidation.status()).toBe(202);
    await expect(async () => {
      const response = await guest(request, 'GET', '/api/v1/real/orders');
      const sells = ((await response.json()) as { items: Order[] }).items.filter(
        (o) => o.side === 'SELL' && o.input.mint === mint,
      );
      expect(
        sells.filter((o) => o.state === 'FAILED' || o.state === 'CANCELLED'),
        'the recorded sell must not fail',
      ).toEqual([]);
      expect(sells.map((o) => o.state)).toContain('CONFIRMED');
    }).toPass({ timeout: FINAL_WITHIN_MS, intervals: [5_000] });
    await expect(async () => {
      expect(await balance(main.address)).toBeGreaterThan(before + LAMPORTS_PER_SOL / 2n);
    }).toPass({ timeout: FINAL_WITHIN_MS, intervals: [5_000] });
    const pnl = (await (await guest(request, 'GET', '/api/v1/real/pnl')).json()) as {
      positions: Position[];
    };
    expect(pnl.positions.find((p) => p.mint === mint)?.baseUnits ?? '0').toBe('0');
  });
});
