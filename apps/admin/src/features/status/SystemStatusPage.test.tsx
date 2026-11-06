import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import { messages } from '@/lib/i18n/messages';
import { tokenStore } from '@/lib/auth/token-store';
import { AdminShell } from '@/features/layout/AdminShell';
import { SystemStatusPage } from '@/features/status/SystemStatusPage';
import { Providers, STATUS, fakeAdminApi } from '@/test-support';

const text = messages.status;

function renderStatus(api = fakeAdminApi(), timeZone = 'America/Sao_Paulo') {
  render(
    <Providers api={api}>
      <AdminShell>
        <SystemStatusPage timeZone={timeZone} />
      </AdminShell>
    </Providers>,
  );
  return api;
}

describe('A1 system status (BR3.1 to BR3.3, AC7.4.1)', () => {
  beforeEach(() => tokenStore.clear());

  it('shows the loading state and then every count', async () => {
    const api = fakeAdminApi();
    let release: (value: typeof STATUS) => void = () => undefined;
    api.getSystemStatus.mockImplementation(() => new Promise((resolve) => (release = resolve)));
    renderStatus(api);

    expect(await screen.findByTestId('system-status-loading')).toHaveTextContent(text.loading);
    release(STATUS);

    expect(await screen.findByTestId('status-guests')).toHaveTextContent('42');
    expect(screen.getByTestId('status-invites')).toHaveTextContent('Pendentes: 3');
    expect(screen.getByTestId('status-bots')).toHaveTextContent('Ativo: 12');
    expect(screen.getByTestId('status-bots')).toHaveTextContent('Encerramento pendente: 0');
    expect(screen.getByTestId('status-kill-switch')).toHaveTextContent(text.switchOff);
    expect(screen.getByTestId('status-rejected')).toHaveTextContent('7');
    expect(screen.getByTestId('status-audit-outbox')).toHaveTextContent('0');
  });

  it('shows each source with its state in text and the time of its last collection', async () => {
    renderStatus();

    expect(await screen.findByTestId('status-source-sim-a')).toHaveTextContent(
      'Simulada A (simulada): OK',
    );
    expect(screen.getByTestId('status-source-sim-a')).toHaveTextContent(
      text.sourceUpdated('12:29'),
    );
    expect(screen.getByTestId('status-source-sim-b')).toHaveTextContent(text.sourceNever);
  });

  it('shows the time of the reading in the time zone of the browser', async () => {
    renderStatus(fakeAdminApi(), 'UTC');

    expect(await screen.findByTestId('system-status-updated')).toHaveTextContent(
      text.updated('15:30'),
    );
  });

  it('shows an UNKNOWN part as "desconhecido" with its reason, never zero, and the others normally', async () => {
    renderStatus(
      fakeAdminApi({
        getSystemStatus: jest.fn(async () => ({
          ...STATUS,
          rejectedOrdersToday: { status: 'UNKNOWN' as const, reason: 'TIMEOUT' as const },
          guestCount: { status: 'UNKNOWN' as const, reason: 'UNAVAILABLE' as const },
        })),
      }),
    );

    const rejected = await screen.findByTestId('status-rejected');
    expect(rejected).toHaveTextContent(`${text.unknown} (${text.unknownReason.TIMEOUT})`);
    // The value itself (the label mentions "00:00 UTC") never shows a number in place of the unknown count.
    expect(rejected.querySelector('dd')?.textContent).not.toMatch(/\d/);
    expect(rejected).toHaveAttribute('data-state', 'unknown');
    expect(screen.getByTestId('status-guests')).toHaveTextContent(
      text.unknownReason.UNAVAILABLE as string,
    );
    expect(screen.getByTestId('status-bots')).toHaveAttribute('data-state', 'known');
  });

  it('shows an error state different from loading and from unknown, and reloads', async () => {
    const api = fakeAdminApi({
      getSystemStatus: jest
        .fn()
        .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
        .mockResolvedValueOnce(STATUS),
    });
    renderStatus(api);

    expect(await screen.findByTestId('system-status-error')).toHaveTextContent(text.error);
    await userEvent.click(screen.getByTestId('system-status-refresh'));

    expect(await screen.findByTestId('status-guests')).toHaveTextContent('42');
    expect(screen.queryByTestId('system-status-error')).toBeNull();
  });

  it('shows "Acesso restrito" on a 403 and the entry on a 401', async () => {
    renderStatus(
      fakeAdminApi({
        getSystemStatus: jest.fn(async () => {
          throw new ApiError(403, 'FORBIDDEN_ROLE');
        }),
      }),
    );
    expect(await screen.findByTestId('admin-restricted')).toBeInTheDocument();
  });

  it('goes back to the entry when the session ended', async () => {
    renderStatus(
      fakeAdminApi({
        getSystemStatus: jest.fn(async () => {
          throw new ApiError(401, 'UNAUTHENTICATED');
        }),
      }),
    );

    await waitFor(() => expect(screen.getByTestId('admin-sign-in')).toBeInTheDocument());
    expect(tokenStore.get()).toBeNull();
  });
});
