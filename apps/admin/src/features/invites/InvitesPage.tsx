'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type { Invite } from '@/lib/api/admin-api';
import { browserTimeZone, dateTime } from '@/lib/format';
import { messages } from '@/lib/i18n/messages';
import { useFailure } from '@/features/auth/use-failure';
import { IssueInviteDialog } from '@/features/invites/IssueInviteDialog';
import { RevokeInviteDialog } from '@/features/invites/RevokeInviteDialog';

const text = messages.invites;

/**
 * A2, the invitations (F6; BR5.1 to BR5.3; AC7.1.1 to AC7.1.4): the list newest first with the state in TEXT, the
 * validity and the suffix of the code, by cursor; "+ Emitir convite" and "Revogar" behind their dialogs.
 */
export function InvitesPage({
  guestAppOrigin = 'http://localhost:3000',
  timeZone = browserTimeZone(),
}: {
  guestAppOrigin?: string;
  timeZone?: string;
}) {
  const api = useApi();
  const handled = useFailure();
  const [items, setItems] = useState<Invite[] | null>(null);
  const [next, setNext] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [issuing, setIssuing] = useState(false);
  const [revoking, setRevoking] = useState<Invite | null>(null);
  const opener = useRef<HTMLElement | null>(null);

  const load = useCallback(
    async (cursor: string | null) => {
      setFailed(false);
      try {
        const page = await api.listInvites(cursor);
        setItems((current) => (cursor && current ? [...current, ...page.items] : page.items));
        setNext(page.nextCursor ?? null);
      } catch (failure) {
        if (!handled(failure)) {
          setFailed(true);
        }
      }
    },
    [api, handled],
  );

  useEffect(() => {
    void load(null);
  }, [load]);

  function replaceRow(invite: Invite) {
    setItems((current) =>
      (current ?? []).map((row) => (row.inviteId === invite.inviteId ? invite : row)),
    );
  }

  return (
    <section aria-labelledby="invites-title" data-testid="invites-page">
      <h1 id="invites-title">{text.title}</h1>
      <button
        type="button"
        data-testid="invites-issue"
        onClick={(event) => {
          opener.current = event.currentTarget;
          setIssuing(true);
        }}
      >
        {text.issue}
      </button>
      {failed ? (
        <p role="alert" className="field-error" data-testid="invites-error">
          {text.error}
        </p>
      ) : null}
      {items === null && !failed ? (
        <p role="status" data-testid="invites-loading">
          {text.loading}
        </p>
      ) : null}
      {items !== null && items.length === 0 ? (
        <p data-testid="invites-empty">{text.empty}</p>
      ) : null}
      {items !== null && items.length > 0 ? (
        <table data-testid="invites-table">
          <thead>
            <tr>
              <th scope="col">{text.columns.status}</th>
              <th scope="col">{text.columns.suffix}</th>
              <th scope="col">{text.columns.expires}</th>
              <th scope="col">{text.columns.created}</th>
              <th scope="col">{text.columns.actions}</th>
            </tr>
          </thead>
          <tbody>
            {items.map((invite) => (
              <tr key={invite.inviteId} data-testid={`invite-row-${invite.inviteId}`}>
                <td data-testid="invite-status">{text.status[invite.status]}</td>
                <td>…{invite.codeSuffix}</td>
                <td>{dateTime(invite.expiresAt, timeZone)}</td>
                <td>{dateTime(invite.createdAt, timeZone)}</td>
                <td>
                  {invite.status === 'PENDING' ? (
                    <button
                      type="button"
                      className="secondary"
                      data-testid={`invite-revoke-${invite.inviteId}`}
                      onClick={(event) => {
                        opener.current = event.currentTarget;
                        setRevoking(invite);
                      }}
                    >
                      {text.revoke}
                    </button>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {next ? (
        <button
          type="button"
          className="secondary"
          data-testid="invites-more"
          onClick={() => void load(next)}
        >
          {text.more}
        </button>
      ) : null}
      {issuing ? (
        <IssueInviteDialog
          guestAppOrigin={guestAppOrigin}
          returnFocusTo={opener.current}
          onFailure={handled}
          onClose={() => setIssuing(false)}
          onIssued={(invite) => {
            // The list never keeps the code: only the row without it (BR5.1).
            const { code: _code, ...row } = invite;
            void _code;
            setItems((current) => [row, ...(current ?? [])]);
          }}
        />
      ) : null}
      {revoking ? (
        <RevokeInviteDialog
          invite={revoking}
          returnFocusTo={opener.current}
          onFailure={handled}
          onClose={() => setRevoking(null)}
          onRefresh={() => void load(null)}
          onRevoked={(invite) => {
            replaceRow(invite);
            setRevoking(null);
          }}
        />
      ) : null}
    </section>
  );
}
