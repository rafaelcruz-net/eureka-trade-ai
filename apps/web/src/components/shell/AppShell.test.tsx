import { act, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import { ApiError } from '@/lib/api/client';
import { tokenStore } from '@/lib/auth/token-store';
import { messages } from '@/lib/i18n/messages';
import { ACCOUNT, fakeApi, Providers } from '@/features/auth/test-support';
import { HOME, safeOrigin } from '@/features/auth/origin';
import { BotsShell } from '@/features/bots/BotsShell';
import { AppShell } from '@/components/shell/AppShell';
import { JOIN_WINDOW_MS, useAnnounce } from '@/components/shell/NoticesRegion';
import { NAV_ITEMS, type ShellSection } from '@/components/shell/PrimaryNav';
import { resetWalletCache } from '@/components/shell/WalletBadge';

/**
 * Passo 9 of U9 (BR1.1, BR6.1 to BR6.4, BR7.2, BR7.3; NFR7.1; CT-UX-8, CT-UX-9): the single shell of the logged
 * screens S2 to S8.
 */
jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));

const nav = messages.nav;

function renderShell(current: ShellSection = 'dashboard', api = fakeApi()) {
  tokenStore.set('access-token-value');
  render(
    <Providers api={api}>
      <AppShell current={current} titleId="screen-title">
        <h1 id="screen-title">Tela</h1>
      </AppShell>
    </Providers>,
  );
  return api;
}

beforeEach(() => resetWalletCache());
afterEach(() => {
  jest.useRealTimers();
  tokenStore.clear();
});

