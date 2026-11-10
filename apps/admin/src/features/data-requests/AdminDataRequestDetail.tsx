'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { AdminDataRequest, DataRequestTransition } from '@/lib/api/admin-api';
import { browserTimeZone, dateTime } from '@/lib/format';
import { messages } from '@/lib/i18n/messages';
import { useFailure } from '@/features/auth/use-failure';
import { AccessSummaryView } from '@/features/data-requests/AccessSummaryView';
import { DeadlineAlert } from '@/features/data-requests/DeadlineAlert';
import { ReplyToContact } from '@/features/data-requests/ReplyToContact';
import { VerificationPanel } from '@/features/data-requests/VerificationPanel';
import { reasonOf } from '@/features/data-requests/errors';
import { AnonymizeAccountButton } from '@/features/data-requests/AnonymizeAccountButton';
import { AccountState } from '@/features/accounts/AccountState';

const text = messages.dataRequests;

type Load =
  | { kind: 'loading' }
  | { kind: 'error'; notFound: boolean }
  | { kind: 'ready'; request: AdminDataRequest };

/** BR4.11: an EMAIL Access or Deletion linked to an account goes through the signature verification. */
export function needsVerification(request: AdminDataRequest): boolean {
  return (
    request.origin === 'EMAIL' &&
    (request.type === 'ACCESS' || request.type === 'DELETION') &&
    request.userId !== null
  );
}

/**
 * u4-data-requests (T6a; F2 steps 2 to 4; BR4.5, BR4.8, BR4.9, BR4.17; US5.3 to US5.6): one request with its text as
 * plain text, the editable response (prefilled with the model for a Correction), the reason of a refusal with the model
 * texts, the actions allowed in its situation, the preview of the Access summary with "Liberar resumo", the
 * verification and, for a closed EMAIL request, the reply to the contact. Every message is announced in an `aria-live`
 * region; a refused action keeps the typed texts.
 */
