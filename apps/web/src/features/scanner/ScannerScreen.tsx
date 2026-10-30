'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { ScannerPage } from '@/lib/api/guest-api';
import { formatClock } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';
import { SignOutButton } from '@/features/auth/SignOutButton';
import { useSession } from '@/features/auth/SessionProvider';
import { useRequireSession } from '@/features/auth/useRequireSession';
import { ScannerTable } from '@/features/scanner/ScannerTable';

export const PAGE_SIZE = 50;
const text = messages.scanner;

type Load = { kind: 'loading' } | { kind: 'error' } | { kind: 'ready'; page: ScannerPage };

/**
 * S3 Scanner (minimal): loading only in the list area, error with "Tentar de novo", partial data and per-field
 * `unknown` as distinct states, always with text besides color (BR3.7). Data refreshes every 15 s on the same page
 * without moving the focus or the selected row, and the update is announced politely (CT-UX-8).
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
  const { expire } = useSession();
  const [cursors, setCursors] = useState<Array<string | null>>([null]);
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [selected, setSelected] = useState<string | null>(null);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const current = cursors[cursors.length - 1] ?? null;
  const pageIndex = cursors.length - 1;
  const loaded = useRef(false);

  const fetchPage = useCallback(
    async (cursor: string | null, quiet: boolean) => {
      if (!quiet) {
        setLoad({ kind: 'loading' });
      }
      try {
        const page = await api.listTokens(cursor, PAGE_SIZE);
        setLoad({ kind: 'ready', page });
        setUpdatedAt(new Date());
        loaded.current = true;
      } catch (failure) {
        if (failure instanceof ApiError && failure.status === 401) {
          expire();
          return;
        }
        if (!quiet || !loaded.current) {
          setLoad({ kind: 'error' });
        }
      }
    },
    [api, expire],
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

  const page = load.kind === 'ready' ? load.page : null;
  const source = page?.sources[0];
  const partial = page
    ? page.items.filter((row) => row.coverage.informed < row.coverage.total).length
    : 0;
  const from = pageIndex * PAGE_SIZE + 1;

  return (
    <>
      <header className="app-header">
        <span>{messages.app.name}</span>
        <span className="muted">{messages.app.paperOnly}</span>
        <SignOutButton />
      </header>
      <main id="conteudo" aria-labelledby="scanner-title">
        <h1 id="scanner-title">{text.title}</h1>

        {source && (
          <p data-testid="scanner-source">
            {text.source(source.source.name)}
            {source.source.simulated && <strong> — {text.simulated}</strong>}
          </p>
        )}
        {source?.status === 'FAILED' && (
          <p role="status" className="notice notice-warning" data-testid="scanner-source-failed">
            ⚠ {text.sourceFailed}
          </p>
        )}
        <p aria-live="polite" className="muted" data-testid="scanner-updated">
          {updatedAt ? text.updatedAt(formatClock(updatedAt, timeZone)) : ''}
        </p>

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
          {load.kind === 'error' && (
            <div role="alert" className="notice notice-error" data-testid="scanner-error">
              <h2>{text.errorTitle}</h2>
              <p>{text.errorBody}</p>
              <button
                type="button"
                data-testid="scanner-retry-button"
                onClick={() => void fetchPage(current, false)}
              >
                {text.retry}
              </button>
            </div>
          )}
          {page && page.items.length === 0 && <p data-testid="scanner-empty">{text.empty}</p>}
          {page && page.items.length > 0 && (
            <>
              {partial > 0 && (
                <p className="notice" data-testid="scanner-partial">
                  ? {text.partial(partial)}
                </p>
              )}
              <p data-testid="scanner-showing">
                {text.showing(from, from + page.items.length - 1, page.total)}
              </p>
              <ScannerTable
                rows={page.items}
                selected={selected}
                onSelect={setSelected}
                timeZone={timeZone}
              />
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
      </main>
    </>
  );
}
