'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { ScannerFilters, ScannerPage, ScannerRow, SourceStatus } from '@/lib/api/guest-api';
import { formatClock, formatInstant } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';
import { AppShell } from '@/components/shell/AppShell';
import { useSession } from '@/features/auth/SessionProvider';
import { useRequireSession } from '@/features/auth/useRequireSession';
import { AssessmentPanel } from '@/features/scanner/AssessmentPanel';
import {
  fromSearchParams,
  hasActiveFilters,
  toSearchParams,
  type FilterState,
} from '@/features/scanner/filters';
import { ScannerFiltersForm } from '@/features/scanner/ScannerFiltersForm';
import { rowKey, ScannerTable } from '@/features/scanner/ScannerTable';

export const PAGE_SIZE = 50;
const text = messages.scanner;

type Load =
  | { kind: 'loading' }
  | { kind: 'error' }
  | { kind: 'invalid' }
  | { kind: 'allFailed'; sources: SourceStatus[] }
  | { kind: 'ready'; page: ScannerPage };

type FieldErrors = Partial<Record<keyof ScannerFilters, string>>;

/**
 * S3 Scanner (US2.2, US2.3, US2.4, US3.1, US3.2). Filters live in the address; loading only in the list area; error,
 * partial data, every source failed, waiting for the first collection, empty with filters and empty without them are
 * distinct states, always with text besides color. Values refresh every 15 s on the same page without moving the
 * focus or the selection; new tokens never jump into the list: they wait for "Atualizar lista" (BR2.6), announced
 * politely with the update time (CT-UX-8). A row opens the assessment panel next to the list (BR5.3).
 */
