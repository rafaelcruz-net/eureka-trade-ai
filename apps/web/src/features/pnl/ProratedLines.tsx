'use client';

import Link from 'next/link';
import { useId, useState } from 'react';
import type { RealBotPnlLine } from '@/lib/api/guest-api';
import { abbreviateMint, formatDayTime } from '@/lib/format/format';
import { TableScroll } from '@/components/TableScroll';
import { modeMessages } from '@/features/pnl/mode-messages';
import { formatSol, formatSolAmount } from '@/features/pnl/money-format';

const text = modeMessages.prorated;
/** The API sends at most the 10 most recent shares; the others are read on the bot screen (C5). */
const SHOWN = 10;

export type ProratedShares = RealBotPnlLine['prorated'];

/** BR4.1: the origin in text; an unknown code of a newer server is shown as it came. */
export function originText(origin: string): string {
  return modeMessages.origins[origin] ?? origin;
}

/**
 * 261121-paper-real-unico U3 (BR4.7, BR2.5, BR10.1, BR11.1; AC3.10.2, AC4.4.x): the prorated shares of one REAL bot
 * inside the REAL block. Nothing with `count = 0`. Otherwise one line "inclui <valor> de <origem>" per item of
 * `summary` (the value of the API), a button "Ver N linhas rateadas de <bot>" with `aria-expanded` and
 * `aria-controls`, and the received shares (at most 10): date in the person's time zone, token, origin in text,
 * received, fee and result, in SOL by money-format.ts. With more than 10, "Ver todas no detalhe do bot". Cards below
 * 768 px; a table with a caption in its own TableScroll from 768 px. No value is added, subtracted nor converted here.
 */
export function ProratedLines({
  botId,
  botName,
  prorated,
  timeZone,
}: {
  botId: string;
  botName: string;
  prorated: ProratedShares;
  timeZone?: string;
}) {
  const [expanded, setExpanded] = useState(false);
  const listId = useId();
  if (prorated.count === 0) {
    return null;
  }
  const lines = prorated.lines.slice(0, SHOWN);
  const testId = `prorated-${botId}`;
  return (
    <div className="prorated-lines" data-testid={testId}>
      <ul className="prorated-summary" data-testid={`${testId}-summary`}>
        {prorated.summary.map((item) => (
          <li key={item.origin} data-testid={`${testId}-summary-${item.origin}`}>
            {text.includes(formatSol(item.realizedLamports), originText(item.origin))}
          </li>
        ))}
      </ul>
      <button
        type="button"
        className="secondary"
        aria-expanded={expanded}
        aria-controls={listId}
        data-testid={`${testId}-toggle`}
        onClick={() => setExpanded((value) => !value)}
      >
        {expanded ? text.hide(prorated.count) : text.show(prorated.count)}
        <span className="visually-hidden">{text.ofBot(botName)}</span>
      </button>
      <div id={listId} hidden={!expanded} data-testid={`${testId}-list`}>
        {expanded && (
          <>
            <ul className="pnl-lines-cards" data-testid={`${testId}-cards`}>
              {lines.map((line, index) => (
                <li
                  key={`${line.at}-${index}`}
                  className="pnl-line-card"
                  data-testid={`${testId}-card-${index}`}
                >
                  <dl>
                    <dt>{text.columns.date}</dt>
                    <dd>{formatDayTime(line.at, timeZone)}</dd>
                    <dt>{text.columns.token}</dt>
                    <dd title={line.mint}>{abbreviateMint(line.mint)}</dd>
                    <dt>{text.columns.origin}</dt>
                    <dd>{originText(line.origin)}</dd>
                    <dt>{text.columns.received}</dt>
                    <dd>{formatSolAmount(line.receivedLamports)}</dd>
                    <dt>{text.columns.fee}</dt>
                    <dd>{formatSolAmount(line.feeLamports)}</dd>
                    <dt>{text.columns.realized}</dt>
                    <dd data-testid={`${testId}-card-realized-${index}`}>
                      {formatSol(line.realizedLamports)}
                    </dd>
                  </dl>
                </li>
              ))}
            </ul>
            <div className="pnl-lines-table">
              <TableScroll label={text.caption(botName)} testId={`${testId}-scroll`}>
                <table data-testid={`${testId}-table`}>
                  <caption>{text.caption(botName)}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{text.columns.date}</th>
                      <th scope="col">{text.columns.token}</th>
                      <th scope="col">{text.columns.origin}</th>
                      <th scope="col">{text.columns.received}</th>
                      <th scope="col">{text.columns.fee}</th>
                      <th scope="col">{text.columns.realized}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {lines.map((line, index) => (
                      <tr key={`${line.at}-${index}`} data-testid={`${testId}-row-${index}`}>
                        <td>{formatDayTime(line.at, timeZone)}</td>
                        <td title={line.mint}>{abbreviateMint(line.mint)}</td>
                        <td>{originText(line.origin)}</td>
                        <td>{formatSolAmount(line.receivedLamports)}</td>
                        <td>{formatSolAmount(line.feeLamports)}</td>
                        <td data-testid={`${testId}-row-realized-${index}`}>
                          {formatSol(line.realizedLamports)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </TableScroll>
            </div>
            {prorated.count > SHOWN && (
              <Link href={`/bots/${botId}`} data-testid={`${testId}-see-all`}>
                {text.seeAll}
              </Link>
            )}
          </>
        )}
      </div>
    </div>
  );
}
