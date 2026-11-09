import type { LegalVersionRef } from '@/features/legal/legal-api';
import { formatEffectiveDate, legalMessages } from '@/features/legal/messages';

/**
 * The history of the published versions (FR8.1, BR1.9, Could): newest first, each one a link to `?versao=`; the
 * current version is marked, and a legacy version reads as provisional.
 */
export function VersionHistory({
  versions,
  currentVersion,
  basePath,
}: {
  versions: LegalVersionRef[];
  currentVersion: string;
  basePath: string;
}) {
  if (versions.length === 0) {
    return null;
  }
  return (
    <section aria-labelledby="legal-history-heading" data-testid="legal-history">
      <h2 id="legal-history-heading">{legalMessages.historyHeading}</h2>
      <ul>
        {versions.map((ref) => (
          <li key={ref.version}>
            <a
              href={
                ref.version === currentVersion
                  ? basePath
                  : `${basePath}?versao=${encodeURIComponent(ref.version)}`
              }
              data-testid={`legal-history-link-${ref.version}`}
            >
              {ref.version}
            </a>{' '}
            {formatEffectiveDate(ref.effectiveAt)}
            {ref.version === currentVersion ? ` ${legalMessages.historyCurrent}` : ''}
            {ref.provisional ? ` ${legalMessages.historyProvisional}` : ''}
          </li>
        ))}
      </ul>
    </section>
  );
}
