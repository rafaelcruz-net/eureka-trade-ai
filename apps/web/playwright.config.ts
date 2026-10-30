import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests run against the environment that scripts/verify.sh brings up with
 * docker-compose.yml + infra/docker-compose.test.yml; no web server is started from here.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  timeout: 60_000,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.WEB_BASE_URL ?? 'http://localhost:3000',
    trace: 'retain-on-failure',
    viewport: { width: 1440, height: 900 },
    locale: 'pt-BR',
    timezoneId: 'America/Sao_Paulo',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
  ],
});
