import type { AccessSummary } from '@/lib/api/admin-api';
import { count, dateTime } from '@/lib/format';
import { messages } from '@/lib/i18n/messages';

const text = messages.dataRequests;

/**
 * u4-data-requests (BR4.8): the Access summary of the person of the request (preview or released snapshot); only data
 * of that person, the wallet abbreviated by the server; every value is a React text node.
 */
export function AccessSummaryView({
  summary,
  title,
  timeZone,
  testId,
}: {
  summary: AccessSummary;
  title: string;
  timeZone: string;
  testId: string;
}) {
  return (
    <section aria-label={title} data-testid={testId}>
      <h2>{title}</h2>
      <dl>
        <dt>{text.summary.wallet}</dt>
        <dd data-testid={`${testId}-wallet`}>{summary.walletMasked}</dd>
        <dt>{text.summary.provider}</dt>
        <dd>{summary.provider}</dd>
        <dt>{text.summary.accountCreatedAt}</dt>
        <dd>{dateTime(summary.accountCreatedAt, timeZone)}</dd>
        <dt>{text.summary.lastLoginAt}</dt>
        <dd>
          {summary.lastLoginAt
            ? dateTime(summary.lastLoginAt, timeZone)
            : text.summary.neverLoggedIn}
        </dd>
        <dt>{text.summary.acceptedVersions}</dt>
        <dd>
          {summary.acceptedVersions.length === 0 ? (
            text.summary.noVersions
          ) : (
            <ul>
              {summary.acceptedVersions.map((accepted) => (
                <li key={`${accepted.version}-${accepted.acceptedAt}`}>
                  {text.summary.acceptedVersion(
                    accepted.version,
                    dateTime(accepted.acceptedAt, timeZone),
                  )}
                </li>
              ))}
            </ul>
          )}
        </dd>
        <dt>{text.summary.bots}</dt>
        <dd data-testid={`${testId}-bots`}>{count(summary.botCount)}</dd>
        <dt>{text.summary.paperOrders}</dt>
        <dd data-testid={`${testId}-orders`}>{count(summary.paperOrderCount)}</dd>
        <dt>{text.summary.feedback}</dt>
        <dd data-testid={`${testId}-feedback`}>{count(summary.feedbackCount)}</dd>
        <dt>{text.summary.requests}</dt>
        <dd>
          <ul>
            {summary.requests.map((line, index) => (
              <li key={`${line.receivedAt}-${index}`}>
                {text.types[line.type] ?? line.type} · {text.status[line.status] ?? line.status} ·{' '}
                {dateTime(line.receivedAt, timeZone)}
              </li>
            ))}
          </ul>
        </dd>
      </dl>
    </section>
  );
}
