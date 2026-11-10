import type { AccessSummary } from '@/lib/api/guest-api';
import { formatInstant } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';

const text = messages.dataRequests;
const COUNT = new Intl.NumberFormat('pt-BR');

/**
 * u4-data-requests (BR4.8, US5.4): the Access summary released with a DONE ACCESS request. Only data of the person,
 * the wallet abbreviated by the server; every value is a React text node (never HTML).
 */
export function AccessSummaryView({
  summary,
  timeZone,
}: {
  summary: AccessSummary;
  timeZone?: string;
}) {
  return (
    <section aria-labelledby="access-summary-title" data-testid="access-summary">
      <h3 id="access-summary-title">{text.summary.title}</h3>
      <dl>
        <dt>{text.summary.wallet}</dt>
        <dd data-testid="access-summary-wallet">{summary.walletMasked}</dd>
        <dt>{text.summary.provider}</dt>
        <dd data-testid="access-summary-provider">
          {messages.account.providers[summary.provider] ?? summary.provider}
        </dd>
        <dt>{text.summary.accountCreatedAt}</dt>
        <dd data-testid="access-summary-created">
          {formatInstant(summary.accountCreatedAt, timeZone)}
        </dd>
        <dt>{text.summary.lastLoginAt}</dt>
        <dd data-testid="access-summary-last-login">
          {summary.lastLoginAt
            ? formatInstant(summary.lastLoginAt, timeZone)
            : text.summary.neverLoggedIn}
        </dd>
        <dt>{text.summary.acceptedVersions}</dt>
        <dd>
          {summary.acceptedVersions.length === 0 ? (
            <span data-testid="access-summary-no-versions">{text.summary.noVersions}</span>
          ) : (
            <ul data-testid="access-summary-versions">
              {summary.acceptedVersions.map((accepted) => (
                <li key={`${accepted.version}-${accepted.acceptedAt}`}>
                  {text.summary.acceptedVersion(
                    accepted.version,
                    formatInstant(accepted.acceptedAt, timeZone),
                  )}
                </li>
              ))}
            </ul>
          )}
        </dd>
        <dt>{text.summary.bots}</dt>
        <dd data-testid="access-summary-bots">{COUNT.format(summary.botCount)}</dd>
        <dt>{text.summary.paperOrders}</dt>
        <dd data-testid="access-summary-orders">{COUNT.format(summary.paperOrderCount)}</dd>
        <dt>{text.summary.feedback}</dt>
        <dd data-testid="access-summary-feedback">{COUNT.format(summary.feedbackCount)}</dd>
        <dt>{text.summary.requests}</dt>
        <dd>
          <ul data-testid="access-summary-requests">
            {summary.requests.map((line, index) => (
              <li key={`${line.receivedAt}-${index}`}>
                {text.summary.request(
                  text.types[line.type] ?? line.type,
                  text.status[line.status] ?? line.status,
                  formatInstant(line.receivedAt, timeZone),
                )}
              </li>
            ))}
          </ul>
        </dd>
      </dl>
    </section>
  );
}
