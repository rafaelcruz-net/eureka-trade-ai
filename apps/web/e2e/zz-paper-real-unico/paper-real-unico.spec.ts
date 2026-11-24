import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
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
import { LAMPORTS_PER_SOL, airdrop, encode, transfer } from '../real-bot-wallet/local-validator';
import { mountedByMode, mountedLines } from './mounted-pnl-fixtures';

const API = process.env.API_BASE_URL ?? 'http://localhost:8080';
const PAPER_NAME = 'Simulado e2e U1';
const REAL_NAME = 'Real e2e U1';
/** The test-only first cycle of the worker runs a new test bot within about 10 s; the REAL order then settles. */
const BUY_WITHIN_MS = 180_000;

/** RECORDED_FIXTURES names the recorded.env written by infra/solana-fixtures/record.sh (scripts/verify.sh sets it). */
const fixtures = process.env.RECORDED_FIXTURES;

type Orders = {
  items: {
    botId?: string;
    side: string;
    state: string;
    output?: { mint: string; baseUnits: string };
  }[];
};
type Amount = { status: 'KNOWN' | 'UNKNOWN'; value?: string };
type PaperLine = { botId: string; name: string; fees: string; unrealized: Amount; total: Amount };
type RealLine = {
  botId: string;
  name: string;
  feesLamports: string;
  unrealizedLamports: Amount;
  totalLamports: Amount;
};
type Block = Record<string, unknown> & { status: string; currency: string; market: string };
type ByMode = {
  period: unknown;
  paper: Block & { bots?: PaperLine[] };
  real: Block & { bots?: RealLine[] };
};

function recorded(): Record<string, string> {
  const values: Record<string, string> = {};
  for (const line of readFileSync(fixtures ?? '', 'utf8').split('\n')) {
    const [key, value] = line.split('=');
    if (key && value) {
      values[key.trim()] = value.trim();
    }
  }
  return values;
}

/** True for an exact decimal text above zero (never parsed as floating point). */
function aboveZero(decimal: string): boolean {
  return /^\d+(\.\d+)?$/.test(decimal) && /[1-9]/.test(decimal);
}

/**
 * Walking skeleton of 261121-paper-real-unico (team.md "Fatia do intent `261121-paper-real-unico`", U1
 * u1-mode-skeleton; BR10.1, BR10.2, AC4.1.5; functional-spec.md F6): the single view with a PAPER bot and a REAL bot on
 * the local validator: browser (screens of Bots and P&L) → API (P&L by bot in both modes) → PAPER ledger and REAL
 * ledger → separate blocks Simulado (US$) and Real (SOL), without a general total, with the fixed disclaimer on the
 * simulated one. The spec creates the two bots, makes ONE BUY IN EACH MODE and checks that each block shows only its
 * own mode and that the modes are never added.
 *
 * - Both bots are created through the screens with the recorded mint as their only token: Simulado by default; Real
 *   through the confirmation "Este bot opera com dinheiro real" and the limits step read only. The REAL release comes
 *   from the seed of the test profile (BR10.2); the bot wallet is the one real-bot-wallet activated, in AUTO_FULL as
 *   real-v2-exits leaves it (checked, never fixed).
 * - The buys are deterministic and never wait for the 5-minute slot: the test strategy of the recorded swap (test
 *   profile, worker, only for a bot whose selection is exactly the recorded mint) buys ONCE in each mode, the REAL bot
 *   exactly the recorded lamports (as e2e/real-swap) and the PAPER bot the tokens of the recorded quote, through the
 *   same barriers and executions as any bot; the test-only first cycle of the worker runs a new test bot at once.
 * - The spec never checks values that depend on the rounding of the cost (RK-4). Nothing here talks to devnet,
 *   mainnet or a real KMS; the SOL is airdropped on the local validator.
 *
 * The folder is named zz-* on purpose: Playwright (workers: 1) runs the spec files in the alphabetical order of their
 * paths. This spec needs the bot wallet that real-bot-wallet activates and the AUTO_FULL that real-v2-exits restores,
 * and its PAPER buy must come after u7-ledger-pnl, which expects the smoke account without PAPER operations. It is the
 * last spec: it stops its two bots at the end; the REAL position it bought stays in the bot wallet of the local
 * validator, which scripts/verify.sh tears down.
 */
