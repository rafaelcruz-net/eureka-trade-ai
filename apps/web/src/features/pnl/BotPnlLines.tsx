'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, RequestCancelled } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { RealBotPnlLineItem } from '@/lib/api/guest-api';
import { abbreviateMint, formatDayTime } from '@/lib/format/format';
import { TableScroll } from '@/components/TableScroll';
import { failureCode } from '@/features/pnl/by-mode';
import { modeMessages } from '@/features/pnl/mode-messages';
import { formatSol, formatSolAmount } from '@/features/pnl/money-format';
import { originText } from '@/features/pnl/ProratedLines';

const text = modeMessages.lines;
export const LINES_LIMIT = 50;

export type BotPnlLinesPhase =
  | 'loading'
  | 'ready'
  | 'empty'
  | 'error'
  | 'loadingMore'
  | 'moreError'
  | 'cursorStale';

/** The state of the lines of C5 (functional-spec.md, state machine of the lines; alignment A9). */
export interface BotPnlLinesState {
  phase: BotPnlLinesPhase;
  items: RealBotPnlLineItem[];
  nextCursor: string | null;
  code: string | null;
  /** The index of the first line of the last page, which receives the focus after "Carregar mais". */
  firstNewIndex: number | null;
}

const INITIAL: BotPnlLinesState = {
  phase: 'loading',
  items: [],
  nextCursor: null,
  code: null,
  firstNewIndex: null,
};

/** A 400 of the field `cursor`: the anchor of the page is gone (the list changed since the last page). */
function isStaleCursor(failure: unknown): boolean {
  return (
    failure instanceof ApiError &&
    failure.status === 400 &&
    failure.fields.some((field) => field.field === 'cursor')
  );
}

function isNotFound(failure: unknown): boolean {
  return failure instanceof ApiError && failure.status === 404;
}

function wideScreen(): boolean {
  return typeof window.matchMedia !== 'function' || window.matchMedia('(min-width: 768px)').matches;
}

/**
 * 261121-paper-real-unico U3 (BR4.1 to BR4.6, BR2.5, BR8.2, BR10.1, BR10.2; AC3.10.1 to AC3.10.4, AC7.2.2): the lines of
 * the P&L of one REAL bot (C5), in the order of the network as they came, 50 per page with "Carregar mais"; the button
 * is disabled while it loads and the focus goes to the first new line. A failure of the next page keeps the lines and
 * offers "Tentar de novo" with the same cursor; a 400 of the cursor (the list changed) offers "Recarregar do início",
 * which reads the first page without a cursor and replaces the lines; a 404 hands over to `onNotFound`. Six columns:
 * a buy has "—" ("sem resultado"). The screen never adds the lines: the total of the bot is the one of C4.
 */
