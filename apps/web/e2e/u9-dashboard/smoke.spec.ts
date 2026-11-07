import { expect, test } from '@playwright/test';
import { installTestWallet, loadSmokeWallet } from '../support/test-wallet';

/** AC5.4.1: a change appears on the dashboard within 60 s, without reloading. */
const LIVE_BUDGET_MS = 60_000;
/** The channel sends its first events at the opening and a heartbeat every 15 s. */
const CHANNEL_BUDGET_MS = 20_000;

/**
 * U9 smoke (US5.3, US5.4; Q5 of the NFR requirements): sign in with the smoke account (no pending origin, so the entry
 * leads to the dashboard), check the three cards and the live channel, create and start a PAPER bot through the
 * wizard, then pause it from the bot screen in a SECOND tab; the first tab shows the bot paused within 60 s without
 * reloading. Simulated sources only, no real wallet, no transaction; REAL stays off.
 */
test('U9: the dashboard shows a pause made in another tab without reloading', async ({
  page,
  context,
}) => {
  test.setTimeout(180_000);
  const wallet = loadSmokeWallet();
  await installTestWallet(page, wallet);

  await page.goto('/entrar');
  await page.getByTestId('login-connect-phantom').click();
  await page.waitForURL((url) => url.pathname === '/');

  // The three cards and the shell.
  await expect(page.getByTestId('dashboard-title')).toHaveText('Painel');
  await expect(page.getByTestId('app-nav-dashboard-link')).toHaveAttribute('aria-current', 'page');
  await expect(page.getByTestId('dashboard-bots-card')).toContainText('MODO PAPER (simulado)');
  await expect(page.getByTestId('dashboard-pnl-card')).toContainText('MODO PAPER (simulado)');
  await expect(page.getByTestId('dashboard-data-notices')).toBeVisible();
  await expect(page.getByTestId('dashboard-pnl-card')).not.toContainText('Calculando');
  await expect(page.getByTestId('app-wallet-badge')).toContainText(
    `${wallet.address.slice(0, 4)}…${wallet.address.slice(-4)}`,
  );

  // The live channel is open: its first events arrive and no outdated notice is shown.
  await expect(page.getByTestId('dashboard-updated-at')).toContainText('Atualizado às', {
    timeout: CHANNEL_BUDGET_MS,
  });
  await expect(page.getByTestId('dashboard-stale-notice')).toHaveCount(0);

  // A new PAPER bot through the wizard of U5, started from it.
  await page.getByTestId('app-nav-bots-link').click();
  await page.getByTestId('bots-create').click();
  await page.getByTestId('bot-create-MOMENTUM_VOLUME').check();
  await page.getByTestId('bot-create-name').fill('Bot do smoke U9');
  await page.getByTestId('bot-create-submit').click();
  await page.waitForURL((url) => /^\/bots\/new\/[0-9a-f-]{36}$/.test(url.pathname));
  await page.getByTestId('wizard-add-rule').click();
  await page.getByTestId('wizard-next-1').click();
  await page.getByTestId('wizard-next-2').click();
  await page.getByTestId('wizard-next-3').click();
  await page.getByTestId('wizard-start').click();
  await page.waitForURL((url) => /^\/bots\/[0-9a-f-]{36}$/.test(url.pathname));
  await expect(page.getByTestId('bot-state')).toHaveText('Ativo');
  const botPath = new URL(page.url()).pathname;
  const botId = botPath.split('/').pop() as string;

  // Back to the dashboard: the bot is in use, with "Pausar".
  await page.getByTestId('app-nav-dashboard-link').click();
  await page.waitForURL((url) => url.pathname === '/');
  const row = page.getByTestId(`dashboard-bot-row-${botId}`);
  await expect(row).toContainText('Estado: Ativo');
  await expect(page.getByTestId(`dashboard-bot-pause-button-${botId}`)).toBeVisible();
  await expect(page.getByTestId('dashboard-bots-counts')).toContainText('Ativos:');

  // A second tab (same session through the refresh cookie) pauses the bot on its own screen.
  const second = await context.newPage();
  await second.goto(botPath);
  await expect(second.getByTestId('bot-state')).toHaveText('Ativo');
  await second.getByTestId('bot-pause').click();
  await expect(second.getByTestId('bot-state')).toHaveText('Pausado');
  const paused = Date.now();

  // The first tab shows it within 60 s, without any reload.
  await expect(row).toContainText('Estado: Pausado', { timeout: LIVE_BUDGET_MS });
  await expect(row).toContainText('pausado por você');
  expect(Date.now() - paused).toBeLessThanOrEqual(LIVE_BUDGET_MS);
  await expect(page.getByTestId(`dashboard-bot-pause-button-${botId}`)).toHaveCount(0);

  // Leave the smoke account without a bot in use for the next runs.
  await second.getByTestId('bot-stop').click();
  await second.getByTestId('bot-stop-confirm').click();
  await expect(second.getByTestId('bot-state')).toHaveText(/Encerrado|Encerramento pendente/);
  await second.close();
});
