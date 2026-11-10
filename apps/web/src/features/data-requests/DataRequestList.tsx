import type { DataRequestView } from '@/lib/api/guest-api';
import { formatInstant } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';

const text = messages.dataRequests;

/**
 * u4-data-requests (US5.2): the requests of the person with type, reception, situation and due date, newest first;
 * each row opens its detail. The situation is always text, never only a color.
 */
export function DataRequestList({
  requests,
  selectedId,
  timeZone,
  onOpen,
}: {
  requests: DataRequestView[];
  selectedId: string | null;
  timeZone?: string;
  onOpen: (requestId: string) => void;
}) {
  if (requests.length === 0) {
    return <p data-testid="data-requests-empty">{text.none}</p>;
  }
  return (
    <table data-testid="data-requests-list">
      <caption className="visually-hidden">{text.listTitle}</caption>
      <thead>
        <tr>
          <th scope="col">{text.columns.type}</th>
          <th scope="col">{text.columns.receivedAt}</th>
          <th scope="col">{text.columns.status}</th>
          <th scope="col">{text.columns.dueAt}</th>
          <th scope="col">
            <span className="visually-hidden">{text.open}</span>
          </th>
        </tr>
      </thead>
      <tbody>
        {requests.map((request) => (
          <tr key={request.requestId} data-testid="data-requests-row">
            <td>{text.types[request.type] ?? request.type}</td>
            <td>{formatInstant(request.receivedAt, timeZone)}</td>
            <td data-testid="data-requests-row-status">
              {text.status[request.status] ?? request.status}
            </td>
            <td>{formatInstant(request.dueAt, timeZone)}</td>
            <td>
              <button
                type="button"
                className="secondary"
                aria-expanded={selectedId === request.requestId}
                data-testid="data-requests-row-open"
                onClick={() => onOpen(request.requestId)}
              >
                {text.open}
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
