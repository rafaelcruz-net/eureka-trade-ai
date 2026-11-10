'use client';

import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { DataRequestView } from '@/lib/api/guest-api';
import { messages } from '@/lib/i18n/messages';
import { DataRequestDetail } from '@/features/data-requests/DataRequestDetail';
import { DataRequestForm } from '@/features/data-requests/DataRequestForm';
import { DataRequestList } from '@/features/data-requests/DataRequestList';

const text = messages.dataRequests;

type Load =
  | { kind: 'loading' }
  | { kind: 'error' }
  | { kind: 'ready'; requests: DataRequestView[] };

/**
 * u4-data-requests (T5; F1; US5.1, US5.2; NFR4.1, NFR4.2): the section "Meus dados (LGPD)" of the Account - the form,
 * the list of the person's requests (loading, error with "Tentar de novo", and empty states, each in text) and the
 * detail of the selected one. A 401 ends the session like the rest of the Account.
 */
export function DataRequestsSection({
  timeZone,
  onSessionEnded,
  newKey,
}: {
  timeZone?: string;
  onSessionEnded: () => void;
  newKey?: () => string;
}) {
  const api = useApi();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const fetchRequests = useCallback(async () => {
    setLoad({ kind: 'loading' });
    try {
      setLoad({ kind: 'ready', requests: await api.listDataRequests() });
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        onSessionEnded();
        return;
      }
      setLoad({ kind: 'error' });
    }
  }, [api, onSessionEnded]);

  useEffect(() => {
    void fetchRequests();
  }, [fetchRequests]);

  function created(request: DataRequestView) {
    setLoad((current) =>
      current.kind === 'ready'
        ? { kind: 'ready', requests: [request, ...current.requests] }
        : { kind: 'ready', requests: [request] },
    );
  }

  const selected =
    load.kind === 'ready'
      ? (load.requests.find((request) => request.requestId === selectedId) ?? null)
      : null;

  return (
    <section
      aria-labelledby="data-requests-title"
      className="data-requests"
      data-testid="data-requests-section"
    >
      <h2 id="data-requests-title">{text.title}</h2>
      <p>{text.intro}</p>
      <DataRequestForm
        timeZone={timeZone}
        onCreated={created}
        onSessionEnded={onSessionEnded}
        newKey={newKey}
      />
      <h3>{text.listTitle}</h3>
      {load.kind === 'loading' && (
        <p role="status" data-testid="data-requests-loading">
          {text.loading}
        </p>
      )}
      {load.kind === 'error' && (
        <div role="alert" className="notice notice-error" data-testid="data-requests-error">
          <p>
            <strong>{text.errorTitle}</strong>
          </p>
          <p>{text.errorBody}</p>
          <button
            type="button"
            className="secondary"
            data-testid="data-requests-retry"
            onClick={() => void fetchRequests()}
          >
            {text.retry}
          </button>
        </div>
      )}
      {load.kind === 'ready' && (
        <DataRequestList
          requests={load.requests}
          selectedId={selectedId}
          timeZone={timeZone}
          onOpen={(requestId) =>
            setSelectedId((current) => (current === requestId ? null : requestId))
          }
        />
      )}
      {selected && (
        <DataRequestDetail
          request={selected}
          timeZone={timeZone}
          onClose={() => setSelectedId(null)}
        />
      )}
    </section>
  );
}
