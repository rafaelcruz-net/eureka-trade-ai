import { randomUUID } from 'node:crypto';
import { expect, type APIRequestContext, type APIResponse, type Page } from '@playwright/test';

/**
 * 261113-trading-real-bot U6 (infrastructure-specification.md "Spec novo"): the waits of the login limit, extracted from
 * the bot wallet spec so the specs of the REAL share them. The login is limited per IP and per minute
 * (LoginRateLimiter), and every spec of the run signs in from the same machine: a refusal is waited out, never raised.
 */
export const LOGIN_WINDOW_MS = 65_000;

/** POST that waits out one 429 of the login rate limit before giving up. */
export async function postWithinLoginLimit(
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

/**
 * Signs in through the entry of the app with the wallet already installed on the page, waiting out at most two
 * refusals of the limit. Each wait is bounded and the loser of the race is settled, so nothing is left pending.
 */
export async function signInWithinLoginLimit(page: Page): Promise<void> {
  for (let attempt = 1; ; attempt++) {
    await page.goto('/entrar?origem=/scanner');
    await page.getByTestId('login-connect-phantom').click();
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
}
