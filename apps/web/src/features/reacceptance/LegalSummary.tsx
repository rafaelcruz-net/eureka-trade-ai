import type { CurrentLegalDocuments } from '@/lib/api/guest-api';
import { reacceptanceMessages } from '@/features/reacceptance/messages';

const text = reacceptanceMessages.summary;

/** The summary items as plain text: the documents are never rendered as HTML. */
function SummaryList({ items, testId }: { items: string[] | null; testId: string }) {
  if (!items || items.length === 0) {
    return (
      <p className="muted" data-testid={testId}>
        {text.unavailable}
      </p>
    );
  }
  return (
    <ul data-testid={testId}>
      {items.map((item, index) => (
        <li key={index}>{item}</li>
      ))}
    </ul>
  );
}

/**
 * u3-reacceptance (BR3.7, BR3.8; US2.3, US4.1): the summary of the effective current terms and privacy policy (L1)
 * with the links to the full pages. The links open in ANOTHER tab (`target="_blank"`, `rel="noopener noreferrer"`), so
 * the marks of the boxes stay where they are. Headings are h2 under the h1 of the screen.
 */
export function LegalSummary({
  documents,
  idPrefix,
}: {
  documents: CurrentLegalDocuments;
  idPrefix: string;
}) {
  return (
    <section aria-label={text.label} data-testid={`${idPrefix}-summary`}>
      <h2 id={`${idPrefix}-terms-summary-heading`}>{text.termsHeading}</h2>
      <SummaryList items={documents.terms.summary ?? null} testId={`${idPrefix}-terms-summary`} />
      <h2 id={`${idPrefix}-privacy-summary-heading`}>{text.privacyHeading}</h2>
      {documents.privacy ? (
        <SummaryList
          items={documents.privacy.summary ?? null}
          testId={`${idPrefix}-privacy-summary`}
        />
      ) : (
        <p className="muted" data-testid={`${idPrefix}-privacy-summary`}>
          {text.privacyMissing}
        </p>
      )}
      <nav aria-label={text.linksLabel}>
        <ul>
          <li>
            <a
              href="/termos"
              target="_blank"
              rel="noopener noreferrer"
              data-testid={`${idPrefix}-terms-link`}
            >
              {text.readTerms}
            </a>
          </li>
          <li>
            <a
              href="/privacidade"
              target="_blank"
              rel="noopener noreferrer"
              data-testid={`${idPrefix}-privacy-link`}
            >
              {text.readPrivacy}
            </a>
          </li>
        </ul>
      </nav>
    </section>
  );
}
