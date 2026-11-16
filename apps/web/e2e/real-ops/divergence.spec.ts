import { randomUUID } from 'node:crypto';
import { expect, test, type APIRequestContext, type APIResponse } from '@playwright/test';
import { loadSmokeWallet, signWith, type SmokeWallet } from '../support/test-wallet';
import {
  LAMPORTS_PER_SOL,
  airdrop,
  encode,
  mintToNewToken,
} from '../real-bot-wallet/local-validator';

const API = process.env.API_BASE_URL ?? 'http://localhost:8080';
const LOGIN_WINDOW_MS = 65_000;
/** The worker of infra/docker-compose.real.yml reconciles every 15 s (MEMETRADE_REAL_OPS_RECONCILIATION_INTERVAL). */
const RECONCILIATION_WAIT_MS = 120_000;
const MINTED_BASE_UNITS = 1_000_000n;

type BotWallet = { address?: string; status?: string; pausedReason?: string | null };
type AdminUser = { userId: string; botWalletId: string; botWalletAddress: string | null };
type Divergence = {
  divergenceId: string;
  botWalletId: string;
  state: string;
  lamportsDiff: string;
  tokenDiffs: { mint: string; diffBaseUnits: string }[];
};

/**
 * Operations of the REAL on the local validator (261113-trading-real-bot U5, infrastructure-specification.md "Ponta a
 * ponta", U5-NFR8.7): a new test token is minted straight into the bot wallet of the smoke account, with no order and
 * no ledger entry; the reconciliation of the worker (every 15 s in this run) finds the divergence and pauses the wallet
 * (DIVERGENCE); the administrator compensates exactly the measured difference; the next reconciliation comes back
 * CLEAN, the divergence is RESOLVED and the pause leaves. Then the administrator switches the kill switch REAL on (the
 * wallet shows KILL_SWITCH) and off again.
 *
 * Runs after the bot wallet spec (which activates the bot wallet of the smoke account) and the swap spec of the same
 * run. The token, the mint key and the payer are generated in test code; nothing here talks to devnet, mainnet or a
 * real KMS. Needs SMOKE_WALLET_FILE and SMOKE_ADMIN_WALLET_FILE (scripts/verify.sh).
 */
