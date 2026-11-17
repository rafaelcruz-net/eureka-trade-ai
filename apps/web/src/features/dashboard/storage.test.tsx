import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { BotSummary } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { resetWalletCache } from '@/components/shell/WalletBadge';
import { DashboardScreen } from '@/features/dashboard/DashboardScreen';
import { LiveChannelProvider } from '@/features/live/LiveChannelProvider';
import {
  BOT_A,
  botPage,
  botSummary,
  fakeChannel,
  fakeFetch,
  pnlReport,
  reconciliation,
  sseBlock,
  ZONE,
} from '@/features/dashboard/test-support';

/**
 * Passo 10 of U9 (NFR6.1, NFR6.3, NFR7.1): after opening the dashboard, receiving events, renewing the token and
 * pausing a bot, no key or value of localStorage, sessionStorage or IndexedDB, and no cookie readable by scripts,
 * holds the token or "Bearer"; and the console never shows the token, the wallet or the event id. The acceptance
 * marks of U3 in sessionStorage stay allowed (the check looks only for the token).
 */
jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => '/',
}));

const TOKEN = 'eyJhbGciOiJIUzI1NiJ9.dashboard-token.signature';
const RENEWED = 'eyJhbGciOiJIUzI1NiJ9.renewed-token.signature';

async function storedTexts(): Promise<string[]> {
  const texts: string[] = [];
  for (const storage of [window.localStorage, window.sessionStorage]) {
    for (let i = 0; i < storage.length; i++) {
      const key = storage.key(i) ?? '';
      texts.push(key, storage.getItem(key) ?? '');
    }
  }
  const idb = (window as { indexedDB?: IDBFactory }).indexedDB;
  if (idb && typeof idb.databases === 'function') {
    for (const database of await idb.databases()) {
      texts.push(database.name ?? '');
    }
  }
  texts.push(document.cookie);
  return texts;
}

afterEach(() => {
  tokenStore.clear();
  window.sessionStorage.clear();
  jest.restoreAllMocks();
  resetWalletCache();
});

it('the token never reaches the browser storage, a readable cookie or the console', async () => {
  const warn = jest.spyOn(console, 'warn');
  const error = jest.spyOn(console, 'error');
  const setItem = jest.spyOn(Storage.prototype, 'setItem');
  // U3 keeps its acceptance marks in sessionStorage; they are allowed.
  window.sessionStorage.setItem('memetrade.invite.acceptance', '{"terms":true}');
  const active: BotSummary = botSummary({ botId: BOT_A, state: 'ACTIVE' });
  const api = fakeApi({
    listBots: jest.fn(async (request: { state?: BotSummary['state'] } = {}) =>
      botPage(request.state === 'ACTIVE' ? [active] : []),
    ),
    getPnl: jest.fn(async () => pnlReport()),
    getReconciliation: jest.fn(async () => reconciliation()),
    refreshSession: jest.fn(async () => ({
      accessToken: RENEWED,
      accessTokenExpiresAt: '2026-10-28T12:30:00Z',
      sessionExpiresAt: '2026-10-29T00:00:00Z',
      role: 'BETA_USER' as const,
    })),
    runBotCommand: jest.fn(async () => ({
      ...active,
      state: 'PAUSED' as const,
      pauseReasons: ['PERSON' as const],
    })),
  });
  const first = fakeChannel();
  const net = fakeFetch();
  net.respond(first.response, fakeChannel().response);
  tokenStore.set(TOKEN);

  render(
    <Providers api={api}>
      <LiveChannelProvider fetchImpl={net.impl}>
        <DashboardScreen now={() => new Date('2026-10-28T12:00:00Z')} timeZone={ZONE} />
      </LiveChannelProvider>
    </Providers>,
  );
  await screen.findByTestId(`dashboard-bot-pause-button-${BOT_A}`);
  await act(async () => {
    first.push(sseBlock('heartbeat', { at: '2026-10-28T12:00:15Z' }, 'b11.l22.s33'));
    first.push(sseBlock('bot.state', '{broken', 'b12.l22.s33'));
    first.push(sseBlock('auth.expired', { expiredAt: '2026-10-28T12:15:00Z' }, 'b12.l22.s34'));
  });
  await waitFor(() => expect(net.requests).toHaveLength(2));
  await userEvent.click(screen.getByTestId(`dashboard-bot-pause-button-${BOT_A}`));
  await screen.findByText('Estado: Pausado');

  const stored = (await storedTexts()).join('\n');
  for (const secret of [TOKEN, RENEWED, 'Bearer']) {
    expect(stored).not.toContain(secret);
  }
  for (const call of setItem.mock.calls) {
    expect(call.join(' ')).not.toMatch(/Bearer|dashboard-token|renewed-token/);
  }
  expect(net.requests.every((request) => !request.url.includes('token'))).toBe(true);
  expect(net.requests[1]?.headers.Authorization).toBe(`Bearer ${RENEWED}`);
  const printed = [...warn.mock.calls, ...error.mock.calls].flat().map(String).join('\n');
  for (const secret of [TOKEN, RENEWED, 'Bearer', 'TestW', 'b11.l22.s33', 'b12.l22.s34']) {
    expect(printed).not.toContain(secret);
  }
  expect(document.body.innerHTML).not.toContain(TOKEN);
});
