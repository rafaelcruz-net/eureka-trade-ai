'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type { AdminDataRequest, DataRequestStatus } from '@/lib/api/admin-api';
import { browserTimeZone, dateTime } from '@/lib/format';
import { messages } from '@/lib/i18n/messages';
import { AccountState } from '@/features/accounts/AccountState';
import { useFailure } from '@/features/auth/use-failure';
import { DeadlineAlert } from '@/features/data-requests/DeadlineAlert';

const text = messages.dataRequests;
const STATUSES: DataRequestStatus[] = [
  'RECEIVED',
  'AWAITING_VERIFICATION',
  'IN_PROGRESS',
  'DONE',
  'REFUSED',
];

/**
 * u4-data-requests (T6; F2 step 1; BR4.16; US5.3, US5.9; NFR3.2, NFR4.2): the requests by due date, with a visible
 * filter of the situation, a page by cursor ("Carregar mais"), the loading, error ("Tentar de novo") and empty states in
 * text, and the due-date alert with icon and text.
 */
export function AdminDataRequestList({
  timeZone = browserTimeZone(),
  now = () => Date.now(),
}: {
  timeZone?: string;
  now?: () => number;
}) {
  const api = useApi();
  const failure = useFailure();
  // The handler changes with the session; the list loads again only when the filter changes.
  const handledRef = useRef(failure);
  useEffect(() => {
    handledRef.current = failure;
  }, [failure]);
  const [status, setStatus] = useState<DataRequestStatus | ''>('');
  const [items, setItems] = useState<AdminDataRequest[] | null>(null);
  const [next, setNext] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  const load = useCallback(
    async (cursor: string | null) => {
      setFailed(false);
      if (cursor === null) {
        setItems(null);
      }
      try {
        const page = await api.listDataRequests(status === '' ? null : status, cursor);
        setItems((current) => (cursor && current ? [...current, ...page.items] : page.items));
        setNext(page.nextCursor ?? null);
      } catch (refused) {
        if (!handledRef.current(refused)) {
          setFailed(true);
        }
      }
    },
    [api, status],
  );

  useEffect(() => {
    void load(null);
  }, [load]);

  const moment = now();

  return (
    <section aria-labelledby="data-requests-title" data-testid="data-requests-page">
      <h1 id="data-requests-title">{text.title}</h1>
      <p>
        <Link href="/pedidos/novo" data-testid="data-requests-register">
          {text.register}
        </Link>
      </p>
      <label htmlFor="data-requests-filter">{text.filterLabel}</label>
      <select
        id="data-requests-filter"
        value={status}
        data-testid="data-requests-filter"
        onChange={(event) => setStatus(event.target.value as DataRequestStatus | '')}
      >
        <option value="">{text.filterAll}</option>
        {STATUSES.map((option) => (
          <option key={option} value={option}>
            {text.status[option]}
          </option>
        ))}
      </select>
      {failed ? (
        <div role="alert" data-testid="data-requests-error">
          <p className="field-error">{text.error}</p>
          <button
            type="button"
            className="secondary"
            data-testid="data-requests-retry"
            onClick={() => void load(null)}
          >
            {text.retry}
          </button>
        </div>
      ) : null}
      {items === null && !failed ? (
        <p role="status" data-testid="data-requests-loading">
          {text.loading}
        </p>
      ) : null}
      {items !== null && items.length === 0 ? (
        <p data-testid="data-requests-empty">{text.empty}</p>
      ) : null}
      {items !== null && items.length > 0 ? (
        <table data-testid="data-requests-table">
          <thead>
            <tr>
              <th scope="col">{text.columns.type}</th>
              <th scope="col">{text.columns.origin}</th>
              <th scope="col">{text.columns.account}</th>
              <th scope="col">{text.columns.receivedAt}</th>
              <th scope="col">{text.columns.dueAt}</th>
              <th scope="col">{text.columns.status}</th>
              <th scope="col">{text.columns.alert}</th>
              <th scope="col">
                <span className="visually-hidden">{text.open}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr key={item.requestId} data-testid="data-requests-row">
                <td>{text.types[item.type] ?? item.type}</td>
                <td>{text.origins[item.origin] ?? item.origin}</td>
                <td>
                  <AccountState
                    userId={item.userId}
                    anonymized={item.accountAnonymized}
                    testId={`data-request-account-${item.requestId}`}
                  />
                </td>
                <td>{dateTime(item.receivedAt, timeZone)}</td>
                <td>{dateTime(item.dueAt, timeZone)}</td>
                <td data-testid="data-requests-row-status">
                  {text.status[item.status] ?? item.status}
                </td>
                <td>
                  <DeadlineAlert request={item} now={moment} />
                </td>
                <td>
                  <Link
                    href={`/pedidos/${encodeURIComponent(item.requestId)}`}
                    data-testid="data-requests-row-open"
                  >
                    {text.open}
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {next ? (
        <button
          type="button"
          className="secondary"
          data-testid="data-requests-more"
          onClick={() => void load(next)}
        >
          {text.more}
        </button>
      ) : null}
    </section>
  );
}
