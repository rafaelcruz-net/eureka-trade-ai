'use client';

import { useState } from 'react';
import { Dialog } from '@/components/Dialog';
import type { AdminDataRequest, AnonymizationResult } from '@/lib/api/admin-api';
import { useApi } from '@/lib/api/ApiProvider';
import { messages } from '@/lib/i18n/messages';
import { useFailure } from '@/features/auth/use-failure';
import { reasonOf } from '@/features/data-requests/errors';

const text = messages.dataRequests.anonymization;

/** BR5.1, BR5.9: the exact text the administrator types. */
export const CONFIRMATION = 'ANONIMIZAR';

/**
 * u5-anonymization T8 (F1; BR5.9; NFR4.1): says what leaves and what stays, enables "Anonimizar" only with the exact
 * text ANONIMIZAR (the reason of the disabled button is visible text linked by `aria-describedby`), keeps the focus
 * inside (Tab cycles), closes with Esc and returns the focus to the button that opened it ({@link Dialog}). A refusal
 * (409, 422) or a 503 is said in text with an icon in an `aria-live` region and the typed text stays; the same
 * Idempotency-Key is reused on a new attempt of the same dialog, so a repetition after a 503 never acts twice.
 */
export function AnonymizeAccountDialog({
  request,
  onClose,
  onAnonymized,
  returnFocusTo,
  newKey,
}: {
  request: AdminDataRequest;
  onClose: () => void;
  onAnonymized: (result: AnonymizationResult) => void;
  returnFocusTo?: HTMLElement | null;
  newKey?: () => string;
}) {
  const api = useApi();
  const handled = useFailure();
  const [key] = useState(() => (newKey ? newKey() : api.newKey()));
  const [typed, setTyped] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const ready = typed === CONFIRMATION;

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!ready || sending) {
      return;
    }
    setSending(true);
    setError(null);
    try {
      onAnonymized(await api.anonymizeAccount(request.requestId, typed, key));
    } catch (failure) {
      if (!handled(failure)) {
        setError(reasonOf(failure));
      }
      setSending(false);
    }
  }

  return (
    <Dialog
      title={text.title}
      testId="anonymize-dialog"
      onClose={onClose}
      returnFocusTo={returnFocusTo}
    >
      <p>{text.intro}</p>
      <h3>{text.removedTitle}</h3>
      <ul data-testid="anonymize-dialog-removed">
        {text.removed.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
      <h3>{text.keptTitle}</h3>
      <ul data-testid="anonymize-dialog-kept">
        {text.kept.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
      <form onSubmit={(event) => void submit(event)} noValidate>
        <label htmlFor="anonymize-dialog-confirmation">{text.confirmLabel}</label>
        <input
          id="anonymize-dialog-confirmation"
          type="text"
          autoComplete="off"
          spellCheck={false}
          value={typed}
          data-testid="anonymize-dialog-confirmation"
          onChange={(event) => setTyped(event.target.value)}
        />
        {!ready ? (
          <p id="anonymize-dialog-reason" data-testid="anonymize-dialog-reason">
            {text.disabledReason}
          </p>
        ) : null}
        <div aria-live="polite" data-testid="anonymize-dialog-messages">
          {error ? (
            <p className="field-error" data-testid="anonymize-dialog-error">
              <span aria-hidden="true">⚠</span> {error}
            </p>
          ) : null}
        </div>
        <div className="dialog-actions">
          <button
            type="submit"
            className="danger"
            disabled={!ready || sending}
            aria-describedby={!ready ? 'anonymize-dialog-reason' : undefined}
            data-testid="anonymize-dialog-submit"
          >
            {sending ? text.submitting : text.submit}
          </button>
          <button
            type="button"
            className="secondary"
            data-testid="anonymize-dialog-cancel"
            onClick={onClose}
          >
            {text.cancel}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
