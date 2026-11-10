'use client';

import { useRef, useState } from 'react';
import type { AdminDataRequest, AnonymizationResult } from '@/lib/api/admin-api';
import { messages } from '@/lib/i18n/messages';
import { AnonymizeAccountDialog } from '@/features/data-requests/AnonymizeAccountDialog';

const text = messages.dataRequests.anonymization;

/** BR5.1: only a DELETION in progress, linked to an account that is not anonymized yet, offers the anonymization. */
export function canAnonymize(request: AdminDataRequest): boolean {
  return (
    request.type === 'DELETION' &&
    request.status === 'IN_PROGRESS' &&
    request.userId !== null &&
    request.userId !== undefined &&
    !request.accountAnonymized
  );
}

/**
 * u5-anonymization (T6a, frontend-components.md): "Anonimizar conta" in the detail of a DELETION in progress with a
 * linked account; hidden in any other case. It opens T8 and receives the focus back when the dialog closes.
 */
export function AnonymizeAccountButton({
  request,
  onAnonymized,
  newKey,
}: {
  request: AdminDataRequest;
  onAnonymized: (result: AnonymizationResult) => void;
  newKey?: () => string;
}) {
  const button = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);

  if (!canAnonymize(request)) {
    return null;
  }
  return (
    <>
      <button
        ref={button}
        type="button"
        className="danger"
        data-testid="anonymize-open"
        onClick={() => setOpen(true)}
      >
        {text.open}
      </button>
      {open ? (
        <AnonymizeAccountDialog
          request={request}
          returnFocusTo={button.current}
          newKey={newKey}
          onClose={() => setOpen(false)}
          onAnonymized={(result) => {
            setOpen(false);
            onAnonymized(result);
          }}
        />
      ) : null}
    </>
  );
}
