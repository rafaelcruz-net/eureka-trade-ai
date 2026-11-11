import { expect, test, type Page } from '@playwright/test';

/**
 * u1-legal-documents, walking skeleton of the intent 261109-termos-lgpd (US2.1, US2.2, US2.4, US2.5; NFR4.1, NFR6.3,
 * NFR6.5): /termos and /privacidade served by the api from the catalog and shown WITHOUT a session. scripts/verify.sh
 * runs the environment with infra/docker-compose.test.yml, which mounts the TEST catalog "reviewed" (current v2, never
 * the real draft) and sets no deployment field, so the fields read "a definir".
 *
 * The last test exhausts the per-IP limit of /api/v1/legal/** from the host (the bridge gateway, never exempt); it runs
 * last so the other tests never meet a 429.
 */
const API = process.env.API_BASE_URL ?? 'http://localhost:8080';

/** NFR6.3: 60 requests per client IP and minute. */
const LEGAL_LIMIT_PER_MINUTE = 60;

/**
 * The visible content of the page. The pages stream behind a loading.tsx, and a hidden copy of the streamed content
 * can stay in the document next to the real one (first CI run: getByTestId('legal-version') resolved to 2 elements).
 * A role query skips hidden elements, and the loading main has no accessible name, so this is the single page main.
 */
function content(page: Page) {
  return page.getByRole('main', { name: /\S/ });
}

test.describe.configure({ mode: 'serial' });

test('/privacidade opens without a session with version, summary, PAPER notice and "a definir"', async ({
  page,
}) => {
  const response = await page.goto('/privacidade');

  expect(response?.status()).toBe(200);
  expect(response?.headers()['x-content-type-options']).toBe('nosniff');
  expect(response?.headers()['x-frame-options']).toBe('DENY');
  expect(response?.headers()['content-security-policy']).toContain("frame-ancestors 'none'");
  await expect(
    page.getByRole('heading', { level: 1, name: 'Política de privacidade' }),
  ).toBeVisible();
  await expect(content(page).getByTestId('legal-version')).toContainText('Versão v2');
  await expect(
    content(page).getByTestId('legal-summary').getByRole('listitem').first(),
  ).toBeVisible();
  await expect(content(page).getByTestId('legal-paper-notice')).toContainText('PAPER');
  await expect(content(page).getByTestId('legal-deployment-storage')).toHaveText('a definir');
  await expect(content(page).getByTestId('legal-deployment-transfer')).toHaveText('a definir');
  await expect(content(page).getByTestId('legal-deployment-dpo')).toHaveText('a definir');
  await expect(content(page).getByTestId('legal-retention-table')).toBeVisible();
  await expect(content(page).getByTestId('legal-not-publishable')).toBeVisible();
  await expect(page.getByTestId('desktop-only-notice')).toHaveCount(0);
});

test('"Fazer um pedido" leads to the entry without a session and the footer links both pages', async ({
  page,
}) => {
  await page.goto('/privacidade');

  await expect(content(page).getByTestId('legal-request-button')).toHaveAttribute(
    'href',
    '/entrar',
  );
  await expect(content(page).getByTestId('legal-request-email')).toContainText('a definir');
  const footer = page.getByTestId('site-footer');
  await expect(footer.getByTestId('site-footer-terms-link')).toHaveAttribute('href', '/termos');
  await expect(footer.getByTestId('site-footer-accepted-version')).toHaveCount(0);

  await footer.getByTestId('site-footer-terms-link').click();
  await page.waitForURL((url) => url.pathname === '/termos');
  await expect(page.getByRole('heading', { level: 1, name: 'Termos de uso' })).toBeVisible();
  await expect(content(page).getByTestId('legal-risk-notice')).toBeVisible();
});

test('a script in the text appears literally and an old or unknown version is announced', async ({
  page,
}) => {
  await page.goto('/termos');
  await expect(
    content(page).getByText('<script>alert(1)</script>', { exact: false }),
  ).toBeVisible();

  await page.goto('/termos?versao=v1');
  await expect(content(page).getByTestId('legal-old-version-banner')).toContainText(
    'Esta não é a versão vigente',
  );

  await page.goto('/privacidade?versao=v9');
  await expect(
    page.getByRole('heading', { level: 1, name: 'Versão não encontrada' }),
  ).toBeVisible();
});

test('"Pular para o conteúdo" is the first focus and the contents move the focus by keyboard', async ({
  page,
}) => {
  await page.goto('/privacidade');

  await page.keyboard.press('Tab');
  const skip = page.getByTestId('skip-to-content-link');
  await expect(skip).toBeFocused();
  const outline = await skip.evaluate((el) => getComputedStyle(el).outlineStyle);
  expect(outline).not.toBe('none');

  const firstLink = content(page).getByTestId('legal-toc').getByRole('link').first();
  for (
    let i = 0;
    i < 20 && !(await firstLink.evaluate((el) => el === document.activeElement));
    i++
  ) {
    await page.keyboard.press('Tab');
  }
  await expect(firstLink).toBeFocused();
  const target = (await firstLink.getAttribute('href'))?.slice(1) ?? '';
  await page.keyboard.press('Enter');
  await expect(page.locator(`[id="${target}"]`)).toBeFocused();
});

test('at 320 px the pages have no sideways scroll, the contents collapse and the table stacks', async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 800 });

  for (const path of ['/privacidade', '/termos']) {
    await page.goto(path);
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow, path).toBeLessThanOrEqual(0);
  }
  await page.goto('/privacidade');
  const toggle = content(page).getByTestId('legal-toc-toggle');
  await expect(toggle).toBeVisible();
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await toggle.click();
  await expect(content(page).getByTestId('legal-toc').getByRole('link').first()).toBeVisible();
  const display = await page
    .getByTestId('legal-retention-row')
    .first()
    .evaluate((el) => getComputedStyle(el).display);
  expect(display).toBe('block');
});

test('passing the limit through the published port answers 429 with Retry-After (the gateway is not exempt)', async ({
  request,
}) => {
  let refused: Awaited<ReturnType<typeof request.get>> | undefined;
  for (let i = 0; i <= LEGAL_LIMIT_PER_MINUTE + 5 && !refused; i++) {
    const response = await request.get(`${API}/api/v1/legal/versions`);
    if (response.status() === 429) {
      refused = response;
    } else {
      expect(response.status()).toBe(200);
    }
  }

  expect(refused, 'the limit of 60 per minute answers 429').toBeDefined();
  expect(Number(refused?.headers()['retry-after'])).toBeGreaterThanOrEqual(1);
  expect(refused?.headers()['cache-control']).toBe('no-store');
  expect((await refused?.json())?.code).toBe('RATE_LIMITED');
});
