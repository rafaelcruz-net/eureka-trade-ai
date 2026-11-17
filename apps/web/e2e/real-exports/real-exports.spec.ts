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
import { encode } from '../real-bot-wallet/local-validator';

const API = process.env.API_BASE_URL ?? 'http://localhost:8080';
const ZONE = 'America/Sao_Paulo';
const HEADER =
  'data_utc;data_local;tipo;mint;simbolo;quantidade;preco_sol;valor_sol;taxa_sol;assinatura';

type Amount = { mint: string; baseUnits: string; decimals?: number };
type Order = {
  side: 'BUY' | 'SELL';
  state: string;
  txSignature?: string;
  input: Amount;
  output?: Amount;
};

/**
 * 261113-trading-real-bot U7 (infrastructure-specification.md "Spec `apps/web/e2e/real-exports/`", U7-NFR8.5; BR7):
 * the fiscal CSV of the smoke account on the local validator, after real-swap, real-ops and real-ui. It ONLY READS:
 * - exports the current year (1 January to today, in the zone of the person) by the API and checks the BOM, the `;`,
 *   the decimal comma, the line breaks CRLF and the header;
 * - finds the line COMPRA of the confirmed buy of real-swap by its signature and checks it against the order;
 * - checks the sum of BR7.6: since the bot wallet was created in this run, the sum of every line is the SOL balance of
 *   the REAL ledger;
 * - checks the 422 for a period longer than 1 year, and the download from the screen `/real/ordens`.
 * Wallets and keys are generated in test code; nothing here talks to devnet, mainnet or a real KMS.
 */
