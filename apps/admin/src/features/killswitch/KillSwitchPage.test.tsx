import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import { messages } from '@/lib/i18n/messages';
import { tokenStore } from '@/lib/auth/token-store';
import { KillSwitchPage } from '@/features/killswitch/KillSwitchPage';
import { AdminShell } from '@/features/layout/AdminShell';
import { KILL_SWITCH_OFF, Providers, fakeAdminApi } from '@/test-support';

const text = messages.killSwitch;

const ON = {
  ...KILL_SWITCH_OFF,
  state: 'ON' as const,
  version: 1,
  changedBy: 'AdmW…1111',
  changedAt: '2026-11-05T14:00:00Z',
  reason: 'Fonte instável',
};

function renderPage(api = fakeAdminApi()) {
  render(
    <Providers api={api}>
      <AdminShell>
        <KillSwitchPage timeZone="UTC" />
      </AdminShell>
    </Providers>,
  );
  return api;
}

describe('kill switch screen (BR2.7, BR7.2, AC7.2.1, AC7.2.4)', () => {
  beforeEach(() => tokenStore.clear());

  it('shows OFF, never changed, and the action to switch it on', async () => {
    renderPage();

    expect(await screen.findByTestId('kill-switch-state')).toHaveTextContent(
      text.state(text.stateOff),
    );
    expect(screen.getByTestId('kill-switch-change')).toHaveTextContent(text.neverChanged);
    expect(screen.getByTestId('kill-switch-activate')).toHaveTextContent(text.activate);
  });

  it('shows who, when, why, the pending bots and the reconciliation while ON', async () => {
    renderPage(
      fakeAdminApi({
        getKillSwitch: jest.fn(async () => ({ ...ON, pendingBots: 2, reconcileNeeded: true })),
      }),
    );

    expect(await screen.findByTestId('kill-switch-change')).toHaveTextContent('AdmW…1111');
    expect(screen.getByTestId('kill-switch-change')).toHaveTextContent('05/11/2026, 14:00');
    expect(screen.getByTestId('kill-switch-reason')).toHaveTextContent(
      text.reason('Fonte instável'),
    );
    expect(screen.getByTestId('kill-switch-pending')).toHaveTextContent(text.pending(2));
    expect(screen.getByTestId('kill-switch-reconciling')).toHaveTextContent(text.reconciling);
    expect(screen.getByTestId('kill-switch-deactivate')).toHaveTextContent(text.deactivate);
  });

  it('the activation dialog says how many active bots will be paused and asks for the reason', async () => {
    const api = renderPage();
    await userEvent.click(await screen.findByTestId('kill-switch-activate'));

    expect(screen.getByTestId('kill-switch-dialog-impact')).toHaveTextContent(text.activeBots(12));
    await userEvent.click(screen.getByTestId('kill-switch-dialog-confirm'));

    expect(screen.getByTestId('kill-switch-dialog-error')).toHaveTextContent(text.reasonEmpty);
    expect(api.changeKillSwitch).not.toHaveBeenCalled();
  });

  it('refuses a reason above 500 characters before calling the route', async () => {
    const api = renderPage();
    await userEvent.click(await screen.findByTestId('kill-switch-activate'));

    fireEvent.change(screen.getByTestId('kill-switch-dialog-reason'), {
      target: { value: 'x'.repeat(501) },
    });
    await userEvent.click(screen.getByTestId('kill-switch-dialog-confirm'));

    expect(screen.getByTestId('kill-switch-dialog-counter')).toHaveTextContent('501 / 500');
    expect(screen.getByTestId('kill-switch-dialog-error')).toHaveTextContent(text.reasonTooLong);
    expect(api.changeKillSwitch).not.toHaveBeenCalled();
  });

  it('switches ON after the confirmation and the banner appears on the screen', async () => {
    const api = renderPage(fakeAdminApi({ changeKillSwitch: jest.fn(async () => ON) }));
    await userEvent.click(await screen.findByTestId('kill-switch-activate'));

    await userEvent.type(screen.getByTestId('kill-switch-dialog-reason'), '  Fonte instável  ');
    await userEvent.click(screen.getByTestId('kill-switch-dialog-confirm'));

    await waitFor(() => expect(screen.queryByTestId('kill-switch-dialog')).toBeNull());
    expect(api.changeKillSwitch).toHaveBeenCalledWith(
      { state: 'ON', reason: 'Fonte instável' },
      'key-1',
    );
    expect(await screen.findByTestId('kill-switch-banner')).toHaveTextContent(messages.banner.on);
    expect(screen.getByTestId('kill-switch-state')).toHaveTextContent(text.stateOn);
  });

  it('the deactivation dialog says the bots stay paused until each owner resumes', async () => {
    const api = renderPage(
      fakeAdminApi({
        getKillSwitch: jest.fn(async () => ON),
        changeKillSwitch: jest.fn(async () => ({ ...ON, state: 'OFF' as const, version: 2 })),
      }),
    );
    await userEvent.click(await screen.findByTestId('kill-switch-deactivate'));

    expect(screen.getByTestId('kill-switch-dialog-impact')).toHaveTextContent(text.deactivateBody);
    await userEvent.type(screen.getByTestId('kill-switch-dialog-reason'), 'Fonte voltou');
    await userEvent.click(screen.getByTestId('kill-switch-dialog-confirm'));

    await waitFor(() => expect(screen.queryByTestId('kill-switch-banner')).toBeNull());
    expect(api.changeKillSwitch).toHaveBeenCalledWith(
      { state: 'OFF', reason: 'Fonte voltou' },
      'key-1',
    );
  });

  it('keeps the dialog open with the error and reuses the key on a new attempt', async () => {
    const change = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
      .mockResolvedValueOnce(ON);
    renderPage(fakeAdminApi({ changeKillSwitch: change }));
    await userEvent.click(await screen.findByTestId('kill-switch-activate'));
    await userEvent.type(screen.getByTestId('kill-switch-dialog-reason'), 'Repetir');

    await userEvent.click(screen.getByTestId('kill-switch-dialog-confirm'));
    expect(await screen.findByTestId('kill-switch-dialog-error')).toHaveTextContent(
      text.changeError,
    );
    await userEvent.click(screen.getByTestId('kill-switch-dialog-confirm'));

    await waitFor(() => expect(change).toHaveBeenCalledTimes(2));
    expect(change.mock.calls[0][1]).toBe(change.mock.calls[1][1]);
  });

  it('Esc closes the dialog without a call and the focus returns to the action', async () => {
    const api = renderPage();
    const activate = await screen.findByTestId('kill-switch-activate');
    await userEvent.click(activate);
    expect(screen.getByTestId('kill-switch-dialog-reason')).toHaveFocus();

    await userEvent.keyboard('{Escape}');

    expect(screen.queryByTestId('kill-switch-dialog')).toBeNull();
    expect(api.changeKillSwitch).not.toHaveBeenCalled();
    await waitFor(() => expect(activate).toHaveFocus());
  });

  it('keeps Tab inside the dialog', async () => {
    renderPage();
    await userEvent.click(await screen.findByTestId('kill-switch-activate'));

    await userEvent.tab({ shift: true });
    expect(screen.getByTestId('kill-switch-dialog-cancel')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('kill-switch-dialog-reason')).toHaveFocus();
  });

  it('shows the loading and the error states', async () => {
    renderPage(
      fakeAdminApi({
        getKillSwitch: jest.fn(async () => {
          throw new ApiError(503, 'SERVICE_UNAVAILABLE');
        }),
      }),
    );

    expect(await screen.findByTestId('kill-switch-error')).toHaveTextContent(text.error);
  });
});
