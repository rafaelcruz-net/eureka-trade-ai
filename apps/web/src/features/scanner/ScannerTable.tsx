'use client';

import type { ScannerRow } from '@/lib/api/guest-api';
import { abbreviateMint } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';
import { MarketValueCell } from '@/features/scanner/MarketValueCell';
import { RiskCell } from '@/features/scanner/RiskCell';

const text = messages.scanner;

export function rowKey(row: Pick<ScannerRow, 'chain' | 'mint'>): string {
  return `${row.chain}:${row.mint}`;
}

/**
 * The 7 fields of S3 plus the column "Risco" (U4). Rows are keyed by chain + mint, so data refreshes keep the selected
 * row and the focus where they were. The symbol button of a row opens the assessment panel with a click or Enter
 * (BR5.3) without reloading the list.
 */
export function ScannerTable({
  rows,
  selected,
  onSelect,
  timeZone,
}: {
  rows: ScannerRow[];
  selected: string | null;
  onSelect: (key: string) => void;
  timeZone?: string;
}) {
  return (
    <table className="scanner" data-testid="scanner-table">
      <caption className="muted">{text.regionLabel}</caption>
      <thead>
        <tr>
          <th scope="col" title={text.termHelp.mint}>
            {text.columns.token}
          </th>
          <th scope="col" title={text.columnHelp.price}>
            {text.columns.price}
          </th>
          <th scope="col" title={text.termHelp.liquidity}>
            {text.columns.liquidity}
          </th>
          <th scope="col" title={text.termHelp.volume}>
            {text.columns.volume24h}
          </th>
          <th scope="col" title={text.termHelp.holders}>
            {text.columns.holders}
          </th>
          <th scope="col" title={text.columnHelp.age}>
            {text.columns.age}
          </th>
          <th scope="col" title={text.columnHelp.coverage}>
            {text.columns.coverage}
          </th>
          <th scope="col">{text.riskColumn}</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => {
          const key = rowKey(row);
          const id = row.mint;
          return (
            <tr
              key={key}
              aria-selected={selected === key}
              data-testid={`scanner-row-${id}`}
              onClick={() => onSelect(key)}
            >
              <th scope="row">
                <button
                  type="button"
                  className="secondary"
                  data-testid={`scanner-row-select-${id}`}
                  aria-pressed={selected === key}
                  aria-label={text.openAssessment(row.symbol)}
                  onClick={(event) => {
                    event.stopPropagation();
                    onSelect(key);
                  }}
                >
                  {row.symbol}
                </button>{' '}
                <span className="muted" data-testid={`scanner-row-mint-${id}`} title={row.mint}>
                  {abbreviateMint(row.mint)}
                </span>
              </th>
              <td>
                <MarketValueCell
                  value={row.price}
                  testId={`scanner-price-${id}`}
                  timeZone={timeZone}
                />
              </td>
              <td>
                <MarketValueCell
                  value={row.liquidity}
                  testId={`scanner-liquidity-${id}`}
                  timeZone={timeZone}
                />
              </td>
              <td>
                <MarketValueCell
                  value={row.volume24h}
                  testId={`scanner-volume-${id}`}
                  timeZone={timeZone}
                />
              </td>
              <td>
                <MarketValueCell
                  value={row.holders}
                  testId={`scanner-holders-${id}`}
                  timeZone={timeZone}
                />
              </td>
              <td>
                <MarketValueCell
                  value={row.ageMinutes}
                  testId={`scanner-age-${id}`}
                  unit={text.minutes}
                  timeZone={timeZone}
                />
              </td>
              <td data-testid={`scanner-coverage-${id}`}>
                {text.coverage(row.coverage.informed, row.coverage.total)}
              </td>
              <td>
                <RiskCell risk={row.risk} testId={`scanner-risk-${id}`} />
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