test.describe.serial('REAL operations: divergence, compensation and kill switch', () => {
  test.setTimeout(300_000);

  let main: SmokeWallet;
  let admin: SmokeWallet;
  let guestToken = '';
  let adminToken = '';
  let botAddress = '';
  let botWalletId = '';
  let mint = '';
  let divergenceId = '';

  async function post(
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

  /** A login through the api, signed by a wallet generated in test code: the access token. */
  async function signIn(request: APIRequestContext, wallet: SmokeWallet, prefix: string) {
    const challenge = await post(request, `${prefix}/challenge`, { walletAddress: wallet.address });
    expect(challenge.ok(), `challenge of ${prefix}`).toBe(true);
    const { message } = (await challenge.json()) as { message: string };
    const verify = await post(request, `${prefix}/verify`, {
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
    method: 'GET' | 'POST' | 'PUT' | 'DELETE',
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

  async function divergencesOfTheWallet(request: APIRequestContext): Promise<Divergence[]> {
    const response = await adminCall(request, 'GET', '/divergences?limit=50');
    expect(response.ok()).toBe(true);
    const { items } = (await response.json()) as { items: Divergence[] };
    return items.filter((item) => item.botWalletId === botWalletId);
  }

  test.beforeAll(async ({ request }) => {
    test.setTimeout(LOGIN_WINDOW_MS * 3 + 60_000);
    const adminFile = process.env.SMOKE_ADMIN_WALLET_FILE;
    if (!adminFile) {
      throw new Error(
        'the REAL operations spec needs SMOKE_ADMIN_WALLET_FILE (set by scripts/verify.sh)',
      );
    }
    main = loadSmokeWallet();
    admin = loadSmokeWallet(adminFile);
    guestToken = await signIn(request, main, '/api/v1/auth');
    adminToken = await signIn(request, admin, '/api/v1/admin/auth');
  });

  test('the administrator sees the bot wallet of the smoke account, with the main wallet abbreviated', async ({
    request,
  }) => {
    const wallet = await botWallet(request);
    expect(wallet.status, 'the bot wallet spec activates the bot wallet first').toBe('ACTIVE');
    botAddress = wallet.address ?? '';

    const response = await adminCall(request, 'GET', '/users?limit=50');
    expect(response.ok()).toBe(true);
    const { items } = (await response.json()) as { items: AdminUser[] };
    const user = items.find((item) => item.botWalletAddress === botAddress);
    expect(user, 'the smoke account is in the list of the administrator').toBeDefined();
    botWalletId = user!.botWalletId;

    const detail = await adminCall(request, 'GET', `/users/${user!.userId}`);
    expect(detail.ok()).toBe(true);
    const text = await detail.text();
    expect(text).not.toContain(main.address);
    expect(JSON.parse(text).mainWalletShort).toBe(
      `${main.address.slice(0, 4)}…${main.address.slice(-4)}`,
    );
  });

  test('a token minted straight into the bot wallet opens a divergence and pauses it', async ({
    request,
  }) => {
    await airdrop(main.address, LAMPORTS_PER_SOL);
    mint = await mintToNewToken(main, botAddress, MINTED_BASE_UNITS);

    await expect(async () => {
      const open = await divergencesOfTheWallet(request);
      expect(open).toHaveLength(1);
      expect(open[0]!.state).toBe('OPEN');
      expect(open[0]!.tokenDiffs).toContainEqual(
        expect.objectContaining({ mint, diffBaseUnits: MINTED_BASE_UNITS.toString() }),
      );
      divergenceId = open[0]!.divergenceId;
    }).toPass({ timeout: RECONCILIATION_WAIT_MS, intervals: [5_000] });
    expect((await botWallet(request)).pausedReason).toBe('DIVERGENCE');
  });

  test('a wrong compensation is refused and the exact one resolves the divergence and lifts the pause', async ({
    request,
  }) => {
    const path = `/divergences/${divergenceId}/compensation`;
    const wrong = await adminCall(request, 'POST', path, {
      items: [{ asset: mint, amount: (MINTED_BASE_UNITS - 1n).toString() }],
      reason: 'teste de ponta a ponta: valor errado',
    });
    expect(wrong.status()).toBe(422);
    expect(((await wrong.json()) as { code: string }).code).toBe('COMPENSATION_MISMATCH');

    const right = await adminCall(request, 'POST', path, {
      items: [{ asset: mint, amount: MINTED_BASE_UNITS.toString() }],
      reason: 'teste de ponta a ponta: token recebido sem ordem',
    });
    expect(right.status()).toBe(201);
    expect(((await right.json()) as Divergence).state).toBe('COMPENSATED');

    await expect(async () => {
      expect(await divergencesOfTheWallet(request)).toHaveLength(0);
      expect((await botWallet(request)).pausedReason ?? null).not.toBe('DIVERGENCE');
    }).toPass({ timeout: RECONCILIATION_WAIT_MS, intervals: [5_000] });
  });

  test('the kill switch REAL pauses the wallet at once and switching it off lifts that pause', async ({
    request,
  }) => {
    const on = await adminCall(request, 'PUT', '/kill-switch', {
      reason: 'teste de ponta a ponta do kill switch',
    });
    try {
      expect(on.status()).toBe(200);
      expect(((await on.json()) as { active: boolean }).active).toBe(true);
      expect((await botWallet(request)).pausedReason).toBe('KILL_SWITCH');
    } finally {
      const off = await adminCall(request, 'DELETE', '/kill-switch', {
        reason: 'fim do teste de ponta a ponta',
      });
      expect(off.status()).toBe(200);
    }
    expect((await botWallet(request)).pausedReason ?? null).not.toBe('KILL_SWITCH');
  });
});