export function BotPnlLines({
  botId,
  botName,
  timeZone,
  onNotFound,
}: {
  botId: string;
  botName: string;
  timeZone?: string;
  onNotFound: () => void;
}) {
  const api = useApi();
  const [state, setState] = useState<BotPnlLinesState>(INITIAL);
  const slot = useRef<AbortController | null>(null);
  const firstNewRow = useRef<HTMLTableRowElement>(null);
  const firstNewCard = useRef<HTMLLIElement>(null);
  const notFound = useRef(onNotFound);
  notFound.current = onNotFound;

  useEffect(() => () => slot.current?.abort(), []);

  const read = useCallback(
    async (cursor: string | null) => {
      slot.current?.abort();
      const controller = new AbortController();
      slot.current = controller;
      setState((current) =>
        cursor === null
          ? { ...INITIAL }
          : { ...current, phase: 'loadingMore', code: null, firstNewIndex: null },
      );
      try {
        const page = await api.listBotPnlLines(
          botId,
          { cursor, limit: LINES_LIMIT },
          { signal: controller.signal },
        );
        if (slot.current !== controller) {
          return;
        }
        setState((current) => {
          if (cursor === null) {
            return {
              phase: page.items.length === 0 ? 'empty' : 'ready',
              items: page.items,
              nextCursor: page.nextCursor,
              code: null,
              firstNewIndex: null,
            };
          }
          return {
            phase: 'ready',
            items: [...current.items, ...page.items],
            nextCursor: page.nextCursor,
            code: null,
            firstNewIndex: page.items.length > 0 ? current.items.length : null,
          };
        });
      } catch (failure) {
        if (failure instanceof RequestCancelled || slot.current !== controller) {
          return;
        }
        if (isNotFound(failure)) {
          notFound.current();
          return;
        }
        const code = failureCode(failure);
        setState((current) => {
          if (cursor === null) {
            return { ...INITIAL, phase: 'error', code };
          }
          if (isStaleCursor(failure)) {
            return { ...current, phase: 'cursorStale', code };
          }
          return { ...current, phase: 'moreError', code };
        });
      }
    },
    [api, botId],
  );

  useEffect(() => {
    void read(null);
  }, [read]);

  useEffect(() => {
    if (state.firstNewIndex === null) {
      return;
    }
    (wideScreen() ? firstNewRow.current : firstNewCard.current)?.focus();
  }, [state.firstNewIndex]);

  const caption = text.caption(botName);
  const { phase, items } = state;
  const showLines = phase !== 'loading' && phase !== 'error' && phase !== 'empty';
  return (
    <section aria-labelledby="bot-lines-title" className="bot-pnl-lines" data-testid="bot-lines">
      <h2 id="bot-lines-title">{text.heading}</h2>
      {phase === 'loading' && (
        <p role="status" data-testid="bot-lines-loading">
          {text.loading}
        </p>
      )}
      {phase === 'empty' && <p data-testid="bot-lines-empty">{text.empty}</p>}
      {phase === 'error' && (
        <div role="alert" className="notice notice-error" data-testid="bot-lines-error">
          <p>{text.error}</p>
          <p className="muted" data-testid="bot-lines-code">
            {modeMessages.supportCode(state.code ?? '')}
          </p>
          <button
            type="button"
            className="secondary"
            data-testid="bot-lines-retry"
            onClick={() => void read(null)}
          >
            {modeMessages.retry}
          </button>
        </div>
      )}
      {showLines && (
        <>
          <ul className="pnl-lines-cards" data-testid="bot-lines-cards">
            {items.map((line, index) => (
              <li
                key={index}
                ref={index === state.firstNewIndex ? firstNewCard : undefined}
                tabIndex={-1}
                className="pnl-line-card"
                data-testid={`bot-lines-card-${index}`}
              >
                <dl>
                  <dt>{text.columns.date}</dt>
                  <dd>{formatDayTime(line.at, timeZone)}</dd>
                  <dt>{text.columns.side}</dt>
                  <dd>{text.sides[line.side] ?? line.side}</dd>
                  <dt>{text.columns.token}</dt>
                  <dd title={line.mint}>{abbreviateMint(line.mint)}</dd>
                  <dt>{text.columns.result}</dt>
                  <dd>
                    <LineResult line={line} />
                  </dd>
                  <dt>{text.columns.fee}</dt>
                  <dd>{formatSolAmount(line.feeLamports)}</dd>
                  <dt>{text.columns.origin}</dt>
                  <dd>{originText(line.origin)}</dd>
                </dl>
              </li>
            ))}
          </ul>
          <div className="pnl-lines-table">
            <TableScroll label={caption} testId="bot-lines-scroll">
              <table data-testid="bot-lines-table">
                <caption>{caption}</caption>
                <thead>
                  <tr>
                    <th scope="col">{text.columns.date}</th>
                    <th scope="col">{text.columns.side}</th>
                    <th scope="col">{text.columns.token}</th>
                    <th scope="col">{text.columns.result}</th>
                    <th scope="col">{text.columns.fee}</th>
                    <th scope="col">{text.columns.origin}</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((line, index) => (
                    <tr
                      key={index}
                      ref={index === state.firstNewIndex ? firstNewRow : undefined}
                      tabIndex={-1}
                      data-testid={`bot-lines-row-${index}`}
                    >
                      <td>{formatDayTime(line.at, timeZone)}</td>
                      <td>{text.sides[line.side] ?? line.side}</td>
                      <td title={line.mint}>{abbreviateMint(line.mint)}</td>
                      <td data-testid={`bot-lines-result-${index}`}>
                        <LineResult line={line} />
                      </td>
                      <td>{formatSolAmount(line.feeLamports)}</td>
                      <td data-testid={`bot-lines-origin-${index}`}>{originText(line.origin)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </TableScroll>
          </div>
        </>
      )}
      {phase === 'moreError' && (
        <div role="alert" className="notice notice-error" data-testid="bot-lines-more-error">
          <p>{text.moreError}</p>
          <p className="muted">{modeMessages.supportCode(state.code ?? '')}</p>
          <button
            type="button"
            className="secondary"
            data-testid="bot-lines-more-retry"
            onClick={() => void read(state.nextCursor)}
          >
            {modeMessages.retry}
          </button>
        </div>
      )}
      {phase === 'cursorStale' && (
        <div role="alert" className="notice" data-testid="bot-lines-stale">
          <p>{text.stale}</p>
          <button
            type="button"
            className="secondary"
            data-testid="bot-lines-reload"
            onClick={() => void read(null)}
          >
            {text.reload}
          </button>
        </div>
      )}
      {(phase === 'ready' || phase === 'loadingMore') && state.nextCursor !== null && (
        <button
          type="button"
          className="secondary"
          disabled={phase === 'loadingMore'}
          aria-busy={phase === 'loadingMore'}
          data-testid="bot-lines-more"
          onClick={() => void read(state.nextCursor)}
        >
          {phase === 'loadingMore' ? text.loadingMore : text.loadMore}
        </button>
      )}
    </section>
  );
}

/** BR4.4: a buy has no result ("—", read as "sem resultado"); a sale writes its result in SOL with the sign. */
function LineResult({ line }: { line: RealBotPnlLineItem }) {
  if (line.side === 'BUY') {
    return (
      <span aria-label={text.noResult} role="img">
        —
      </span>
    );
  }
  return <>{formatSol(line.realizedLamports)}</>;
}