test.describe.serial('REAL fiscal export on the local validator', () => {
  test.setTimeout(180_000);

  let main: SmokeWallet;
  let token = '';
  let page: Page;

  async function signIn(request: APIRequestContext) {
    const challenge = await postWithinLoginLimit(request, `${API}/api/v1/auth/challenge`, {
      walletAddress: main.address,
    });
    expect(challenge.ok(), 'challenge').toBe(true);
    const { message } = (await challenge.json()) as { message: string };
    const verify = await postWithinLoginLimit(request, `${API}/api/v1/auth/verify`, {
      walletAddress: main.address,
      message,
      signature: encode(signWith(main, message)),
      provider: 'PHANTOM',
    });
    expect(verify.ok(), 'verify').toBe(true);
    return ((await verify.json()) as { accessToken: string }).accessToken;
  }

  function get(request: APIRequestContext, path: string) {
    return request.get(`${API}${path}`, { headers: { Authorization: `Bearer ${token}` } });
  }

  /** Today in the zone of the person, as yyyy-mm-dd. */
  function today(): string {
    return new Intl.DateTimeFormat('en-CA', {
      timeZone: ZONE,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).format(new Date());
  }

  /** "0,050000000" (SOL with a decimal comma) as lamports, exactly (no floating point). */
  function lamports(text: string): bigint {
    if (text === '') {
      return 0n;
    }
    const [whole, fraction = ''] = text.split(',');
    return BigInt(whole!) * 1_000_000_000n + BigInt(fraction.padEnd(9, '0').slice(0, 9));
  }

  /** A quantity with a decimal comma as base units of the mint. */
  function units(text: string, decimals: number): bigint {
    const [whole, fraction = ''] = text.split(',');
    return BigInt(whole!) * 10n ** BigInt(decimals) + BigInt(fraction.padEnd(decimals, '0') || '0');
  }

  /** The effect of one line on the SOL of the bot wallet (FiscalBalance, BR7.6). */
  function signed(kind: string, value: bigint, fee: bigint): bigint {
    switch (kind) {
      case 'DEPOSITO':
      case 'VENDA':
      case 'VENDA_CONDICIONAL':
      case 'AJUSTE_ENTRADA':
        return value - fee;
      default:
        return -(value + fee);
    }
  }

  test.beforeAll(async ({ browser, request }) => {
    test.setTimeout(LOGIN_WINDOW_MS * 2 + 60_000);
    main = loadSmokeWallet();
    token = await signIn(request);
    page = await browser.newPage();
    await installTestWallet(page, main);
    await signInWithinLoginLimit(page);
  });

  test.afterAll(async () => {
    test.setTimeout(LOGIN_WINDOW_MS + 30_000);
    await page?.close();
    // The spec after this one (real-exits) signs in from the same IP: leave it a whole login window.
    await new Promise((resolve) => setTimeout(resolve, LOGIN_WINDOW_MS));
  });

  test('1. the CSV of the current year: BOM, header, the buy of real-swap and the sum of BR7.6', async ({
    request,
  }) => {
    const to = today();
    const from = `${to.slice(0, 4)}-01-01`;
    const response = await get(
      request,
      `/api/v1/real/exports/fiscal?from=${from}&to=${to}&tz=${encodeURIComponent(ZONE)}`,
    );
    expect(response.status()).toBe(200);
    expect(response.headers()['content-type']).toContain('text/csv');
    expect(response.headers()['content-disposition']).toContain(`historico-real-${from}-${to}.csv`);
    const bytes = await response.body();
    expect([...bytes.subarray(0, 3)], 'the BOM of UTF-8').toEqual([0xef, 0xbb, 0xbf]);
    const text = bytes.subarray(3).toString('utf8');
    expect(text.endsWith('\r\n'), 'every line ends with CRLF').toBe(true);
    const lines = text.split('\r\n').filter((line) => line !== '');
    expect(lines[0]).toBe(HEADER);
    const rows = lines.slice(1).map((line) => line.split(';'));
    expect(rows.length, 'the history of this run has lines').toBeGreaterThan(0);

    // The confirmed buy of real-swap is the line COMPRA with its signature and the values of the order.
    const orders = await get(request, '/api/v1/real/orders?limit=100');
    expect(orders.ok()).toBe(true);
    const buy = ((await orders.json()) as { items: Order[] }).items.find(
      (order) => order.side === 'BUY' && order.state === 'CONFIRMED' && order.txSignature,
    );
    expect(buy, 'the confirmed buy of real-swap').toBeDefined();
    const line = rows.find((row) => row[2] === 'COMPRA' && row[9] === buy!.txSignature);
    expect(line, 'the line COMPRA of the buy of real-swap').toBeDefined();
    expect(line![3]).toBe(buy!.output!.mint);
    expect(lamports(line![7]!), 'valor_sol of the buy').toBe(BigInt(buy!.input.baseUnits));
    if (buy!.output?.decimals !== undefined) {
      expect(line![5], 'a quantity with a decimal comma').not.toContain('.');
      expect(units(line![5]!, buy!.output.decimals), 'quantidade of the buy').toBe(
        BigInt(buy!.output.baseUnits),
      );
    }
    expect(line![0]).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);

    // BR7.6: the bot wallet was created in this run, so the sum of the year is its SOL balance in the REAL ledger.
    const sum = rows.reduce(
      (total, row) => total + signed(row[2]!, lamports(row[7] ?? ''), lamports(row[8] ?? '')),
      0n,
    );
    const wallet = await get(request, '/api/v1/real/bot-wallet');
    const balance = ((await wallet.json()) as { balance: { totalLamports: string } }).balance;
    expect(sum, 'the sum of the lines (BR7.6)').toBe(BigInt(balance.totalLamports));
  });

  test('2. a period longer than 1 year is refused with 422', async ({ request }) => {
    const response = await get(
      request,
      '/api/v1/real/exports/fiscal?from=2025-01-01&to=2026-01-02',
    );
    expect(response.status()).toBe(422);
    expect(response.headers()['content-type']).toContain('application/problem+json');
  });

  test('3. the screen of the orders downloads the CSV with the name of the period', async () => {
    await page.goto('/real/ordens');
    await expect(page.getByTestId('real-fiscal-button')).toBeEnabled({ timeout: 30_000 });
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.getByTestId('real-fiscal-button').click(),
    ]);
    expect(download.suggestedFilename()).toMatch(
      /^historico-real-\d{4}-\d{2}-\d{2}-\d{4}-\d{2}-\d{2}\.csv$/,
    );
    await expect(page.getByTestId('real-fiscal-status')).toBeVisible();
  });
});
