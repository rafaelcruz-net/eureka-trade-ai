import type { DataRequestView } from '@/lib/api/guest-api';
import { formatInstant } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';
import { AccessSummaryView } from '@/features/data-requests/AccessSummaryView';

const text = messages.dataRequests;

/**
 * u4-data-requests (BR4.4, US5.2): one request of the person. The response, the reason and the summary appear only once
 * the request is DONE or REFUSED (the server sends null before); the texts keep their line breaks as text nodes.
 */
export function DataRequestDetail({
  request,
  timeZone,
  onClose,
}: {
  request: DataRequestView;
  timeZone?: string;
  onClose: () => void;
}) {
  const closed = request.status === 'DONE' || request.status === 'REFUSED';
  return (
    <section
      aria-labelledby="data-request-detail-title"
      className="card"
      data-testid="data-request-detail"
    >
      <h3 id="data-request-detail-title" tabIndex={-1}>
        {text.detailTitle(text.types[request.type] ?? request.type)}
      </h3>
      <dl>
        <dt>{text.columns.status}</dt>
        <dd data-testid="data-request-detail-status">
          {text.status[request.status] ?? request.status}
        </dd>
        <dt>{text.columns.receivedAt}</dt>
        <dd>{formatInstant(request.receivedAt, timeZone)}</dd>
        <dt>{text.columns.dueAt}</dt>
        <dd>{formatInstant(request.dueAt, timeZone)}</dd>
      </dl>
      {!closed && <p data-testid="data-request-detail-pending">{text.pending}</p>}
      {request.status === 'DONE' && (
        <>
          <h4>{text.response}</h4>
          <p className="pre-wrap" data-testid="data-request-detail-response">
            {request.response ?? text.noResponse}
          </p>
        </>
      )}
      {request.status === 'REFUSED' && (
        <>
          <h4>{text.refusalReason}</h4>
          <p className="pre-wrap" data-testid="data-request-detail-reason">
            {request.refusalReason}
          </p>
        </>
      )}
      {closed && request.accessSummary && (
        <AccessSummaryView summary={request.accessSummary} timeZone={timeZone} />
      )}
      <button
        type="button"
        className="secondary"
        data-testid="data-request-detail-close"
        onClick={onClose}
      >
        {text.close}
      </button>
    </section>
  );
}
