'use client';

import { useState } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type { AdminDataRequest } from '@/lib/api/admin-api';
import { dateTime } from '@/lib/format';
import { messages } from '@/lib/i18n/messages';
import { useFailure } from '@/features/auth/use-failure';
import { copyText, reasonOf } from '@/features/data-requests/errors';

const text = messages.dataRequests;

/**
 * u4-data-requests (T6c; F3 steps 2 and 3; BR4.11, BR4.12; US5.6): the signature verification of an EMAIL Access or
 * Deletion linked to an active account. "Iniciar verificação" creates the unique message valid for 24 hours; the
 * administrator copies it to the contact, pastes the signature received and "Conferir" checks it with the wallet of the
 * account. A refused signature keeps the request waiting. The signature is never kept by the screen after the call.
 */
export function VerificationPanel({
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
  const [signature, setSignature] = useState('');
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [startKey] = useState(newKey);
  const [checkKey, setCheckKey] = useState(newKey);
  const verification = request.verification;

  async function start() {
    setBusy(true);
    setError(null);
    try {
      await api.startVerification(request.requestId, startKey);
      onChanged(await api.getDataRequest(request.requestId));
    } catch (failure) {
      if (!handled(failure)) {
        setError(reasonOf(failure));
      }
    } finally {
      setBusy(false);
    }
  }

  async function check() {
    if (signature.trim().length === 0) {
      setError(text.form.required);
      return;
    }
    setBusy(true);
    setError(null);
    setNote(null);
    try {
      const confirmed = await api.confirmVerification(
        request.requestId,
        signature.trim(),
        checkKey,
      );
      setSignature('');
      setNote(text.verification.confirmed);
      onChanged(confirmed);
    } catch (failure) {
      setCheckKey(newKey());
      if (!handled(failure)) {
        setError(reasonOf(failure));
      }
    } finally {
      setBusy(false);
    }
  }

  async function copy() {
    if (!verification) {
      return;
    }
    setNote((await copyText(verification.message)) ? text.verification.copied : text.copyFailed);
  }

  return (
    <section aria-labelledby="verification-title" data-testid="verification-panel">
      <h2 id="verification-title">{text.verification.title}</h2>
      <p>{text.verification.intro}</p>
      {!verification && request.status === 'RECEIVED' ? (
        <button
          type="button"
          disabled={busy}
          data-testid="verification-start"
          onClick={() => void start()}
        >
          {text.verification.start}
        </button>
      ) : null}
      {verification ? (
        <>
          <p data-testid="verification-state">
            {text.verification.states[verification.state] ?? verification.state} ·{' '}
            {text.verification.expiresAt(dateTime(verification.expiresAt, timeZone))}
          </p>
          <h3>{text.verification.message}</h3>
          <p className="pre-wrap" data-testid="verification-message">
            {verification.message}
          </p>
          <button
            type="button"
            className="secondary"
            data-testid="verification-copy"
            onClick={() => void copy()}
          >
            {text.verification.copy}
          </button>
          {verification.state === 'PENDING' && request.status === 'AWAITING_VERIFICATION' ? (
            <div>
              <label htmlFor="verification-signature">{text.verification.signature}</label>
              <textarea
                id="verification-signature"
                rows={3}
                value={signature}
                data-testid="verification-signature"
                onChange={(event) => setSignature(event.target.value)}
              />
              <button
                type="button"
                disabled={busy}
                data-testid="verification-check"
                onClick={() => void check()}
              >
                {busy ? text.verification.checking : text.verification.check}
              </button>
            </div>
          ) : null}
        </>
      ) : null}
      <div aria-live="polite" data-testid="verification-messages">
        {note ? <p data-testid="verification-note">{note}</p> : null}
        {error ? (
          <p className="field-error" data-testid="verification-error">
            {error}
          </p>
        ) : null}
      </div>
    </section>
  );
}
