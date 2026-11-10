'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { DataRequestType } from '@/lib/api/admin-api';
import { codePoints, count } from '@/lib/format';
import { messages } from '@/lib/i18n/messages';
import { useFailure } from '@/features/auth/use-failure';
import { reasonOf } from '@/features/data-requests/errors';

const text = messages.dataRequests;
const TYPES: DataRequestType[] = ['ACCESS', 'CORRECTION', 'DELETION', 'OTHER'];
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
const MAX_DETAILS = 1000;

type Field = 'type' | 'details' | 'contact' | 'receivedAt' | 'userId';

/** The instant of a `datetime-local` value, read in the time zone of the browser, as ISO-8601 UTC. */
export function toInstant(local: string): string | null {
  if (!local) {
    return null;
  }
  const parsed = new Date(local);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}

/**
 * u4-data-requests (T6b; F3 step 1; BR4.10; US5.6): the registration of a request received by the e-mail of the DPO -
 * type, text (up to 1.000 characters), the contact, when it arrived and the optional account. Each field has a visible
 * label; the fields the server refuses are marked with their own message (never echoing the value). One
 * Idempotency-Key per form, reused on every attempt; a 201 opens the request.
 */
export function RegisterEmailRequestForm({
  newKey = () => crypto.randomUUID(),
}: {
  newKey?: () => string;
}) {
  const api = useApi();
  const router = useRouter();
  const handled = useFailure();
  const [key] = useState(newKey);
  const [type, setType] = useState<DataRequestType>('ACCESS');
  const [details, setDetails] = useState('');
  const [contact, setContact] = useState('');
  const [receivedAt, setReceivedAt] = useState('');
  const [userId, setUserId] = useState('');
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function check(): Partial<Record<Field, string>> {
    const found: Partial<Record<Field, string>> = {};
    if (details.trim().length === 0) {
      found.details = text.form.required;
    } else if (codePoints(details) > MAX_DETAILS) {
      found.details = text.form.tooLong;
    }
    if (!EMAIL.test(contact.trim())) {
      found.contact = text.form.invalidEmail;
    }
    if (toInstant(receivedAt) === null) {
      found.receivedAt = text.form.required;
    }
    return found;
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const found = check();
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length > 0) {
      return;
    }
    setBusy(true);
    try {
      const registered = await api.registerEmailRequest(
        {
          type,
          details,
          contact: contact.trim(),
          receivedAt: toInstant(receivedAt) as string,
          userId: userId.trim().length > 0 ? userId.trim() : null,
        },
        key,
      );
      router.push(`/pedidos/${encodeURIComponent(registered.requestId)}`);
    } catch (refused) {
      if (handled(refused)) {
        return;
      }
      if (refused instanceof ApiError && refused.status === 400) {
        const marked: Partial<Record<Field, string>> = {};
        for (const field of refused.fields) {
          if (field.field in text.form.fieldErrors) {
            marked[field.field as Field] = text.form.fieldErrors[field.field];
          }
        }
        setErrors(marked);
      }
      setFailure(reasonOf(refused));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form noValidate onSubmit={(event) => void submit(event)} data-testid="register-form">
      <h1>{text.form.title}</h1>
      <label htmlFor="register-type">{text.form.type}</label>
      <select
        id="register-type"
        value={type}
        data-testid="register-type"
        onChange={(event) => setType(event.target.value as DataRequestType)}
      >
        {TYPES.map((option) => (
          <option key={option} value={option}>
            {text.types[option]}
          </option>
        ))}
      </select>
      <FieldMessage field="type" errors={errors} />
      <label htmlFor="register-details">{text.form.details}</label>
      <textarea
        id="register-details"
        rows={6}
        value={details}
        aria-describedby="register-details-counter"
        aria-invalid={errors.details ? true : undefined}
        data-testid="register-details"
        onChange={(event) => setDetails(event.target.value)}
      />
      <p id="register-details-counter" data-testid="register-counter">
        {text.form.counter(count(codePoints(details)))}
      </p>
      <FieldMessage field="details" errors={errors} />
      <label htmlFor="register-contact">{text.form.contact}</label>
      <input
        id="register-contact"
        type="email"
        value={contact}
        aria-invalid={errors.contact ? true : undefined}
        data-testid="register-contact"
        onChange={(event) => setContact(event.target.value)}
      />
      <FieldMessage field="contact" errors={errors} />
      <label htmlFor="register-received-at">{text.form.receivedAt}</label>
      <input
        id="register-received-at"
        type="datetime-local"
        value={receivedAt}
        aria-invalid={errors.receivedAt ? true : undefined}
        data-testid="register-received-at"
        onChange={(event) => setReceivedAt(event.target.value)}
      />
      <FieldMessage field="receivedAt" errors={errors} />
      <label htmlFor="register-user-id">{text.form.userId}</label>
      <p id="register-user-id-help">{text.form.userIdHelp}</p>
      <input
        id="register-user-id"
        type="text"
        value={userId}
        aria-describedby="register-user-id-help"
        aria-invalid={errors.userId ? true : undefined}
        data-testid="register-user-id"
        onChange={(event) => setUserId(event.target.value)}
      />
      <FieldMessage field="userId" errors={errors} />
      <button type="submit" disabled={busy} data-testid="register-submit">
        {busy ? text.form.submitting : text.form.submit}
      </button>
      <div aria-live="polite">
        {failure ? (
          <p className="field-error" data-testid="register-error">
            {failure}
          </p>
        ) : null}
      </div>
    </form>
  );
}

function FieldMessage({ field, errors }: { field: Field; errors: Partial<Record<Field, string>> }) {
  const message = errors[field];
  return message ? (
    <p className="field-error" data-testid={`register-${field}-error`}>
      {message}
    </p>
  ) : null;
}
