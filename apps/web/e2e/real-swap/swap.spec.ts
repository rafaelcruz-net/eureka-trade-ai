import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { expect, test, type APIRequestContext } from '@playwright/test';
import { loadSmokeWallet, signWith, type SmokeWallet } from '../support/test-wallet';
import {
  LAMPORTS_PER_SOL,
  airdrop,
  balance,
  encode,
  transfer,
} from '../real-bot-wallet/local-validator';

const API = process.env.API_BASE_URL ?? 'http://localhost:8080';

/**
 * REAL swap on the local validator (261113-trading-real-bot U4, infrastructure-specification.md "Ponta a ponta"):
 * deposits in the bot wallet, waits for the buy of the recorded mint to reach CONFIRMED with its P&L position, asks
 * "vender tudo e sacar" and checks the balance back in the test main wallet. The Jupiter answers come from the
 * jupiter-stub and the pool accounts from the validator, both recorded ONCE by infra/solana-fixtures/record.sh.
 *
 * PENDING RECORDING: the spec runs only when RECORDED_FIXTURES names the recorded.env written by record.sh; until the
 * owner records the fixtures it is skipped and no other spec depends on it. It runs after the bot wallet spec of the
 * same run, which activates the bot wallet of the smoke account. Nothing here talks to devnet, mainnet or a real KMS.
 */
const fixtures = process.env.RECORDED_FIXTURES;

/** The part of GET /api/v1/real/orders the spec reads (RealOrder: input and output carry the mint). */
type Orders = {
  items: { side: string; state: string; input: { mint: string }; output: { mint: string } }[];
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

test.describe.serial('REAL swap on the local validator', () => {
  test.skip(
    !fixtures,
    'pending recording: run infra/solana-fixtures/record.sh and set RECORDED_FIXTURES',
  );
  test.setTimeout(300_000);

  let main: SmokeWallet;
  let accessToken = '';
  let botAddress = '';
  let mint = '';

  async function call(request: APIRequestContext, method: 'GET' | 'POST', path: string) {
    const headers = { Authorization: `Bearer ${accessToken}`, 'Idempotency-Key': randomUUID() };
    return method === 'GET'
      ? request.get(`${API}${path}`, { headers })
      : request.post(`${API}${path}`, { headers });
  }

  test.beforeAll(async ({ request }) => {
    mint = recorded().RECORDED_MINT ?? '';
    expect(mint).toMatch(/^[1-9A-HJ-NP-Za-km-z]{32,44}$/);
    main = loadSmokeWallet();
    const challenge = await request.post(`${API}/api/v1/auth/challenge`, {
      headers: { 'Idempotency-Key': randomUUID() },
      data: { walletAddress: main.address },
    });
    expect(challenge.ok()).toBe(true);
    const { message } = (await challenge.json()) as { message: string };
    const verify = await request.post(`${API}/api/v1/auth/verify`, {
      headers: { 'Idempotency-Key': randomUUID() },
      data: {
        walletAddress: main.address,
        message,
        signature: encode(signWith(main, message)),
        provider: 'PHANTOM',
      },
    });
    expect(verify.ok()).toBe(true);
    accessToken = ((await verify.json()) as { accessToken: string }).accessToken;
    const wallet = await call(request, 'GET', '/api/v1/real/bot-wallet');
    botAddress = ((await wallet.json()) as { address: string }).address;
    expect(botAddress).toBe(recorded().RECORDED_BOT_WALLET);
  });

  test('a deposit lets the bot buy the recorded mint and the order is CONFIRMED with its position', async ({
    request,
  }) => {
    await airdrop(main.address, 2n * LAMPORTS_PER_SOL);
    await transfer(main, botAddress, LAMPORTS_PER_SOL);

    await expect(async () => {
      const orders = (await (await call(request, 'GET', '/api/v1/real/orders')).json()) as Orders;
      expect(
        orders.items.some(
          (o) => o.side === 'BUY' && o.output.mint === mint && o.state === 'CONFIRMED',
        ),
      ).toBe(true);
    }).toPass({ timeout: 180_000, intervals: [5_000] });

    const pnl = (await (await call(request, 'GET', '/api/v1/real/pnl')).json()) as {
      positions: { mint: string; baseUnits: string }[];
      feesLamports: string;
    };
    expect(pnl.positions.find((p) => p.mint === mint)?.baseUnits).toBe(
      recorded().RECORDED_SELL_UNITS,
    );
    expect(BigInt(pnl.feesLamports)).toBeGreaterThan(0n);
  });

  test('selling everything and withdrawing returns the balance to the main wallet', async ({
    request,
  }) => {
    const before = await balance(main.address);

    const liquidation = await call(request, 'POST', '/api/v1/real/liquidations');
    expect(liquidation.status()).toBe(202);

    await expect(async () => {
      expect(await balance(main.address)).toBeGreaterThan(before + LAMPORTS_PER_SOL / 2n);
    }).toPass({ timeout: 240_000, intervals: [5_000] });
    const orders = (await (await call(request, 'GET', '/api/v1/real/orders')).json()) as Orders;
    expect(
      orders.items.some(
        (o) => o.side === 'SELL' && o.input.mint === mint && o.state === 'CONFIRMED',
      ),
    ).toBe(true);
  });
});
