import { expect, test } from '@playwright/test';
import { installTestWallet, loadSmokeWallet } from '../support/test-wallet';
import { connectAndReachScanner } from '../u4-discovery/sign-in';

/** NFR3.2 (Q1 of the Infrastructure Design, option A): the bot screen S5 is visible in under 2 s. */
const SCREEN_BUDGET_MS = 2_000;

/**
 * U5 smoke (US4.1, US4.3, US4.8, US4.12): sign in with the smoke account of U1, create a PAPER bot through the 4 steps
 * of the wizard with a scanner rule, start it and then pause, resume and stop it, checking the state and the reason in
 * text and the label "MODO PAPER (simulado)" on every screen. Simulated sources only, no real wallet, no transaction;
 * REAL stays off.
 */
test('U5: wizard, start, pause, resume and stop of a PAPER bot', async ({ page }) => {
  const wallet = loadSmokeWallet();
  await installTestWallet(page, wallet);

  await page.goto('/entrar?origem=/scanner');
  await connectAndReachScanner(page);
  await page.getByTestId('app-bots-link').click();
  await page.waitForURL((url) => url.pathname === '/bots');
  await expect(page.getByTestId('bots-title')).toContainText('MODO PAPER (simulado)');
  await expect(page.getByTestId('bots-counter')).toContainText('de 3 bots ativos');

  // Step 0: template and name.
  await page.getByTestId('bots-create').click();
  await page.waitForURL((url) => url.pathname === '/bots/new');
  await page.getByTestId('bot-create-MOMENTUM_VOLUME').check();
  await page.getByTestId('bot-create-name').fill('Bot do smoke U5');
  await page.getByTestId('bot-create-submit').click();
  await page.waitForURL((url) => /^\/bots\/new\/[0-9a-f-]{36}$/.test(url.pathname));
  await expect(page.getByTestId('wizard-title')).toContainText('MODO PAPER (simulado)');

  // Step 1: the default parameters and one scanner rule (liquidity >= 10.000 USDC).
  await expect(page.getByTestId('wizard-step')).toHaveText('Etapa 1 de 4: Estratégia e tokens');
  await expect(page.getByTestId('wizard-next-1')).toBeDisabled();
  await page.getByTestId('wizard-add-rule').click();
  await page.getByTestId('wizard-next-1').click();

  // Step 2: the default balance and the proportional limits.
  await expect(page.getByTestId('wizard-step')).toHaveText('Etapa 2 de 4: Limites e saldo');
  await expect(page.getByTestId('wizard-limits-maxOrderSize')).toHaveValue('100.000000');
  await page.getByTestId('wizard-next-2').click();

  // Step 3: review with the hypotheses of the simulation.
  await expect(page.getByTestId('wizard-step')).toHaveText('Etapa 3 de 4: Revisão');
  await expect(page.getByTestId('wizard-hypotheses')).toContainText(
    'não garantem resultados reais',
  );
  await page.getByTestId('wizard-next-3').click();

  // Step 4: start; the bot screen opens with the confirmed state, in under 2 s.
  await expect(page.getByTestId('wizard-step')).toHaveText('Etapa 4 de 4: Início');
  const started = Date.now();
  await page.getByTestId('wizard-start').click();
  await page.waitForURL((url) => /^\/bots\/[0-9a-f-]{36}$/.test(url.pathname));
  await expect(page.getByTestId('bot-state')).toHaveText('Ativo');
  expect(Date.now() - started).toBeLessThan(SCREEN_BUDGET_MS);
  await expect(page.getByTestId('bot-title')).toContainText('MODO PAPER (simulado)');

  await page.getByTestId('bot-pause').click();
  await expect(page.getByTestId('bot-state')).toHaveText('Pausado');
  await expect(page.getByTestId('bot-reasons')).toContainText('pausado por você');

  await page.getByTestId('bot-resume').click();
  await expect(page.getByTestId('bot-state')).toHaveText('Ativo');
  await expect(page.getByTestId('bot-reasons')).toHaveCount(0);

  await page.getByTestId('bot-stop').click();
  await expect(page.getByTestId('bot-stop-dialog')).toBeVisible();
  await page.getByTestId('bot-stop-confirm').click();
  await expect(page.getByTestId('bot-state')).toHaveText(/Encerrado|Encerramento pendente/);
  await expect(page.getByTestId('bot-controls').getByRole('button')).toHaveCount(0);

  // A fresh visit of S5 is also visible in under 2 s.
  const reopened = Date.now();
  await page.reload();
  await expect(page.getByTestId('bot-state')).toBeVisible();
  expect(Date.now() - reopened).toBeLessThan(SCREEN_BUDGET_MS);
});
