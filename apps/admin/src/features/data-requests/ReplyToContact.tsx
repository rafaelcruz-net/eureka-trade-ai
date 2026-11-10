'use client';

import { useState } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type { AdminDataRequest } from '@/lib/api/admin-api';
import { dateTime } from '@/lib/format';
import { messages } from '@/lib/i18n/messages';
import { useFailure } from '@/features/auth/use-failure';
import { copyText, reasonOf } from '@/features/data-requests/errors';

const text = messages.dataRequests;

/** The text ready for the contact: the response, or the reason of a refusal. */
export function replyText(request: AdminDataRequest, timeZone: string): string {
  const body =
    request.status === 'REFUSED' ? (request.refusalReason ?? '') : (request.response ?? '');
  return text.reply.text(dateTime(request.receivedAt, timeZone), body);
}

/**
 * u4-data-requests (F3 step 5; BR4.17; US5.6): for a closed EMAIL request, the reply ready to copy to the e-mail of the
 * DPO and "Marcar como enviada", which records the instant once (a second click keeps the first instant).
 */
export function ReplyToContact({
  request,
  timeZone,
  onChanged,
  newKey,
}: {
  request: AdminDataRequest;
  timeZone: string;
  onChanged: (request: AdminDataRequest) => void;
  newKey: () => string;
}) {
  const api = useApi();
  const handled = useFailure();
  const [key] = useState(newKey);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const reply = replyText(request, timeZone);

  async function markSent() {
    setBusy(true);
    setError(null);
    try {
      onChanged(await api.markReplySent(request.requestId, key));
    } catch (failure) {
      if (!handled(failure)) {
        setError(reasonOf(failure));
      }
    } finally {
      setBusy(false);
    }
  }

  async function copy() {
    setNote((await copyText(reply)) ? text.reply.copied : text.copyFailed);
  }

  return (
    <section aria-labelledby="reply-title" data-testid="reply-to-contact">
      <h2 id="reply-title">{text.reply.title}</h2>
      <p>{text.reply.intro}</p>
      <p className="pre-wrap" data-testid="reply-text">
        {reply}
      </p>
      <button
        type="button"
        className="secondary"
        data-testid="reply-copy"
        onClick={() => void copy()}
      >
        {text.reply.copy}
      </button>
      {request.replySentAt ? (
        <p data-testid="reply-sent-at">
          {text.reply.sentAt(dateTime(request.replySentAt, timeZone))}
        </p>
      ) : (
        <button
          type="button"
          disabled={busy}
          data-testid="reply-mark-sent"
          onClick={() => void markSent()}
        >
          {text.reply.markSent}
        </button>
      )}
      <div aria-live="polite">
        {note ? <p data-testid="reply-note">{note}</p> : null}
        {error ? (
          <p className="field-error" data-testid="reply-error">
            {error}
          </p>
        ) : null}
      </div>
    </section>
  );
}