describe('AppShell', () => {
  it('lists Painel, Scanner, Bots PAPER, P&L PAPER and Conta in this order', async () => {
    renderShell();

    const links = within(screen.getByRole('navigation', { name: nav.label })).getAllByRole('link');
    expect(links.map((link) => link.textContent)).toEqual([
      'Painel',
      'Scanner',
      'Bots PAPER',
      'P&L PAPER',
      'Conta',
    ]);
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '/',
      '/scanner',
      '/bots',
      '/pnl',
      '/conta',
    ]);
    await screen.findByText(`Carteira ${ACCOUNT.walletAddressMasked}`);
  });

  it.each(NAV_ITEMS.map((item) => item.section))(
    'marks only %s as the current page',
    async (section) => {
      renderShell(section);

      const current = screen
        .getAllByRole('link')
        .filter((link) => link.getAttribute('aria-current') === 'page');
      expect(current).toEqual([screen.getByTestId(`app-nav-${section}-link`)]);
      await screen.findByText(`Carteira ${ACCOUNT.walletAddressMasked}`);
    },
  );

  it('the wizard (S4) and the bot screen (S5) mark "Bots PAPER"', async () => {
    tokenStore.set('access-token-value');
    render(
      <Providers api={fakeApi()}>
        <BotsShell title="Assistente do bot" testId="wizard">
          <p>conteúdo</p>
        </BotsShell>
      </Providers>,
    );

    expect(screen.getByTestId('app-nav-bots-link')).toHaveAttribute('aria-current', 'page');
    expect(screen.getByTestId('wizard-title')).toHaveFocus();
    expect(screen.getByRole('main')).toHaveAttribute('aria-labelledby', 'wizard-title');
    await screen.findByText(`Carteira ${ACCOUNT.walletAddressMasked}`);
  });

  it('has the named regions: main navigation, main content #conteudo and "Avisos"', async () => {
    renderShell();

    expect(screen.getByRole('navigation', { name: 'Navegação principal' })).toBeInTheDocument();
    const main = screen.getByRole('main');
    expect(main).toHaveAttribute('id', 'conteudo');
    expect(main).toHaveAccessibleName('Tela');
    const notices = screen.getByRole('region', { name: 'Avisos' });
    expect(notices).toHaveAttribute('aria-live', 'polite');
    expect(screen.getByText(nav.paperMode)).toBeInTheDocument();
    await screen.findByText(`Carteira ${ACCOUNT.walletAddressMasked}`);
  });

  it('shows only the abbreviated wallet, never the full address', async () => {
    const full = 'TestWa11et1111111111111111111111111111111111';
    renderShell(
      'dashboard',
      fakeApi({
        getAccount: jest.fn(async () => ({ ...ACCOUNT, walletAddressMasked: 'Test…1111' })),
      }),
    );

    expect(await screen.findByText('Carteira Test…1111')).toHaveAttribute(
      'data-testid',
      'app-wallet-badge',
    );
    expect(document.body.innerHTML).not.toContain(full);
    expect(screen.getByTestId('app-wallet-badge')).not.toHaveAttribute('title');
  });

  it('a failed read of the account shows "Carteira" without an address', async () => {
    const getAccount = jest.fn(async () => {
      throw new ApiError(503, 'SERVICE_UNAVAILABLE');
    });
    renderShell('dashboard', fakeApi({ getAccount }));

    await waitFor(() => expect(getAccount).toHaveBeenCalled());
    expect(screen.getByTestId('app-wallet-badge')).toHaveTextContent(/^Carteira$/);
  });

  it('reads the account once per session and again after the session ends', async () => {
    const api = fakeApi();
    renderShell('dashboard', api);
    await screen.findByText(`Carteira ${ACCOUNT.walletAddressMasked}`);
    render(
      <Providers api={api}>
        <AppShell current="pnl" titleId="other">
          <h1 id="other">Outra</h1>
        </AppShell>
      </Providers>,
    );
    expect(screen.getAllByTestId('app-wallet-badge')[1]).toHaveTextContent(
      ACCOUNT.walletAddressMasked,
    );
    expect(api.getAccount).toHaveBeenCalledTimes(1);

    act(() => tokenStore.clear());
    tokenStore.set('access-token-value');
    render(
      <Providers api={api}>
        <AppShell current="scanner" titleId="third">
          <h1 id="third">Terceira</h1>
        </AppShell>
      </Providers>,
    );
    await waitFor(() => expect(api.getAccount).toHaveBeenCalledTimes(2));
  });

  it('keeps "Feedback" and "Sair" of the old account menu', async () => {
    renderShell();

    const shell = screen.getByTestId('app-shell');
    expect(within(shell).getByTestId('app-feedback-button')).toHaveTextContent(
      messages.feedback.open,
    );
    expect(within(shell).getByTestId('app-sign-out-button')).toHaveTextContent(
      messages.app.signOut,
    );
    await screen.findByText(`Carteira ${ACCOUNT.walletAddressMasked}`);
  });

  it('the h1 of every screen that is a menu item equals the text of the item', () => {
    expect(messages.dashboard.title).toBe(nav.items.dashboard);
    expect(messages.scanner.title).toBe(nav.items.scanner);
    expect(messages.bots.listTitle).toBe(nav.items.bots);
    expect(messages.pnl.title).toBe(nav.items.pnl);
    expect(messages.account.title).toBe(nav.items.account);
  });

  it('the entry without a pending origin leads to the dashboard and a pending origin is kept', () => {
    expect(HOME).toBe('/');
    expect(safeOrigin(undefined)).toBe('/');
    expect(safeOrigin('/')).toBe('/');
    expect(safeOrigin('/conta')).toBe('/conta');
    expect(safeOrigin('/scanner?minHolders=10')).toBe('/scanner?minHolders=10');
  });
});

describe('NoticesRegion', () => {
  it('joins the messages of 2 s into one announcement, without moving the focus', async () => {
    jest.useFakeTimers();
    tokenStore.set('access-token-value');
    let announce: (text: string) => void = () => undefined;
    function Speaker() {
      announce = useAnnounce();
      return (
        <button type="button" data-testid="focused">
          foco
        </button>
      );
    }
    render(
      <Providers api={fakeApi()}>
        <AppShell current="dashboard" titleId="t">
          <h1 id="t">Painel</h1>
          <Speaker />
        </AppShell>
      </Providers>,
    );
    screen.getByTestId('focused').focus();
    const region = screen.getByTestId('app-notices-region');

    act(() => {
      announce('Bot Alfa pausado por você');
      announce('Fonte B voltou a atualizar');
      announce('Bot Alfa pausado por você');
      announce('');
    });
    act(() => jest.advanceTimersByTime(JOIN_WINDOW_MS - 1));
    expect(region).toHaveTextContent('');
    act(() => jest.advanceTimersByTime(1));

    expect(region).toHaveTextContent('Bot Alfa pausado por você · Fonte B voltou a atualizar');
    expect(screen.getByTestId('focused')).toHaveFocus();
  });

  it('announcing outside the shell does nothing', () => {
    const { result } = renderHook(() => useAnnounce());

    expect(() => result.current('ignored')).not.toThrow();
  });
});
