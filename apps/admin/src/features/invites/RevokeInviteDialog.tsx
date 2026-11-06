'use client';

import { useState } from 'react';
import { Dialog } from '@/components/Dialog';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { Invite } from '@/lib/api/admin-api';
import { messages } from '@/lib/i18n/messages';

const text = messages.invites;

/**
 * BR5.3: "Revogar este convite?" before the call; a 409 INVITE_NOT_REVOCABLE shows the reason (USED or EXPIRED) and the
 * list refreshes the row.
 */
export function RevokeInviteDialog({
  invite,
  onClose,
  onRevoked,
  onRefresh,
  onFailure,
  returnFocusTo,
}: {
  invite: Invite;
  onClose: () => void;
  onRevoked: (invite: Invite) => void;
  onRefresh: () => void;
  onFailure: (failure: unknown) => boolean;
  returnFocusTo?: HTMLElement | null;
}) {
  const api = useApi();
  const [key] = useState(() => api.newKey());
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);

  async function confirm() {
    setSending(true);
    setError(null);
    try {
      onRevoked(await api.revokeInvite(invite.inviteId, key));
    } catch (failure) {
      if (onFailure(failure)) {
        return;
      }
      if (failure instanceof ApiError && failure.code === 'INVITE_NOT_REVOCABLE') {
        setError(text.notRevocable[failure.reason ?? ''] ?? text.revokeError);
        onRefresh();
      } else {
        setError(text.revokeError);
      }
      setSending(false);
    }
  }

  return (
    <Dialog
      title={text.revokeTitle}
      testId="revoke-invite-dialog"
      onClose={onClose}
      returnFocusTo={returnFocusTo}
    >
      <p>{text.revokeBody(invite.codeSuffix)}</p>
      {error ? (
        <p role="alert" className="field-error" data-testid="revoke-invite-error">
          {error}
        </p>
      ) : null}
      <div className="dialog-actions">
        <button
          type="button"
          className="danger"
          disabled={sending}
          data-testid="revoke-invite-confirm"
          onClick={() => void confirm()}
        >
          {text.revokeConfirm}
        </button>
        <button
          type="button"
          className="secondary"
          data-testid="revoke-invite-cancel"
          onClick={onClose}
        >
          {text.cancel}
        </button>
      </div>
    </Dialog>
  );
}
