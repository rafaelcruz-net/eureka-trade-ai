import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { expect, test, type APIRequestContext, type APIResponse } from '@playwright/test';
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
 * raises the bot wallet to AUTO_FULL with the consent signed by the test main wallet, deposits, creates and starts the
 * REAL bot of the recorded mint, waits for its buy to reach CONFIRMED with the ledger entry and the position, asks
 * "vender tudo e sacar" and checks the balance back in the test main wallet. The Jupiter answers come from the
 * jupiter-stub and the pool accounts from the validator, both recorded ONCE by infra/solana-fixtures/record.sh; the
 * recorded mint and the wrapped SOL are published by the simulated source sim-a, and the worker decides with the
 * test-only strategy of the recorded swap (one buy of exactly RECORDED_BUY_LAMPORTS), all in the test profile only.
 *
 * The spec runs only when RECORDED_FIXTURES names the recorded.env written by record.sh; without it the spec is
 * skipped and no other spec depends on it. It runs after the bot wallet spec of the same run, which activates the bot
 * wallet of the smoke account with a cap of 1 SOL and withdraws everything. Nothing here talks to devnet, mainnet or a
 * real KMS.
 */
const fixtures = process.env.RECORDED_FIXTURES;

/** The bots cycle once per 5-minute slot, 60 s after its start: a new bot waits up to about 6 minutes. */
const FIRST_CYCLE_MS = 420_000;
const LOGIN_WINDOW_MS = 65_000;

/** The part of GET /api/v1/real/orders the spec reads (RealOrder: input and output carry the mint). */
type Orders = {
  items: {
    botId?: string;
    side: string;
    state: string;
    failureCode?: string;
    input: { mint: string; baseUnits: string };
    output?: { mint: string; baseUnits: string };
  }[];
};

