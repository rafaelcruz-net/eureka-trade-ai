'use client';

import { useRef, useState } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import { useAdminAction } from '@/features/real/hooks/useAdminAction';
import { adminRealMessages as text } from '@/features/real/messages';
import { AdminActionDialog } from '@/features/real/components/AdminActionDialog';
import { RealSection } from '@/features/real/components/RealSection';

const copy = text.restore;

/**
 * 261113-trading-real-bot U6, A1 `/real/restauracao` (BR9.2, BR9.3; F6 step 4): the confirmation of the restore with
 * the reason. `409 RESTORE_NOT_CLEAN` says wallets are not clean yet (the contract names no wallet: the divergences show
 * them); `404` says there is no restore pending.
 */
export function RealRestoreScreen() {
  const api = useApi();
  const [open, setOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const opener = useRef<HTMLButtonElement>(null);
  const confirm = useAdminAction(
    (body: { reason: string }, key: string) => api.confirmRealRestore(body, key),
    api.newKey,
  );
  const missing = confirm.state.kind === 'error' && confirm.state.error.code === 'NOT_FOUND';

  return (
    <RealSection title={copy.title} testId="admin-real-restore">
      <p>{copy.intro}</p>
      <button
        ref={opener}
        type="button"
        onClick={() => setOpen(true)}
        data-testid="admin-real-restore-confirm"
      >
        {copy.confirm}
      </button>
      {notice && (
        <p role="status" data-testid="admin-real-restore-notice">
          {notice}
        </p>
      )}
      {open && (
        <AdminActionDialog
          title={copy.confirmTitle}
          effect={missing ? copy.none : copy.effect}
          testId="admin-real-restore-dialog"
          state={confirm.state}
          returnFocusTo={opener.current}
          onClose={() => {
            confirm.reset();
            setOpen(false);
          }}
          onSubmit={(reason) =>
            void confirm.submit({ reason }).then((result) => {
              if (result) {
                setOpen(false);
                setNotice(copy.done);
              }
            })
          }
        />
      )}
    </RealSection>
  );
}
