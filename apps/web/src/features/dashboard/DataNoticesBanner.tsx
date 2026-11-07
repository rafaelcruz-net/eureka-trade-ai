'use client';

import { formatClock } from '@/lib/format/format';
import { useDashboard } from '@/features/dashboard/DashboardProvider';
import { sourceNotices, type SourceNotice } from '@/features/dashboard/dashboard-reducer';
import { dashboardMessages } from '@/features/dashboard/messages';

const text = dashboardMessages.notices;

/**
 * The band of data notices (BR4.1, BR4.2; AC5.4.2, AC5.4.3): one notice per late or failed source with its name and
 * the time of its last update, "Dados: OK HH:MM" when every source is up to date, and "Os valores podem estar
 * desatualizados" with "Tentar de novo" while the live channel is stale, waiting or reconnecting after a drop. Text
 * and a symbol, never color alone.
 */
export function DataNoticesBanner() {
  const { state, timeZone, retryChannel } = useDashboard();
  const clock = (iso: string) => formatClock(new Date(iso), timeZone);
  const notices = sourceNotices(state.sources.items, state.sources.now);
  const lastSuccess = state.channel.lastSuccessAt;

  return (
    <section
      aria-label={text.region}
      className="dashboard-notices"
      data-testid="dashboard-data-notices"
    >
      {state.channel.outdated && (
        <div className="notice notice-warning" data-testid="dashboard-stale-notice">
          <p>⚠ {lastSuccess ? text.outdated(clock(lastSuccess)) : text.outdatedNoTime}</p>
          <button
            type="button"
            className="secondary"
            data-testid="dashboard-stale-retry-button"
            onClick={retryChannel}
          >
            {dashboardMessages.retry}
          </button>
        </div>
      )}
      {state.sources.status === 'LOADING' && (
        <p className="muted" aria-busy="true" data-testid="dashboard-sources-loading">
          {text.sourcesLoading}
        </p>
      )}
      {state.sources.status === 'ERROR' && (
        <p className="notice notice-warning" data-testid="dashboard-sources-error">
          ⚠ {text.sourcesError}
        </p>
      )}
      {state.sources.status === 'READY' && notices.length > 0 && (
        <ul className="notice notice-warning" data-testid="dashboard-source-notices">
          {notices.map((notice) => (
            <li key={notice.name}>⚠ {noticeText(notice, clock)}</li>
          ))}
        </ul>
      )}
      {state.sources.status === 'READY' && notices.length === 0 && (
        <p data-testid="dashboard-sources-ok">
          ✓ {text.sourcesOk(newestUpdate(state.sources.items, clock))}
        </p>
      )}
    </section>
  );
}

function noticeText(notice: SourceNotice, clock: (iso: string) => string): string {
  switch (notice.kind) {
    case 'never':
      return text.sourceNever(notice.name);
    case 'failed':
      return notice.lastUpdatedAt
        ? text.sourceFailed(notice.name, clock(notice.lastUpdatedAt))
        : text.sourceNever(notice.name);
    case 'stale':
      return text.sourceStale(notice.name, clock(notice.lastUpdatedAt));
  }
}

function newestUpdate(
  items: Array<{ lastUpdatedAt?: string }>,
  clock: (iso: string) => string,
): string {
  const newest = items
    .map((item) => item.lastUpdatedAt)
    .filter((value): value is string => Boolean(value))
    .sort()
    .at(-1);
  return newest ? clock(newest) : '';
}