type Template = {
  templateId: string;
  parameters: { key: string; default: string }[];
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
  test.setTimeout(600_000);

  let main: SmokeWallet;
  let accessToken = '';
  let botAddress = '';
  let mint = '';
  let recordedBotId = '';

  async function call(
    request: APIRequestContext,
    method: 'GET' | 'POST' | 'PUT',
    path: string,
    data?: object,
  ): Promise<APIResponse> {
    const options = {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'Idempotency-Key': randomUUID(),
        'Content-Type': 'application/json',
      },
      ...(data === undefined ? {} : { data }),
    };
    if (method === 'GET') {
      return request.get(`${API}${path}`, options);
    }
    return method === 'POST'
      ? request.post(`${API}${path}`, options)
      : request.put(`${API}${path}`, options);
  }

  /** POST that waits out one 429 of the login rate limit before giving up. */
  async function postWithinLoginLimit(
    request: APIRequestContext,
    path: string,
    data: object,
  ): Promise<APIResponse> {
    const send = () =>
      request.post(`${API}${path}`, {
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

  /** A fresh access token (15 min): the whole spec can take longer than one token. */
  async function signIn(request: APIRequestContext) {
    const challenge = await postWithinLoginLimit(request, '/api/v1/auth/challenge', {
      walletAddress: main.address,
    });
    expect(challenge.ok()).toBe(true);
    const { message } = (await challenge.json()) as { message: string };
    const verify = await postWithinLoginLimit(request, '/api/v1/auth/verify', {
      walletAddress: main.address,
      message,
      signature: encode(signWith(main, message)),
      provider: 'PHANTOM',
    });
    expect(verify.ok()).toBe(true);
    accessToken = ((await verify.json()) as { accessToken: string }).accessToken;
  }

  async function orders(request: APIRequestContext): Promise<Orders> {
    const response = await call(request, 'GET', '/api/v1/real/orders');
    expect(response.ok()).toBe(true);
    return (await response.json()) as Orders;
  }

  /** The REAL bot of the recorded mint, through the same routes as the wizard (create, steps 1 to 4, start). */
  async function startRecordedMintBot(request: APIRequestContext): Promise<string> {
    const templates = (await (
      await call(request, 'GET', '/api/v1/strategy-templates')
    ).json()) as Template[];
    const template = templates.find((t) => t.templateId === 'MOMENTUM_VOLUME');
    if (!template) {
      throw new Error('the MOMENTUM_VOLUME template is missing');
    }
    const created = await call(request, 'POST', '/api/v1/bots', {
      templateId: template.templateId,
      name: 'Swap gravado (teste)',
      market: 'REAL',
    });
    expect(created.status()).toBe(201);
    const { botId } = (await created.json()) as { botId: string };
    const parameters = Object.fromEntries(template.parameters.map((p) => [p.key, p.default]));
    const steps: object[] = [
      { step: 1, parameters, tokenSelection: { tokens: [{ chain: 'solana', mint }], rules: [] } },
      {
        step: 2,
        virtualBalance: '1000.000000',
        limits: {
          maxOrderSize: '100.000000',
          maxTokenExposure: '200.000000',
          maxTotalExposure: '500.000000',
          maxDailyLoss: '50.000000',
          maxDrawdown: '10.0000',
          maxSlippage: '2.0000',
          minLiquidity: '10000.000000',
          minIntervalSeconds: 240,
        },
      },
      { step: 3 },
      { step: 4 },
    ];
    for (const step of steps) {
      const saved = await call(request, 'PUT', `/api/v1/bots/${botId}/draft`, step);
      expect(saved.status(), JSON.stringify(step)).toBe(200);
    }
    const started = await call(request, 'POST', `/api/v1/bots/${botId}/commands/start`);
    expect(started.status()).toBe(200);
    // Only an ACTIVE bot cycles; from here on the afterAll stops it.
    recordedBotId = botId;
    return botId;
  }

  test.beforeAll(async ({ request }) => {
    mint = recorded().RECORDED_MINT ?? '';
    expect(mint).toMatch(/^[1-9A-HJ-NP-Za-km-z]{32,44}$/);
    main = loadSmokeWallet();
    await signIn(request);
    const wallet = await call(request, 'GET', '/api/v1/real/bot-wallet');
    botAddress = ((await wallet.json()) as { address: string }).address;
    expect(botAddress).toBe(recorded().RECORDED_BOT_WALLET);
  });

  /**
   * The REAL bot of this spec is stopped whatever happened, so it never stays ACTIVE for the smoke account: it would
   * keep cycling during the specs that run after this file and count in their limit of active bots.
   */
  test.afterAll(async ({ request }) => {
    test.setTimeout(LOGIN_WINDOW_MS + 30_000);
    // The last access token of the spec is still valid (15 min; the whole spec takes less): no new login here.
    if (recordedBotId) {
      const stopped = await call(request, 'POST', `/api/v1/bots/${recordedBotId}/commands/stop`);
      expect(stopped.status(), 'the REAL bot of the swap spec must be stopped').toBe(200);
    }
    // The login is limited per IP and minute (LoginRateLimiter, 10 per minute, ProfileDefaultsTest), and the specs
    // after this file (u1, u2, ...) sign in from the same IP. This spec signs in twice (before the tests and before
    // the liquidation): like the bot wallet spec, it leaves them a whole login window. Without it, u1 got 429 on the
    // nonce replay and u2 never left the login screen (CI run 43794459747).
    await new Promise((resolve) => setTimeout(resolve, LOGIN_WINDOW_MS));
  });

  test('raises the bot wallet to AUTO_FULL with the consent signed by the main wallet', async ({
    request,
  }) => {
    const challenge = await call(request, 'POST', '/api/v1/real/consents/challenge', {
      kind: 'RAISE_MODE',
      mode: 'AUTO_FULL',
    });
    expect(challenge.status()).toBe(201);
    const { challengeId, message } = (await challenge.json()) as {
      challengeId: string;
      message: string;
    };

    const raised = await call(request, 'PUT', '/api/v1/real/mode', {
      mode: 'AUTO_FULL',
      challengeId,
      signature: encode(signWith(main, message)),
    });

    expect(raised.status()).toBe(200);
    expect(((await raised.json()) as { mode: string }).mode).toBe('AUTO_FULL');
    const wallet = (await (await call(request, 'GET', '/api/v1/real/bot-wallet')).json()) as {
      mode: string;
    };
    expect(wallet.mode).toBe('AUTO_FULL');
  });

  test('a deposit lets the bot buy the recorded mint and the order is CONFIRMED with its position', async ({
    request,
  }) => {
    await airdrop(main.address, 2n * LAMPORTS_PER_SOL);
    await transfer(main, botAddress, LAMPORTS_PER_SOL);
    // The bot starts only after the deposit is credited, so its first cycle already has the balance.
    await expect(async () => {
      const wallet = (await (await call(request, 'GET', '/api/v1/real/bot-wallet')).json()) as {
        balance: { totalLamports: string };
      };
      expect(BigInt(wallet.balance.totalLamports)).toBe(LAMPORTS_PER_SOL);
    }).toPass({ timeout: 120_000, intervals: [5_000] });

    const botId = await startRecordedMintBot(request);

    await expect(async () => {
      const buys = (await orders(request)).items.filter(
        (o) => o.side === 'BUY' && o.output?.mint === mint,
      );
      expect(
        buys.filter((o) => o.state === 'FAILED' || o.state === 'CANCELLED'),
        'the recorded buy must not fail',
      ).toEqual([]);
      expect(buys.map((o) => o.state)).toContain('CONFIRMED');
    }).toPass({ timeout: FIRST_CYCLE_MS, intervals: [10_000] });

    const buys = (await orders(request)).items.filter((o) => o.side === 'BUY');
    expect(buys).toHaveLength(1);
    expect(buys[0]?.botId).toBe(botId);
    expect(buys[0]?.input.baseUnits).toBe(recorded().RECORDED_BUY_LAMPORTS);

    await expect(async () => {
      const pnl = (await (await call(request, 'GET', '/api/v1/real/pnl')).json()) as {
        positions: { mint: string; baseUnits: string; costLamports: string }[];
        feesLamports: string;
      };
      const position = pnl.positions.find((p) => p.mint === mint);
      // The sell leg was recorded for exactly the output of the buy quote.
      expect(position?.baseUnits).toBe(recorded().RECORDED_SELL_UNITS);
      expect(BigInt(position?.costLamports ?? '0')).toBeGreaterThan(0n);
      expect(BigInt(pnl.feesLamports)).toBeGreaterThan(0n);
    }).toPass({ timeout: 60_000, intervals: [5_000] });
  });

  test('selling everything and withdrawing returns the balance to the main wallet', async ({
    request,
  }) => {
    await signIn(request);
    const before = await balance(main.address);

    const liquidation = await call(request, 'POST', '/api/v1/real/liquidations');
    expect(liquidation.status()).toBe(202);

    await expect(async () => {
      const sells = (await orders(request)).items.filter(
        (o) => o.side === 'SELL' && o.input.mint === mint,
      );
      expect(
        sells.filter((o) => o.state === 'FAILED' || o.state === 'CANCELLED'),
        'the recorded sell must not fail',
      ).toEqual([]);
      expect(sells.map((o) => o.state)).toContain('CONFIRMED');
    }).toPass({ timeout: 180_000, intervals: [5_000] });

    await expect(async () => {
      expect(await balance(main.address)).toBeGreaterThan(before + LAMPORTS_PER_SOL / 2n);
    }).toPass({ timeout: 180_000, intervals: [5_000] });
    const pnl = (await (await call(request, 'GET', '/api/v1/real/pnl')).json()) as {
      positions: { mint: string; baseUnits: string }[];
    };
    expect(pnl.positions.find((p) => p.mint === mint)?.baseUnits ?? '0').toBe('0');
  });
});
