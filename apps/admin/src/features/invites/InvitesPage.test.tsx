import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import type { Invite } from '@/lib/api/admin-api';
import { messages } from '@/lib/i18n/messages';
import { tokenStore } from '@/lib/auth/token-store';
import { InvitesPage } from '@/features/invites/InvitesPage';
import { AdminShell } from '@/features/layout/AdminShell';
import { Providers, fakeAdminApi } from '@/test-support';

const text = messages.invites;

function invite(id: string, status: Invite['status'], suffix = 'Ab12'): Invite {
  return {
    inviteId: id,
    tenantId: 't-1',
    status,
    codeSuffix: suffix,
    createdAt: '2026-11-01T12:00:00Z',
    expiresAt: '2026-11-08T12:00:00Z',
    usedAt: null,
    revokedAt: null,
  };
}

function renderInvites(api = fakeAdminApi()) {
  render(
    <Providers api={api}>
      <AdminShell>
        <InvitesPage guestAppOrigin="http://localhost:3000/" timeZone="UTC" />
      </AdminShell>
    </Providers>,
  );
  return api;
}

describe('A2 invitations (BR5.1 to BR5.3, AC7.1.1 to AC7.1.3)', () => {
  beforeEach(() => tokenStore.clear());

  it('lists the invitations with the state in text, the suffix and the validity', async () => {
    renderInvites(
      fakeAdminApi({
        listInvites: jest.fn(async () => ({
          items: [invite('i-1', 'PENDING'), invite('i-2', 'EXPIRED', 'Zz99')],
          nextCursor: null,
        })),
      }),
    );

    const first = await screen.findByTestId('invite-row-i-1');
    expect(within(first).getByTestId('invite-status')).toHaveTextContent('PENDENTE');
    expect(first).toHaveTextContent('…Ab12');
    expect(first).toHaveTextContent('08/11/2026, 12:00');
    expect(
      within(screen.getByTestId('invite-row-i-2')).getByTestId('invite-status'),
    ).toHaveTextContent('EXPIRADO');
    expect(screen.queryByTestId('invite-revoke-i-2')).toBeNull();
  });

  it('shows the empty and the error states', async () => {
    renderInvites();
    expect(await screen.findByTestId('invites-empty')).toHaveTextContent(text.empty);
  });

  it('shows an error when the list cannot be read', async () => {
    renderInvites(
      fakeAdminApi({
        listInvites: jest.fn(async () => {
          throw new ApiError(503, 'SERVICE_UNAVAILABLE');
        }),
      }),
    );
    expect(await screen.findByTestId('invites-error')).toHaveTextContent(text.error);
  });

  it('loads the next page by cursor', async () => {
    const api = fakeAdminApi({
      listInvites: jest
        .fn()
        .mockResolvedValueOnce({ items: [invite('i-1', 'PENDING')], nextCursor: 'c-2' })
        .mockResolvedValueOnce({ items: [invite('i-2', 'USED')], nextCursor: null }),
    });
    renderInvites(api);

    await userEvent.click(await screen.findByTestId('invites-more'));

    expect(await screen.findByTestId('invite-row-i-2')).toBeInTheDocument();
    expect(api.listInvites).toHaveBeenLastCalledWith('c-2');
    expect(screen.queryByTestId('invites-more')).toBeNull();
  });

  it('the issuance requires a tenant and a validity of 1 to 720 hours', async () => {
    const api = renderInvites();
    await userEvent.click(await screen.findByTestId('invites-issue'));
    expect(screen.getByTestId('issue-invite-hours')).toHaveValue('168');

    await userEvent.click(screen.getByTestId('issue-invite-submit'));
    expect(screen.getByTestId('issue-invite-error')).toHaveTextContent(text.tenantRequired);

    await userEvent.selectOptions(await screen.findByTestId('issue-invite-tenant'), 't-1');
    fireEvent.change(screen.getByTestId('issue-invite-hours'), { target: { value: '721' } });
    await userEvent.click(screen.getByTestId('issue-invite-submit'));

    expect(screen.getByTestId('issue-invite-error')).toHaveTextContent(text.validityInvalid);
    expect(api.issueInvite).not.toHaveBeenCalled();
  });

  it('shows the full code once with the link, and the list keeps only the suffix', async () => {
    const issued = { ...invite('i-9', 'PENDING', 'Cd34'), code: 'CODIGO-COMPLETO-Cd34' };
    const writeText = jest.fn(async () => undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    const api = renderInvites(fakeAdminApi({ issueInvite: jest.fn(async () => issued) }));
    await userEvent.click(await screen.findByTestId('invites-issue'));
    await userEvent.selectOptions(await screen.findByTestId('issue-invite-tenant'), 't-1');

    await userEvent.click(screen.getByTestId('issue-invite-submit'));

    expect(await screen.findByTestId('issue-invite-link')).toHaveValue(
      'http://localhost:3000/convite/CODIGO-COMPLETO-Cd34',
    );
    await userEvent.click(screen.getByTestId('issue-invite-copy'));
    expect(writeText).toHaveBeenCalledWith('http://localhost:3000/convite/CODIGO-COMPLETO-Cd34');
    expect(screen.getByTestId('issue-invite-copied')).toHaveTextContent(text.copied);
    expect(api.issueInvite).toHaveBeenCalledWith(
      expect.objectContaining({ tenantId: 't-1' }),
      'key-1',
    );
    await userEvent.click(screen.getByTestId('issue-invite-close'));
    expect(screen.getByTestId('invite-row-i-9')).toHaveTextContent('…Cd34');
    expect(screen.getByTestId('invites-page')).not.toHaveTextContent('CODIGO-COMPLETO');
  });

  it('shows the fields refused by the API on the form', async () => {
    renderInvites(
      fakeAdminApi({
        issueInvite: jest.fn(async () => {
          throw new ApiError(422, 'VALIDATION_FAILED', undefined, [
            { field: 'tenantId', message: 'inativo' },
          ]);
        }),
      }),
    );
    await userEvent.click(await screen.findByTestId('invites-issue'));
    await userEvent.selectOptions(await screen.findByTestId('issue-invite-tenant'), 't-1');

    await userEvent.click(screen.getByTestId('issue-invite-submit'));

    expect(await screen.findByTestId('issue-invite-error')).toHaveTextContent('tenantId: inativo');
  });

  it('asks before revoking and shows the row as REVOGADO afterwards', async () => {
    const api = renderInvites(
      fakeAdminApi({
        listInvites: jest.fn(async () => ({ items: [invite('i-1', 'PENDING')], nextCursor: null })),
        revokeInvite: jest.fn(async () => ({
          ...invite('i-1', 'REVOKED'),
          revokedAt: '2026-11-05T12:00:00Z',
        })),
      }),
    );
    await userEvent.click(await screen.findByTestId('invite-revoke-i-1'));

    expect(screen.getByTestId('revoke-invite-dialog')).toHaveTextContent(text.revokeTitle);
    expect(api.revokeInvite).not.toHaveBeenCalled();
    await userEvent.click(screen.getByTestId('revoke-invite-confirm'));

    await waitFor(() =>
      expect(
        within(screen.getByTestId('invite-row-i-1')).getByTestId('invite-status'),
      ).toHaveTextContent('REVOGADO'),
    );
    expect(api.revokeInvite).toHaveBeenCalledWith('i-1', 'key-1');
  });

  it('a 409 shows why it cannot be revoked and refreshes the list', async () => {
    const api = renderInvites(
      fakeAdminApi({
        listInvites: jest.fn(async () => ({ items: [invite('i-1', 'PENDING')], nextCursor: null })),
        revokeInvite: jest.fn(async () => {
          throw new ApiError(409, 'INVITE_NOT_REVOCABLE', 'USED');
        }),
      }),
    );
    await userEvent.click(await screen.findByTestId('invite-revoke-i-1'));

    await userEvent.click(screen.getByTestId('revoke-invite-confirm'));

    expect(await screen.findByTestId('revoke-invite-error')).toHaveTextContent(
      text.notRevocable.USED as string,
    );
    await waitFor(() => expect(api.listInvites).toHaveBeenCalledTimes(2));
  });

  it('Cancelar closes the confirmation without revoking', async () => {
    const api = renderInvites(
      fakeAdminApi({
        listInvites: jest.fn(async () => ({ items: [invite('i-1', 'PENDING')], nextCursor: null })),
      }),
    );
    await userEvent.click(await screen.findByTestId('invite-revoke-i-1'));

    await userEvent.click(screen.getByTestId('revoke-invite-cancel'));

    expect(screen.queryByTestId('revoke-invite-dialog')).toBeNull();
    expect(api.revokeInvite).not.toHaveBeenCalled();
  });
});