test.describe.serial('PAPER and REAL bots in one view, one buy in each mode, never added', () => {
  test.skip(
    !fixtures,
    'pending recording: run infra/solana-fixtures/record.sh and set RECORDED_FIXTURES',
  );
  test.setTimeout(240_000);

  let main: SmokeWallet;
  let page: Page;
  let accessToken = '';
  let mint = '';
  let botAddress = '';
  let paperBotId = '';
  let realBotId = '';

  async function call(request: APIRequestContext, method: 'GET' | 'POST', path: string) {
    return request.fetch(`${API}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${accessToken}`,
        ...(method === 'POST' ? { 'Idempotency-Key': randomUUID() } : {}),
      },
    });
  }

  async function signInApi(request: APIRequestContext) {
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
    accessToken = ((await verify.json()) as { accessToken: string }).accessToken;
  }

  async function byMode(request: APIRequestContext): Promise<ByMode> {
    const answer = await call(request, 'GET', '/api/v1/pnl/by-mode');
    expect(answer.status()).toBe(200);
    return (await answer.json()) as ByMode;
  }

  async function totalLamports(request: APIRequestContext): Promise<bigint> {
    const wallet = (await (await call(request, 'GET', '/api/v1/real/bot-wallet')).json()) as {
      balance: { totalLamports: string };
    };
    return BigInt(wallet.balance.totalLamports);
  }

  /** The wizard of a draft, steps 2 to 5: the recorded mint as the only token, the limits step of its mode, start. */
  async function finishWizard(real: boolean) {
    await page.waitForURL((url) => /^\/bots\/new\/[0-9a-f-]{36}$/.test(url.pathname));
    await expect(page.getByTestId('wizard-step')).toHaveText('Etapa 2 de 5: Estratégia e tokens');
    await expect(page.getByTestId('wizard-mode')).toContainText(real ? 'REAL' : 'SIMULADO');
    await page.getByTestId('wizard-mint').fill(mint);
    await page.getByTestId('wizard-add-token').click();
    await expect(page.getByTestId('wizard-token-0')).toHaveText(mint);
    await page.getByTestId('wizard-next-1').click();
    if (real) {
      // BR1.8: the REAL limits of the account, read only; no virtual balance, no limits of the bot.
      await expect(page.getByTestId('real-limits-values')).toBeVisible({ timeout: 30_000 });
      await expect(page.getByTestId('wizard-balance')).toHaveCount(0);
      await page.getByTestId('real-limits-continue').click();
    } else {
      await expect(page.getByTestId('wizard-limits-maxOrderSize')).toBeVisible();
      await page.getByTestId('wizard-next-2').click();
    }
    await page.getByTestId('wizard-next-3').click();
    await expect(page.getByTestId('wizard-start')).toHaveText(
      real ? 'Iniciar bot real' : 'Iniciar bot PAPER',
    );
    await page.getByTestId('wizard-start').click();
    await page.waitForURL((url) => /^\/bots\/[0-9a-f-]{36}$/.test(url.pathname), {
      timeout: 30_000,
    });
    return new URL(page.url()).pathname.split('/').pop() ?? '';
  }

  test.beforeAll(async ({ browser, request }) => {
    test.setTimeout(LOGIN_WINDOW_MS * 3 + 60_000);
    mint = recorded().RECORDED_MINT ?? '';
    expect(mint).toMatch(/^[1-9A-HJ-NP-Za-km-z]{32,44}$/);
    main = loadSmokeWallet();
    page = await browser.newPage();
    await installTestWallet(page, main);
    await signInWithinLoginLimit(page);
    await signInApi(request);
    // Entry state, checked and never fixed: the bot wallet of real-bot-wallet, in AUTO_FULL as real-v2-exits leaves it.
    const wallet = (await (await call(request, 'GET', '/api/v1/real/bot-wallet')).json()) as {
      address: string;
      mode: string;
    };
    botAddress = wallet.address;
    expect(wallet.mode, 'real-v2-exits leaves the bot wallet in AUTO_FULL').toBe('AUTO_FULL');
  });

  test.afterAll(async ({ request }) => {
    test.setTimeout(LOGIN_WINDOW_MS + 60_000);
    // No bot of this spec may keep cycling after the run.
    for (const botId of [paperBotId, realBotId].filter((id) => id !== '')) {
      const stopped = await call(request, 'POST', `/api/v1/bots/${botId}/commands/stop`);
      expect([200, 409], `the bot ${botId} must end stopped`).toContain(stopped.status());
    }
    await page?.close();
  });

  test('credits a deposit of 1 SOL in the bot wallet before the REAL bot exists', async ({
    request,
  }) => {
    const before = await totalLamports(request);
    await airdrop(main.address, 2n * LAMPORTS_PER_SOL);
    await transfer(main, botAddress, LAMPORTS_PER_SOL);
    // The REAL bot starts only after the deposit is credited, so its first cycle already has the balance.
    await expect(async () => {
      expect(await totalLamports(request)).toBe(before + LAMPORTS_PER_SOL);
    }).toPass({ timeout: 120_000, intervals: [5_000] });
  });

  test('creates a PAPER bot of the recorded mint through the screens, with Simulado chosen by default', async () => {
    await page.goto('/bots/new');
    await expect(page.getByTestId('bot-create-step')).toHaveText('Etapa 1 de 5: Modo e nome');
    await expect(page.getByTestId('mode-PAPER')).toBeChecked();
    await page.getByTestId('bot-create-MOMENTUM_VOLUME').check();
    await page.getByTestId('bot-create-name').fill(PAPER_NAME);
    await expect(page.getByTestId('bot-create-submit')).toHaveText('Criar rascunho simulado');
    await page.getByTestId('bot-create-submit').click();

    paperBotId = await finishWizard(false);

    expect(paperBotId).toMatch(/^[0-9a-f-]{36}$/);
    await expect(page.getByTestId('bot-state')).toHaveText('Ativo');
  });

  test('creates a REAL bot of the recorded mint through the confirmation and the limits of the account', async () => {
    await page.goto('/bots/new');
    // BR2.3, BR10.2: the release of the seed is ACTIVE and the bot wallet of real-bot-wallet is active.
    await expect(page.getByTestId('mode-REAL')).toHaveAttribute('aria-disabled', 'false', {
      timeout: 30_000,
    });
    await page.getByTestId('bot-create-MOMENTUM_VOLUME').check();
    await page.getByTestId('bot-create-name').fill(REAL_NAME);
    await page.getByTestId('mode-REAL').check();
    await expect(page.getByTestId('bot-create-submit')).toHaveText('Continuar para a confirmação');
    await page.getByTestId('bot-create-submit').click();
    await expect(page.getByTestId('real-confirm-create')).toBeDisabled();
    await page.getByTestId('real-confirm-check').check();
    await page.getByTestId('real-confirm-create').click();

    realBotId = await finishWizard(true);

    expect(realBotId).toMatch(/^[0-9a-f-]{36}$/);
    expect(realBotId).not.toBe(paperBotId);
  });

  test('each bot buys once in its own mode: the PAPER fill in US$, the REAL order CONFIRMED in SOL', async ({
    request,
  }) => {
    test.setTimeout(BUY_WITHIN_MS + 60_000);
    // REAL: one BUY of the REAL bot, CONFIRMED on the local validator, for exactly the recorded lamports.
    await expect(async () => {
      const response = await call(request, 'GET', '/api/v1/real/orders');
      expect(response.ok()).toBe(true);
      const buys = ((await response.json()) as Orders).items.filter(
        (o) => o.botId === realBotId && o.side === 'BUY',
      );
      expect(
        buys.filter((o) => o.state === 'FAILED' || o.state === 'CANCELLED'),
        'the REAL buy must not fail',
      ).toEqual([]);
      expect(buys.map((o) => o.state)).toContain('CONFIRMED');
      expect(buys.every((o) => o.output?.mint === mint)).toBe(true);
    }).toPass({ timeout: BUY_WITHIN_MS, intervals: [5_000] });

    // Both buys reach C4, each in its own block, unit and currency.
    await expect(async () => {
      const body = await byMode(request);
      const paperLine = body.paper.bots?.find((line) => line.botId === paperBotId);
      const realLine = body.real.bots?.find((line) => line.botId === realBotId);
      expect(paperLine, 'the PAPER bot is in the PAPER block').toBeDefined();
      expect(realLine, 'the REAL bot is in the REAL block').toBeDefined();
      // A PAPER fill always carries its simulated fee (US$ decimal text); a REAL fill its network fee (lamports).
      expect(aboveZero(paperLine?.fees ?? '0'), 'the PAPER buy was filled').toBe(true);
      expect(BigInt(realLine?.feesLamports ?? '0'), 'the REAL buy was settled').toBeGreaterThan(0n);
      // The bot of each mode never appears in the block of the other.
      expect(body.paper.bots?.some((line) => line.botId === realBotId)).toBe(false);
      expect(body.real.bots?.some((line) => line.botId === paperBotId)).toBe(false);
    }).toPass({ timeout: BUY_WITHIN_MS, intervals: [5_000] });
  });

  test('C4 answers only the period and the two blocks, each with only its own unit, and no general total', async ({
    request,
  }) => {
    const body = await byMode(request);

    expect(Object.keys(body).sort()).toEqual(['paper', 'period', 'real']);
    expect(body.paper.status).toBe('AVAILABLE');
    expect(body.real.status).toBe('AVAILABLE');
    expect([body.paper.market, body.paper.currency]).toEqual(['PAPER', 'USD']);
    expect([body.real.market, body.real.currency]).toEqual(['REAL', 'SOL']);
    // NEVER_SUM_MODES on the wire: no amount in lamports in the PAPER block, no amount in dollars in the REAL block.
    expect(JSON.stringify(body.paper)).not.toMatch(/Lamports/);
    expect(
      Object.keys(body.real).filter((key) => /^(total|realized|unrealized|fees)$/.test(key)),
    ).toEqual([]);
    expect(JSON.stringify(body)).not.toMatch(/grandTotal|overallTotal|totalGeral/i);
  });

  test('/pnl shows each bot and its result only in the block of its mode and currency, and no general total', async () => {
    await page.goto('/pnl');
    await expect(page.getByTestId('pnl-title')).toHaveText('Resultado');
    const paper = page.getByTestId('pnl-block-PAPER');
    const real = page.getByTestId('pnl-block-REAL');
    const paperRow = paper.getByTestId(`pnl-block-PAPER-bot-${paperBotId}`);
    const realRow = real.getByTestId(`pnl-block-REAL-bot-${realBotId}`);
    await expect(paperRow).toContainText(PAPER_NAME, { timeout: 30_000 });
    await expect(realRow).toContainText(REAL_NAME);
    // The result of each bought bot is shown in the currency of its own mode.
    await expect(paperRow.getByTestId(`pnl-block-PAPER-bot-total-${paperBotId}`)).toContainText(
      /US\$|desconhecido/i,
    );
    await expect(realRow.getByTestId(`pnl-block-REAL-bot-total-${realBotId}`)).toContainText(
      /SOL|desconhecido/i,
    );
    await expect(paper).not.toContainText(REAL_NAME);
    await expect(real).not.toContainText(PAPER_NAME);
    await expect(paper.getByTestId('pnl-block-PAPER-disclaimer')).toHaveText(
      'O resultado simulado não garante o resultado real.',
    );
    await expect(real.getByTestId('pnl-block-REAL-disclaimer')).toHaveCount(0);
    await expect(paper).not.toContainText('SOL');
    await expect(real).not.toContainText('US$');
    await expect(page.getByTestId('pnl-modes')).not.toContainText(/total geral/i);
  });

  test('/bots lists both bots together, each with its mode in text', async () => {
    await page.goto('/bots');
    await expect(page.getByTestId('bots-title')).toContainText('Meus bots');
    await expect(page.getByTestId(`bots-mode-badge-${paperBotId}`)).toHaveText(/SIMULADO/, {
      timeout: 30_000,
    });
    await expect(page.getByTestId(`bots-mode-badge-${realBotId}`)).toHaveText(/REAL/);
    await expect(page.getByTestId('bots-disclaimer')).toBeVisible();
  });

  test('at 360 px neither /pnl nor /bots scrolls sideways', async () => {
    await page.setViewportSize({ width: 360, height: 800 });
    for (const path of ['/pnl', '/bots']) {
      await page.goto(path);
      await expect(page.getByTestId(path === '/pnl' ? 'pnl-modes' : 'bots-list')).toBeVisible({
        timeout: 30_000,
      });
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      );
      expect(overflow, `${path} scrolls sideways at 360 px`).toBeLessThanOrEqual(0);
    }
  });

  // ---------------- 261121-paper-real-unico U3, Step 25: the views of the P&L (they only read) ----------------

  /** How far the page scrolls sideways; 0 or less means it does not (BR10.1, AC4.5.1). */
  async function sideScroll(): Promise<number> {
    return page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
  }

  async function wide() {
    await page.setViewportSize({ width: 1280, height: 900 });
  }

  test('U3 1. the dashboard shows "Resultado simulado" in US$ with the disclaimer and "Resultado real" in SOL without it', async () => {
    await wide();
    await page.goto('/');
    const cards = page.getByTestId('dashboard-mode-cards');
    await expect(cards).toContainText('Resultados por modo · desde o início', { timeout: 30_000 });
    await expect(page.getByTestId('pnl-compact-PAPER-total')).toContainText(/US\$|desconhecido/, {
      timeout: 30_000,
    });
    await expect(page.getByTestId('pnl-compact-PAPER-disclaimer')).toHaveText(
      'O resultado simulado não garante o resultado real.',
    );
    await expect(page.getByTestId('pnl-compact-REAL-total')).toContainText(/SOL|desconhecido/);
    await expect(page.getByTestId('pnl-compact-REAL-disclaimer')).toHaveCount(0);
    await expect(page.getByTestId('pnl-compact-REAL')).not.toContainText('US$');
    await expect(cards).not.toContainText(/total geral/i);
    await page.getByTestId('pnl-compact-REAL-open').click();
    await page.waitForURL((url) => url.pathname === '/pnl');
  });

  test('U3 2. /bots starts with the focus on the filter; Simulado and Real show only their bots, in the address', async () => {
    await wide();
    await page.goto('/bots');
    await expect(page.getByTestId('bots-mode-filter-ALL')).toBeFocused({ timeout: 30_000 });

    await page.getByTestId('bots-mode-filter-PAPER').check();
    await page.waitForURL((url) => url.searchParams.get('mode') === 'PAPER');
    await expect(page.getByTestId(`bots-row-${paperBotId}`)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId(`bots-row-${realBotId}`)).toHaveCount(0);
    await expect(page.getByTestId('bots-shown')).toContainText(/Mostrando \d+ de \d+ bots?\./);

    await page.getByTestId('bots-mode-filter-REAL').check();
    await page.waitForURL((url) => url.searchParams.get('mode') === 'REAL');
    await expect(page.getByTestId(`bots-row-${realBotId}`)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId(`bots-row-${paperBotId}`)).toHaveCount(0);
    await expect(page.getByTestId('bots-disclaimer')).toHaveCount(0);
  });

  test('U3 3. the screen of the REAL bot lists its lines with the origin, the buy with "Bot" and "—", dates dd/mm HH:MM', async () => {
    await wide();
    await page.goto(`/bots/${realBotId}`);
    const table = page.getByTestId('bot-lines-table');
    await expect(table).toBeVisible({ timeout: 30_000 });
    await expect(table.getByRole('columnheader', { name: 'Origem' })).toBeVisible();
    const buy = table.locator('tbody tr').filter({ hasText: 'Compra' }).first();
    await expect(buy).toContainText('Bot');
    await expect(buy).toContainText('—');
    await expect(buy.locator('td').first()).toHaveText(/^\d{2}\/\d{2} \d{2}:\d{2}$/);
    await expect(page.getByTestId('bot-pnl-disclaimer')).toHaveCount(0);
    await expect(page.getByTestId('bot-mode-line')).toContainText('Modo: Real');
  });

  test('U3 4. the screen of the PAPER bot shows the disclaimer of the simulated mode', async () => {
    await wide();
    await page.goto(`/bots/${paperBotId}`);
    await expect(page.getByTestId('bot-pnl-disclaimer')).toHaveText(
      'O resultado simulado não garante o resultado real.',
      { timeout: 30_000 },
    );
    await expect(page.getByTestId('bot-lines')).toHaveCount(0);
  });

  test('U3 5. a bot id that does not exist shows only "Bot não encontrado." and no data', async () => {
    await wide();
    await page.goto(`/bots/${randomUUID()}`);
    await expect(page.getByTestId('bot-not-found')).toContainText('Bot não encontrado.', {
      timeout: 30_000,
    });
    const main = page.getByRole('main');
    await expect(main).not.toContainText(/US\$|SOL|Modo:/);
  });

  test('U3 6. "Últimos 7 dias" goes to the address and reads C4 with RANGE in UTC; two blocks, no general total', async () => {
    await wide();
    await page.goto('/pnl');
    await expect(page.getByTestId('pnl-block-PAPER-title')).toBeVisible({ timeout: 30_000 });
    const ranged = page.waitForRequest(
      (request) =>
        request.url().includes('/api/v1/pnl/by-mode') && request.url().includes('period=RANGE'),
    );

    await page.getByTestId('pnl-period-LAST_7').check();

    const url = new URL((await ranged).url());
    expect(url.searchParams.get('from')).toMatch(/Z$/);
    expect(url.searchParams.get('to')).toMatch(/Z$/);
    expect(url.searchParams.has('market')).toBe(false);
    await page.waitForURL((current) => current.searchParams.get('periodo') === '7d');
    await expect(page.getByTestId('pnl-block-PAPER')).toBeVisible();
    await expect(page.getByTestId('pnl-block-REAL')).toBeVisible();
    await expect(page.getByTestId('pnl-modes')).not.toContainText(/total geral/i);
  });

  test('U3 7. the comparison puts the two bots of the same strategy side by side, each in its currency', async () => {
    await wide();
    await page.goto('/pnl');
    await page.getByTestId('compare-toggle').click();
    await expect(page.getByTestId('compare-paper-select')).toBeFocused();
    await page.getByTestId('compare-paper-select').selectOption(paperBotId);
    await page.getByTestId('compare-real-select').selectOption(realBotId);

    await expect(page.getByTestId('compare-PAPER-total')).toContainText(/US\$|desconhecido/, {
      timeout: 30_000,
    });
    await expect(page.getByTestId('compare-REAL-total')).toContainText(/SOL|desconhecido/);
    await expect(page.getByTestId('compare-PAPER-disclaimer')).toBeVisible();
    await expect(page.getByTestId('compare-REAL-disclaimer')).toHaveCount(0);
    await expect(page.getByTestId('compare-REAL')).not.toContainText('US$');
    await expect(page.getByTestId('compare-sides')).not.toContainText(/total geral|diferença/i);
    expect(new URL(page.url()).searchParams.get('comparar')).toBe(`${paperBotId},${realBotId}`);
  });

  test('U3 8. at 360 px no new screen scrolls sideways', async () => {
    await page.setViewportSize({ width: 360, height: 800 });
    const screens: [string, string][] = [
      ['/', 'dashboard-mode-cards'],
      ['/bots?mode=REAL', 'bots-mode-filter'],
      [`/bots/${realBotId}`, 'bot-lines'],
      [`/bots/${paperBotId}`, 'bot-pnl'],
      [`/pnl?comparar=${paperBotId},${realBotId}`, 'compare-sides'],
    ];
    for (const [path, ready] of screens) {
      await page.goto(path);
      await expect(page.getByTestId(ready)).toBeVisible({ timeout: 30_000 });
      expect(await sideScroll(), `${path} scrolls sideways at 360 px`).toBeLessThanOrEqual(0);
    }
  });

  test('U3 9. at 360 px with mounted data: 12 shares, "Sem bot" and two pages of lines become cards', async () => {
    await page.setViewportSize({ width: 360, height: 800 });
    const paper = { botId: paperBotId, name: PAPER_NAME };
    const real = { botId: realBotId, name: REAL_NAME };
    // Synthetic answers only (mounted-pnl-fixtures.ts): the screens never know they were mounted.
    await page.route('**/api/v1/pnl/by-mode**', async (route) => {
      const market = new URL(route.request().url()).searchParams.get('market');
      const body = mountedByMode(paper, real);
      await route.fulfill({
        json:
          market === 'REAL'
            ? { period: body.period, real: body.real }
            : market === 'PAPER'
              ? { period: body.period, paper: body.paper }
              : body,
      });
    });
    await page.route(`**/api/v1/bots/${realBotId}/pnl/lines**`, async (route) => {
      const cursor = new URL(route.request().url()).searchParams.get('cursor');
      await route.fulfill({ json: mountedLines(cursor) });
    });
    try {
      await page.goto('/pnl');
      const toggle = page.getByTestId(`prorated-${realBotId}-toggle`);
      await expect(toggle).toContainText('Ver 12 linhas rateadas', { timeout: 30_000 });
      await expect(page.getByTestId('pnl-block-REAL-nobot')).toContainText('Sem bot');
      expect(await sideScroll(), '/pnl before the shares').toBeLessThanOrEqual(0);
      await toggle.click();
      await expect(page.getByTestId(`prorated-${realBotId}-cards`)).toBeVisible();
      await expect(page.getByTestId(`prorated-${realBotId}-table`)).toBeHidden();
      expect(await sideScroll(), '/pnl with the shares open').toBeLessThanOrEqual(0);

      await page.goto(`/bots/${realBotId}`);
      await expect(page.getByTestId('bot-lines-card-49')).toBeVisible({ timeout: 30_000 });
      await expect(page.getByTestId('bot-lines-table')).toBeHidden();
      expect(await sideScroll(), '/bots/{id} with 50 lines').toBeLessThanOrEqual(0);
      await page.getByTestId('bot-lines-more').click();
      await expect(page.getByTestId('bot-lines-card-52')).toBeVisible();
      expect(await sideScroll(), '/bots/{id} with 53 lines').toBeLessThanOrEqual(0);
    } finally {
      await page.unrouteAll({ behavior: 'ignoreErrors' });
    }
  });

  test('U3 10. "Desde o início" shows the note instead of the table by token; "Hoje" shows the table', async () => {
    await wide();
    await page.goto('/pnl');
    await expect(page.getByTestId('pnl-by-token-note')).toHaveText(
      'Escolha um período para ver o resultado por token',
      { timeout: 30_000 },
    );
    await expect(page.getByTestId('pnl-report')).toHaveCount(0);

    await page.getByTestId('pnl-period-TODAY').check();

    await page.waitForURL((url) => url.searchParams.get('periodo') === 'hoje');
    await expect(page.getByTestId('pnl-report')).toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId('pnl-by-token-note')).toHaveCount(0);
  });

  // ---------------- 261121-paper-real-unico U4, Etapa 21: the notice of the own quantity (FR6.2) ----------------

  const OWN_QUANTITY_NOTICE =
    'Cada bot real vende só a quantidade que ele mesmo comprou. Posições herdadas ou de outros bots continuam na carteira e podem ser vendidas em Vender tudo e sacar.';

  test('U4 1. with the flag off (verify.sh) /bots shows no notice of the own quantity', async () => {
    await wide();
    await page.goto('/bots');
    await expect(page.getByTestId('bots-list')).toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId('bots-own-quantity-notice')).toHaveCount(0);
  });

  test('U4 2. at 360 px with the flag on, the notice shows in Todos and Real by its exact name, never in Simulado', async () => {
    await page.setViewportSize({ width: 360, height: 800 });
    // The flag stays off in verify.sh: the real answer of the api gets realOwnQuantitySell true here, nothing else.
    await page.route(
      (url) => url.pathname === '/api/v1/bots',
      async (route) => {
        const response = await route.fetch();
        await route.fulfill({
          response,
          json: { ...(await response.json()), realOwnQuantitySell: true },
        });
      },
    );
    try {
      for (const path of ['/bots', '/bots?mode=REAL']) {
        await page.goto(path);
        const notice = page.getByRole('status', { name: OWN_QUANTITY_NOTICE });
        await expect(notice, path).toBeVisible({ timeout: 30_000 });
        await expect(notice).toHaveText(OWN_QUANTITY_NOTICE);
        expect(await sideScroll(), `${path} with the notice at 360 px`).toBeLessThanOrEqual(0);
      }
      await page.goto('/bots?mode=PAPER');
      await expect(page.getByTestId('bots-list')).toBeVisible({ timeout: 30_000 });
      await expect(page.getByTestId('bots-own-quantity-notice')).toHaveCount(0);
    } finally {
      await page.unrouteAll({ behavior: 'ignoreErrors' });
      await wide();
    }
  });
});