export function AdminDataRequestDetail({
  requestId,
  timeZone = browserTimeZone(),
  now = () => Date.now(),
  newKey = () => crypto.randomUUID(),
}: {
  requestId: string;
  timeZone?: string;
  now?: () => number;
  newKey?: () => string;
}) {
  const api = useApi();
  const handled = useFailure();
  const heading = useRef<HTMLHeadingElement>(null);
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [response, setResponse] = useState('');
  const [reason, setReason] = useState('');
  const [release, setRelease] = useState(false);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const show = useCallback((request: AdminDataRequest) => {
    setLoad({ kind: 'ready', request });
    setResponse((current) =>
      current.length > 0
        ? current
        : (request.response ?? (request.type === 'CORRECTION' ? text.templateText.CORRECTION : '')),
    );
  }, []);

  const fetchRequest = useCallback(async () => {
    setLoad({ kind: 'loading' });
    try {
      show(await api.getDataRequest(requestId));
    } catch (failure) {
      if (!handled(failure)) {
        setLoad({
          kind: 'error',
          notFound: failure instanceof ApiError && failure.status === 404,
        });
      }
    }
  }, [api, handled, requestId, show]);

  useEffect(() => {
    void fetchRequest();
  }, [fetchRequest]);

  useEffect(() => {
    if (load.kind === 'ready') {
      heading.current?.focus();
    }
  }, [load.kind]);

  async function transition(to: DataRequestTransition['to']) {
    setBusy(true);
    setError(null);
    setNote(null);
    try {
      const changed = await api.transitionDataRequest(
        requestId,
        {
          to,
          response: to === 'DONE' && response.trim().length > 0 ? response : null,
          refusalReason: to === 'REFUSED' ? reason : null,
          releaseAccessSummary: to === 'DONE' && release,
        },
        newKey(),
      );
      show(changed);
      setNote(text.transitioned(text.status[changed.status] ?? changed.status));
    } catch (failure) {
      if (!handled(failure)) {
        setError(reasonOf(failure));
      }
    } finally {
      setBusy(false);
    }
  }

  if (load.kind === 'loading') {
    return (
      <p role="status" data-testid="data-request-loading">
        {text.detailLoading}
      </p>
    );
  }
  if (load.kind === 'error') {
    return (
      <div role="alert" data-testid="data-request-error">
        <p className="field-error">{load.notFound ? text.notFound : text.detailError}</p>
        <button
          type="button"
          className="secondary"
          data-testid="data-request-retry"
          onClick={() => void fetchRequest()}
        >
          {text.retry}
        </button>
        <p>
          <Link href="/pedidos">{text.back}</Link>
        </p>
      </div>
    );
  }

  const request = load.request;
  const open =
    request.status === 'RECEIVED' ||
    request.status === 'AWAITING_VERIFICATION' ||
    request.status === 'IN_PROGRESS';
  const canStart = request.status === 'RECEIVED' && !needsVerification(request);
  const canFinish = request.status === 'IN_PROGRESS';
  const canRefuse = request.status === 'IN_PROGRESS' || request.status === 'AWAITING_VERIFICATION';

  return (
    <section aria-labelledby="data-request-title" data-testid="data-request-detail">
      <p>
        <Link href="/pedidos" data-testid="data-request-back">
          {text.back}
        </Link>
      </p>
      <h1 id="data-request-title" ref={heading} tabIndex={-1}>
        {text.detailTitle(text.types[request.type] ?? request.type)}
      </h1>
      <dl>
        <dt>{text.columns.status}</dt>
        <dd data-testid="data-request-status">
          {text.status[request.status] ?? request.status}{' '}
          <DeadlineAlert request={request} now={now()} />
        </dd>
        <dt>{text.columns.origin}</dt>
        <dd>{text.origins[request.origin] ?? request.origin}</dd>
        <dt>{text.columns.receivedAt}</dt>
        <dd>{dateTime(request.receivedAt, timeZone)}</dd>
        <dt>{text.columns.dueAt}</dt>
        <dd>{dateTime(request.dueAt, timeZone)}</dd>
        <dt>{text.account}</dt>
        <dd data-testid="data-request-account">
          <AccountState userId={request.userId} anonymized={request.accountAnonymized} />
        </dd>
        {request.contact ? (
          <>
            <dt>{text.contact}</dt>
            <dd data-testid="data-request-contact">{request.contact}</dd>
          </>
        ) : null}
        {request.identityVerified ? (
          <>
            <dt className="visually-hidden">{text.identityVerified}</dt>
            <dd data-testid="data-request-verified">{text.identityVerified}</dd>
          </>
        ) : null}
      </dl>
      <h2>{text.details}</h2>
      <p className="pre-wrap" data-testid="data-request-details">
        {request.details}
      </p>
      {needsVerification(request) ? (
        <VerificationPanel request={request} timeZone={timeZone} onChanged={show} newKey={newKey} />
      ) : null}
      {request.accessSummaryPreview ? (
        <AccessSummaryView
          summary={request.accessSummaryPreview}
          title={text.preview}
          timeZone={timeZone}
          testId="data-request-preview"
        />
      ) : null}
      {request.accessSummary ? (
        <AccessSummaryView
          summary={request.accessSummary}
          title={text.released}
          timeZone={timeZone}
          testId="data-request-released"
        />
      ) : null}
      {open ? (
        <div data-testid="data-request-actions">
          <h2>{text.templates}</h2>
          <button
            type="button"
            className="secondary"
            data-testid="data-request-template-correction"
            onClick={() => setResponse(text.templateText.CORRECTION)}
          >
            {text.templateCorrection}
          </button>{' '}
          <button
            type="button"
            className="secondary"
            data-testid="data-request-template-identity"
            onClick={() => setReason(text.templateText.IDENTITY)}
          >
            {text.templateIdentity}
          </button>{' '}
          <button
            type="button"
            className="secondary"
            data-testid="data-request-template-wallet"
            onClick={() => setReason(text.templateText.WALLET)}
          >
            {text.templateWallet}
          </button>
          <label htmlFor="data-request-response">{text.response}</label>
          <p id="data-request-response-help">{text.responseHelp}</p>
          <textarea
            id="data-request-response"
            rows={6}
            value={response}
            aria-describedby="data-request-response-help"
            data-testid="data-request-response"
            onChange={(event) => setResponse(event.target.value)}
          />
          <label htmlFor="data-request-reason">{text.refusalReason}</label>
          <p id="data-request-reason-help">{text.refusalHelp}</p>
          <textarea
            id="data-request-reason"
            rows={3}
            value={reason}
            aria-describedby="data-request-reason-help"
            data-testid="data-request-reason"
            onChange={(event) => setReason(event.target.value)}
          />
          {request.type === 'ACCESS' && canFinish ? (
            <p>
              <input
                id="data-request-release"
                type="checkbox"
                checked={release}
                data-testid="data-request-release"
                onChange={(event) => setRelease(event.target.checked)}
              />{' '}
              <label htmlFor="data-request-release">{text.releaseSummary}</label>
            </p>
          ) : null}
          <div className="dialog-actions">
            <AnonymizeAccountButton
              request={request}
              newKey={newKey}
              onAnonymized={(result) => {
                setError(null);
                setNote(
                  result.alreadyAnonymized ? text.anonymization.already : text.anonymization.done,
                );
                void fetchRequest();
              }}
            />
            {canStart ? (
              <button
                type="button"
                disabled={busy}
                data-testid="data-request-start"
                onClick={() => void transition('IN_PROGRESS')}
              >
                {busy ? text.saving : text.start}
              </button>
            ) : null}
            {canFinish ? (
              <button
                type="button"
                disabled={busy}
                data-testid="data-request-done"
                onClick={() => void transition('DONE')}
              >
                {busy ? text.saving : text.done}
              </button>
            ) : null}
            {canRefuse ? (
              <button
                type="button"
                className="secondary"
                disabled={busy}
                data-testid="data-request-refuse"
                onClick={() => void transition('REFUSED')}
              >
                {text.refuse}
              </button>
            ) : null}
          </div>
        </div>
      ) : (
        <>
          {request.response ? (
            <>
              <h2>{text.response}</h2>
              <p className="pre-wrap" data-testid="data-request-final-response">
                {request.response}
              </p>
            </>
          ) : null}
          {request.refusalReason ? (
            <>
              <h2>{text.refusalReason}</h2>
              <p className="pre-wrap" data-testid="data-request-final-reason">
                {request.refusalReason}
              </p>
            </>
          ) : null}
        </>
      )}
      {request.origin === 'EMAIL' && !open ? (
        <ReplyToContact request={request} timeZone={timeZone} onChanged={show} newKey={newKey} />
      ) : null}
      <div aria-live="polite" data-testid="data-request-messages">
        {note ? <p data-testid="data-request-note">{note}</p> : null}
        {error ? (
          <p className="field-error" data-testid="data-request-action-error">
            {error}
          </p>
        ) : null}
      </div>
    </section>
  );
}
