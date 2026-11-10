'use client';

import { useState } from 'react';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { DataRequestType, DataRequestView } from '@/lib/api/guest-api';
import { formatInstant } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';

const text = messages.dataRequests;
const COUNTER = new Intl.NumberFormat('pt-BR');
const TYPES: DataRequestType[] = ['ACCESS', 'CORRECTION', 'DELETION', 'OTHER'];

/** BR4.1: the text of a request, counted in code points like the server does. */
export const MAX_DETAILS = 1000;

export function codePoints(value: string): number {
  return Array.from(value).length;
}

type Outcome =
  | { kind: 'idle' }
  | { kind: 'created'; message: string }
  | { kind: 'error'; message: string };

/**
 * u4-data-requests (T5 form; BR4.1, BR4.2; US5.1): type with visible labels, text with a counter up to 1.000 characters
 * and the submission with ONE Idempotency-Key per submission, reused on every new attempt and renewed after a 201. The
 * text stays in the field until a 201 (409, 429, 400 or a failure show the reason and keep it). Messages are announced
 * in an `aria-live` region.
 */
export function DataRequestForm({
  timeZone,
  onCreated,
  onSessionEnded,
  newKey = () => crypto.randomUUID(),
}: {
  timeZone?: string;
  onCreated: (request: DataRequestView) => void;
  onSessionEnded: () => void;
  newKey?: () => string;
}) {
  const api = useApi();
  const [type, setType] = useState<DataRequestType>('ACCESS');
  const [details, setDetails] = useState('');
  const [key, setKey] = useState(newKey);
  const [submitting, setSubmitting] = useState(false);
  const [outcome, setOutcome] = useState<Outcome>({ kind: 'idle' });
  const [invalidField, setInvalidField] = useState<string | null>(null);

  const used = codePoints(details);
  const tooLong = used > MAX_DETAILS;

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (details.trim().length === 0) {
      setInvalidField('details');
      setOutcome({ kind: 'error', message: text.empty });
      return;
    }
    if (tooLong) {
      setInvalidField('details');
      setOutcome({ kind: 'error', message: text.tooLong });
      return;
    }
    setSubmitting(true);
    setInvalidField(null);
    setOutcome({ kind: 'idle' });
    try {
      const created = await api.createDataRequest({ type, details }, key);
      setDetails('');
      setKey(newKey());
      setOutcome({
        kind: 'created',
        message: text.created(formatInstant(created.dueAt, timeZone)),
      });
      onCreated(created);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        onSessionEnded();
        return;
      }
      if (failure instanceof ApiError && failure.status === 400) {
        setInvalidField(failure.fields[0]?.field ?? 'details');
      }
      setOutcome({ kind: 'error', message: reasonOf(failure) });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form noValidate onSubmit={(event) => void submit(event)} data-testid="data-request-form">
      <h3>{text.formTitle}</h3>
      <label htmlFor="data-request-type">{text.typeLabel}</label>
      <select
        id="data-request-type"
        value={type}
        aria-invalid={invalidField === 'type' || undefined}
        data-testid="data-request-type"
        onChange={(event) => setType(event.target.value as DataRequestType)}
      >
        {TYPES.map((option) => (
          <option key={option} value={option}>
            {text.types[option]}
          </option>
        ))}
      </select>
      <label htmlFor="data-request-details">{text.detailsLabel}</label>
      <p id="data-request-details-help">{text.detailsHelp}</p>
      <textarea
        id="data-request-details"
        rows={5}
        value={details}
        aria-describedby="data-request-details-help data-request-counter"
        aria-invalid={invalidField === 'details' || tooLong || undefined}
        data-testid="data-request-details"
        onChange={(event) => setDetails(event.target.value)}
      />
      <p
        id="data-request-counter"
        data-testid="data-request-counter"
        className={tooLong ? 'field-error' : undefined}
      >
        {text.counter(COUNTER.format(used))}
      </p>
      <button type="submit" disabled={submitting} data-testid="data-request-submit">
        {submitting ? text.submitting : text.submit}
      </button>
      <div aria-live="polite" data-testid="data-request-messages">
        {outcome.kind === 'created' && <p data-testid="data-request-created">{outcome.message}</p>}
        {outcome.kind === 'error' && (
          <p className="field-error" data-testid="data-request-error">
            {outcome.message}
          </p>
        )}
      </div>
    </form>
  );
}

function reasonOf(failure: unknown): string {
  if (!(failure instanceof ApiError)) {
    return text.failed;
  }
  if (failure.status === 409 && failure.code === 'DATA_REQUEST_ALREADY_OPEN') {
    return text.alreadyOpen;
  }
  if (failure.status === 429) {
    return failure.retryAfterSeconds
      ? text.rateLimited(Math.max(1, Math.ceil(failure.retryAfterSeconds / 60)))
      : text.rateLimitedNoWait;
  }
  if (failure.status === 400) {
    return text.invalid;
  }
  return text.failed;
}
