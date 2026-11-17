import { formatInstant } from '@/lib/format/format';
import type { Release } from '@/features/real/api';
import { realMessages as text } from '@/features/real/messages';

/** BR2.5: the text of a release that is not in force (NONE, QUARANTINE with its end, BLOCKED with its reason, REVOKED). */
export function releaseText(release: Release | null, timeZone?: string): string {
  if (!release || release.state === 'NONE') {
    return text.notReleased;
  }
  if (release.state === 'QUARANTINE' && release.effectiveAt) {
    return text.quarantineUntil(formatInstant(release.effectiveAt, timeZone));
  }
  if (release.state === 'BLOCKED') {
    const reason = release.blockedReason ?? '';
    return text.blocked(text.blockedReasons[reason] ?? reason);
  }
  if (release.state === 'REVOKED') {
    return text.revoked;
  }
  return text.notReleased;
}

/**
 * 261113-trading-real-bot U6 (BR2.5, S2, S3): the state of the release instead of the steps of the activation. Dates
 * in the time zone of the browser (BR8.6).
 */
export function ReleaseStatus({
  release,
  timeZone,
}: {
  release: Release | null;
  timeZone?: string;
}) {
  return (
    <div role="status" className="notice notice-warning" data-testid="real-release-status">
      <p>
        <span aria-hidden="true">⏳ </span>
        {releaseText(release, timeZone)}
      </p>
    </div>
  );
}
