'use client';

import { useEffect, useState } from 'react';
import { Dialog } from '@/components/Dialog';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { Invite, TenantOption } from '@/lib/api/admin-api';
import { messages } from '@/lib/i18n/messages';

const text = messages.invites;
const DEFAULT_HOURS = 7 * 24;
const MAX_HOURS = 30 * 24;

/**
 * Issuance of an invitation (F6; BR5.1; AC7.1.1): the tenant among the ACTIVE ones, the validity of 1 hour to 30 days
 * (default 7 days), an Idempotency-Key generated when the dialog opens. After a 201 the FULL code is shown once, with
 * "Copiar link"; afterwards only its suffix exists anywhere. Refusals of U3 (422, 409) are shown on the form.
 */
export function IssueInviteDialog({
  guestAppOrigin,
  onClose,
  onIssued,
  onFailure,
  now = () => Date.now(),
  returnFocusTo,
}: {
  guestAppOrigin: string;
  onClose: () => void;
  onIssued: (invite: Invite) => void;
  onFailure: (failure: unknown) => boolean;
  now?: () => number;
  returnFocusTo?: HTMLElement | null;
}) {
  const api = useApi();
  const [key] = useState(() => api.newKey());
  const [tenants, setTenants] = useState<TenantOption[] | null>(null);
  const [tenantId, setTenantId] = useState('');
  const [hours, setHours] = useState(String(DEFAULT_HOURS));
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [issued, setIssued] = useState<Invite | null>(null);
  const [copied, setCopied] = useState<string | null>(null);

  useEffect(() => {
    api
      .listTenants()
      .then(setTenants)
      .catch((failure: unknown) => {
        if (!onFailure(failure)) {
          setError(text.tenantsError);
        }
      });
  }, [api, onFailure]);

  async function submit() {
    const validHours = /^\d{1,3}$/.test(hours) ? parseInt(hours, 10) : -1;
    if (!tenantId) {
      setError(text.tenantRequired);
      return;
    }
    if (validHours < 1 || validHours > MAX_HOURS) {
      setError(text.validityInvalid);
      return;
    }
    setSending(true);
    setError(null);
    try {
      const expiresAt = new Date(now() + validHours * 3_600_000).toISOString();
      const invite = await api.issueInvite({ tenantId, expiresAt }, key);
      setIssued(invite);
      onIssued(invite);
    } catch (failure) {
      if (!onFailure(failure)) {
        setError(
          failure instanceof ApiError && failure.fields.length > 0
            ? fieldsOf(failure)
            : text.issueError,
        );
      }
    } finally {
      setSending(false);
    }
  }

  const link = issued?.code ? `${guestAppOrigin.replace(/\/+$/, '')}/convite/${issued.code}` : '';

  async function copy() {
    try {
      await navigator.clipboard.writeText(link);
      setCopied(text.copied);
    } catch {
      setCopied(text.copyFailed);
    }
  }

  return (
    <Dialog
      title={text.issueTitle}
      testId="issue-invite-dialog"
      onClose={onClose}
      returnFocusTo={returnFocusTo}
    >
      {issued ? (
        <>
          <p role="status" data-testid="issue-invite-issued">
            {text.issued}
          </p>
          <label htmlFor="issue-invite-link">{text.code}</label>
          <input id="issue-invite-link" readOnly value={link} data-testid="issue-invite-link" />
          <div className="dialog-actions">
            <button type="button" data-testid="issue-invite-copy" onClick={() => void copy()}>
              {text.copyLink}
            </button>
            <button
              type="button"
              className="secondary"
              data-testid="issue-invite-close"
              onClick={onClose}
            >
              {text.close}
            </button>
          </div>
          {copied ? <p data-testid="issue-invite-copied">{copied}</p> : null}
        </>
      ) : (
        <>
          <label htmlFor="issue-invite-tenant">{text.tenant}</label>
          <select
            id="issue-invite-tenant"
            value={tenantId}
            data-testid="issue-invite-tenant"
            onChange={(event) => setTenantId(event.target.value)}
          >
            <option value="">{text.tenantPlaceholder}</option>
            {(tenants ?? []).map((tenant) => (
              <option key={tenant.tenantId} value={tenant.tenantId}>
                {tenant.name}
              </option>
            ))}
          </select>
          <label htmlFor="issue-invite-hours">{text.validity}</label>
          <input
            id="issue-invite-hours"
            inputMode="numeric"
            value={hours}
            aria-describedby="issue-invite-hours-help"
            data-testid="issue-invite-hours"
            onChange={(event) => setHours(event.target.value)}
          />
          <p id="issue-invite-hours-help">{text.validityHelp}</p>
          {error ? (
            <p role="alert" className="field-error" data-testid="issue-invite-error">
              {error}
            </p>
          ) : null}
          <div className="dialog-actions">
            <button
              type="button"
              disabled={sending}
              data-testid="issue-invite-submit"
              onClick={() => void submit()}
            >
              {sending ? text.submitting : text.submit}
            </button>
            <button
              type="button"
              className="secondary"
              data-testid="issue-invite-cancel"
              onClick={onClose}
            >
              {text.cancel}
            </button>
          </div>
        </>
      )}
    </Dialog>
  );
}

function fieldsOf(failure: ApiError): string {
  return failure.fields.map((field) => `${field.field}: ${field.message}`).join(' ');
}
