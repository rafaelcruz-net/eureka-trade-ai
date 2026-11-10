import type { TermsStatus } from '@/lib/api/guest-api';
import { formatInstant } from '@/lib/format/format';
import { reacceptanceMessages } from '@/features/reacceptance/messages';
import { reacceptanceHref } from '@/features/reacceptance/return-path';

const text = reacceptanceMessages.status;

/**
 * u3-reacceptance (BR3.10, F5, L2): the situation of the person before the effective current terms on the Account.
 * Up to date: the accepted version and its date. Not up to date (or no acceptance at all, `terms` absent): the text
 * that says so and the link "Aceitar a nova versão" to `/novo-aceite`, back to the Account afterwards. The state is
 * always in text, never only in a color.
 */
export function TermsStatusPanel({
  terms,
  timeZone,
}: {
  terms: TermsStatus | undefined;
  timeZone?: string;
}) {
  const upToDate = terms?.upToDate === true;
  return (
    <section aria-labelledby="terms-status-heading" data-testid="terms-status-panel">
      <h2 id="terms-status-heading">{text.heading}</h2>
      {terms && (
        <p data-testid="terms-status-accepted">
          {text.accepted(terms.acceptedVersion, formatInstant(terms.acceptedAt, timeZone))}
        </p>
      )}
      {upToDate ? (
        <p data-testid="terms-status-up-to-date">{text.upToDate}</p>
      ) : (
        <>
          <p className="notice notice-warning" data-testid="terms-status-outdated">
            {terms ? text.outdated(terms.currentVersion) : text.none}
          </p>
          <a href={reacceptanceHref('/conta')} data-testid="terms-status-accept-link">
            {text.acceptNew}
          </a>
        </>
      )}
    </section>
  );
}