export function ScannerScreen({
  refreshMs = 15_000,
  timeZone,
}: {
  refreshMs?: number;
  timeZone?: string;
}) {
  const status = useRequireSession('/scanner');
  const api = useApi();
  const router = useRouter();
  const searchParams = useSearchParams();
  const { expire } = useSession();
  const [query, setQuery] = useState<FilterState>(() =>
    fromSearchParams(new URLSearchParams(searchParams?.toString())),
  );
  const [formKey, setFormKey] = useState(0);
  const [cursors, setCursors] = useState<Array<string | null>>([null]);
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});
  const [selected, setSelected] = useState<string | null>(null);
  const [panel, setPanel] = useState<ScannerRow | null>(null);
  const [extraPending, setExtraPending] = useState(0);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const [knownSources, setKnownSources] = useState<SourceStatus[]>([]);
  const current = cursors[cursors.length - 1] ?? null;
  const pageIndex = cursors.length - 1;
  const loaded = useRef<ScannerPage | null>(null);
  const focusAfterClose = useRef<string | null>(null);

  const fetchPage = useCallback(
    async (cursor: string | null, quiet: boolean) => {
      if (!quiet) {
        setLoad({ kind: 'loading' });
      }
      try {
        const page = await api.listTokens({
          cursor,
          limit: PAGE_SIZE,
          sort: query.sort,
          order: query.order,
          filters: query.filters,
        });
        setKnownSources(page.sources);
        setUpdatedAt(new Date());
        const previous = loaded.current;
        if (quiet && previous && cursor === null) {
          const fresh = new Map(page.items.map((item) => [rowKey(item), item]));
          const kept = previous.items.map((item) => fresh.get(rowKey(item)) ?? item);
          const shown = new Set(kept.map(rowKey));
          setExtraPending(page.items.filter((item) => !shown.has(rowKey(item))).length);
          const merged = {
            ...page,
            items: kept,
            nextCursor: previous.nextCursor,
            total: previous.total,
          };
          loaded.current = merged;
          setLoad({ kind: 'ready', page: merged });
          return;
        }
        loaded.current = page;
        setExtraPending(0);
        setServerErrors({});
        setLoad({ kind: 'ready', page });
      } catch (failure) {
        if (failure instanceof ApiError && failure.status === 401) {
          expire();
          return;
        }
        if (failure instanceof ApiError && failure.code === 'SOURCE_UNAVAILABLE') {
          loaded.current = null;
          setLoad({ kind: 'allFailed', sources: failure.sources });
          return;
        }
        if (failure instanceof ApiError && failure.status === 400 && failure.fields.length > 0) {
          const errors: FieldErrors = {};
          failure.fields.forEach((field) => {
            errors[field.field as keyof ScannerFilters] = text.filters.rejected;
          });
          setServerErrors(errors);
          setLoad({ kind: 'invalid' });
          return;
        }
        if (!quiet || !loaded.current) {
          setLoad({ kind: 'error' });
        }
      }
    },
    [api, expire, query],
  );

  useEffect(() => {
    if (status === 'authenticated') {
      void fetchPage(current, false);
    }
  }, [status, current, fetchPage]);

  useEffect(() => {
    if (status !== 'authenticated') {
      return undefined;
    }
    const timer = setInterval(() => void fetchPage(current, true), refreshMs);
    return () => clearInterval(timer);
  }, [status, current, refreshMs, fetchPage]);

  useEffect(() => {
    if (panel === null && focusAfterClose.current) {
      const mint = focusAfterClose.current;
      focusAfterClose.current = null;
      document.querySelector<HTMLElement>(`[data-testid="scanner-row-select-${mint}"]`)?.focus();
    }
  }, [panel]);

  const apply = (next: FilterState) => {
    setQuery(next);
    setCursors([null]);
    setServerErrors({});
    loaded.current = null;
    const search = toSearchParams(next);
    router.replace(search ? `/scanner?${search}` : '/scanner');
  };

  const clearFilters = () => {
    apply({ filters: {}, sort: query.sort, order: query.order });
    setFormKey((key) => key + 1);
  };

  const refreshList = () => {
    loaded.current = null;
    setExtraPending(0);
    if (cursors.length === 1) {
      void fetchPage(null, false);
    } else {
      setCursors([null]);
    }
  };

  const page = load.kind === 'ready' ? load.page : null;
  const rows = page?.items ?? [];
  const select = (key: string) => {
    setSelected(key);
    setPanel(rows.find((row) => rowKey(row) === key) ?? null);
  };
  const closePanel = useCallback(() => {
    setPanel((open) => {
      focusAfterClose.current = open?.mint ?? null;
      return null;
    });
  }, []);

  const sources = page?.sources ?? (load.kind === 'allFailed' ? load.sources : knownSources);
  const filtering = hasActiveFilters(query.filters);
  const waiting =
    page !== null &&
    rows.length === 0 &&
    page.sources.length > 0 &&
    !page.sources.some((s) => s.status === 'OK' || s.status === 'DEGRADED') &&
    page.sources.some((s) => s.status === 'PENDING');
  const failedSources = sources.filter((s) => s.status === 'FAILED');
  const partial = rows.filter((row) => row.coverage.informed < row.coverage.total).length;
  const pending = (page?.pendingNewTokens ?? 0) + extraPending;
  const from = pageIndex * PAGE_SIZE + 1;
  const sourceNames = useMemo(() => sources.map((s) => s.source.name).join(', '), [sources]);
  const lastUpdate = (source: SourceStatus) =>
    source.lastUpdatedAt ? formatInstant(source.lastUpdatedAt, timeZone) : text.neverUpdated;

  return (
    <AppShell current="scanner" titleId="scanner-title" className="scanner-layout">
      <div className="scanner-main">
        <h1 id="scanner-title">{text.title}</h1>

        {sources.length > 0 && (
          <p data-testid="scanner-source">
            {text.sources(sourceNames)}
            {sources.some((s) => s.source.simulated) && <strong> — {text.simulated}</strong>}
          </p>
        )}
        {load.kind !== 'allFailed' && failedSources.length > 0 && (
          <div role="status" className="notice notice-warning" data-testid="scanner-source-failed">
            <p>⚠ {text.sourcesFailed}</p>
            <ul>
              {failedSources.map((source) => (
                <li key={source.source.sourceId}>
                  {text.sourceLine(
                    source.source.name,
                    text.sourceStatus.FAILED as string,
                    lastUpdate(source),
                  )}
                </li>
              ))}
            </ul>
          </div>
        )}
        <div aria-live="polite" className="muted" data-testid="scanner-updated">
          {updatedAt ? text.updatedAt(formatClock(updatedAt, timeZone)) : ''}
          {pending > 0 ? ` · ${text.pendingNew(pending)}` : ''}
        </div>

        <ScannerFiltersForm
          key={formKey}
          state={query}
          sources={knownSources}
          serverErrors={serverErrors}
          onApply={apply}
        />

        {pending > 0 && (
          <p data-testid="scanner-pending-new">
            {text.pendingNew(pending)} —{' '}
            <button type="button" data-testid="scanner-refresh-list" onClick={refreshList}>
              {text.refreshList}
            </button>
          </p>
        )}

        <section
          aria-label={text.regionLabel}
          aria-busy={load.kind === 'loading'}
          data-testid="scanner-region"
        >
          {load.kind === 'loading' && (
            <p role="status" data-testid="scanner-loading">
              {text.loading}
            </p>
          )}
          {(load.kind === 'error' || load.kind === 'invalid') && (
            <div role="alert" className="notice notice-error" data-testid="scanner-error">
              <h2>{text.errorTitle}</h2>
              <p>{load.kind === 'invalid' ? text.filters.rejected : text.errorBody}</p>
              <button
                type="button"
                data-testid="scanner-retry-button"
                onClick={() => void fetchPage(current, false)}
              >
                {text.retry}
              </button>
            </div>
          )}
          {load.kind === 'allFailed' && (
            <div role="alert" className="notice notice-error" data-testid="scanner-all-failed">
              <h2>{text.allSourcesFailedTitle}</h2>
              <p>{text.allSourcesFailedBody}</p>
              <ul>
                {load.sources.map((source) => (
                  <li key={source.source.sourceId}>
                    {text.sourceLine(
                      source.source.name,
                      text.sourceStatus[source.status] ?? source.status,
                      lastUpdate(source),
                    )}
                  </li>
                ))}
              </ul>
              <button
                type="button"
                data-testid="scanner-retry-button"
                onClick={() => void fetchPage(current, false)}
              >
                {text.retry}
              </button>
            </div>
          )}
          {filtering && page && page.excludedByUnknown > 0 && (
            <p className="notice" data-testid="scanner-excluded">
              ? {text.excludedByUnknown(page.excludedByUnknown)}
            </p>
          )}
          {waiting && <p data-testid="scanner-waiting">{text.waitingFirstCollection}</p>}
          {page && !waiting && rows.length === 0 && !filtering && (
            <p data-testid="scanner-empty">{text.empty}</p>
          )}
          {page && !waiting && rows.length === 0 && filtering && (
            <div data-testid="scanner-empty-filtered">
              <p>{text.emptyFiltered}</p>
              <button type="button" data-testid="scanner-clear-filters" onClick={clearFilters}>
                {text.clearFilters}
              </button>
            </div>
          )}
          {page && rows.length > 0 && (
            <>
              {partial > 0 && (
                <p className="notice" data-testid="scanner-partial">
                  ? {text.partial(partial)}
                </p>
              )}
              <p data-testid="scanner-showing">
                {text.showing(from, from + rows.length - 1, page.total)}
              </p>
              <ScannerTable rows={rows} selected={selected} onSelect={select} timeZone={timeZone} />
            </>
          )}
        </section>

        <nav aria-label="Paginação" className="pagination">
          {pageIndex > 0 && (
            <>
              <button
                type="button"
                className="secondary"
                data-testid="scanner-first-page"
                onClick={() => setCursors([null])}
              >
                {text.firstPage}
              </button>{' '}
              <button
                type="button"
                className="secondary"
                data-testid="scanner-previous-page"
                onClick={() => setCursors((stack) => stack.slice(0, -1))}
              >
                {text.previousPage}
              </button>{' '}
            </>
          )}
          {page?.nextCursor && (
            <button
              type="button"
              data-testid="scanner-next-page"
              onClick={() => setCursors((stack) => [...stack, page.nextCursor ?? null])}
            >
              {text.nextPage}
            </button>
          )}
        </nav>
      </div>
      {panel && (
        <AssessmentPanel
          key={rowKey(panel)}
          chain={panel.chain}
          mint={panel.mint}
          symbol={panel.symbol}
          timeZone={timeZone}
          onClose={closePanel}
          onUnauthenticated={expire}
        />
      )}
    </AppShell>
  );
}
