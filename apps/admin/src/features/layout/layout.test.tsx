import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { messages } from '@/lib/i18n/messages';
import { tokenStore } from '@/lib/auth/token-store';
import { AdminShell } from '@/features/layout/AdminShell';
import { KILL_SWITCH_OFF, Providers, fakeAdminApi } from '@/test-support';

function renderShell(api = fakeAdminApi()) {
  render(
    <Providers api={api}>
      <AdminShell>
        <p data-testid="protected-page">conteúdo</p>
      </AdminShell>
    </Providers>,
  );
  return api;
}

describe('administrator layout and kill switch banner (BR7.1, BR1.6, AC7.4.2)', () => {
  beforeEach(() => tokenStore.clear());

  it('shows the name, the four sections, Sair and the note about the data of the guests', async () => {
    renderShell();

    const nav = await screen.findByTestId('admin-navigation');
    expect(screen.getByTestId('admin-app-name')).toHaveTextContent('MemeTrade Admin');
    expect(
      within(nav)
        .getAllByRole('link')
        .map((link) => link.textContent),
    ).toEqual(['Estado', 'Convites', 'Kill switch', 'Feedback', 'Pedidos LGPD']);
    expect(screen.getByTestId('admin-no-personal-data')).toHaveTextContent(
      messages.app.noPersonalData,
    );
    expect(screen.getByTestId('admin-sign-out')).toHaveTextContent('Sair');
  });

  it('shows no banner while the kill switch is OFF', async () => {
    renderShell();

    await screen.findByTestId('protected-page');
    expect(screen.queryByTestId('kill-switch-banner')).toBeNull();
  });

  it('shows "Kill switch ACIONADO" in text and icon while it is ON', async () => {
    renderShell(
      fakeAdminApi({
        getKillSwitch: jest.fn(async () => ({ ...KILL_SWITCH_OFF, state: 'ON' as const })),
      }),
    );

    const banner = await screen.findByTestId('kill-switch-banner');
    expect(banner).toHaveTextContent(messages.banner.on);
    expect(banner).toHaveTextContent('🛑');
    expect(screen.queryByTestId('kill-switch-banner-pending')).toBeNull();
  });

  it('adds the pending bots and the reconciliation in progress', async () => {
    renderShell(
      fakeAdminApi({
        getKillSwitch: jest.fn(async () => ({
          ...KILL_SWITCH_OFF,
          state: 'ON' as const,
          pendingBots: 3,
          reconcileNeeded: true,
        })),
      }),
    );

    expect(await screen.findByTestId('kill-switch-banner-pending')).toHaveTextContent(
      messages.banner.pending(3),
    );
    expect(screen.getByTestId('kill-switch-banner-reconciling')).toHaveTextContent(
      messages.banner.reconciling,
    );
  });

  it('the keyboard reaches the navigation first (the skip link lives in the root layout)', async () => {
    renderShell();
    await screen.findByTestId('protected-page');

    await userEvent.tab();

    expect(document.activeElement?.getAttribute('data-testid')).toBe('admin-nav-status');
  });
});
