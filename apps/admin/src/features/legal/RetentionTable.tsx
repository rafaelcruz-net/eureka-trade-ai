import type { RetentionItem } from '@/features/legal/legal-api';
import { legalMessages } from '@/features/legal/messages';

const columns = legalMessages.retentionColumns;

/** The retention table of the privacy policy (FR3.4, BR1.8); stacked below 768 px (globals.css). Plain text only. */
export function RetentionTable({ items }: { items: RetentionItem[] }) {
  if (items.length === 0) {
    return (
      <p className="note" data-testid="legal-retention-empty">
        {legalMessages.retentionEmpty}
      </p>
    );
  }
  return (
    <table className="retention-table" data-testid="legal-retention-table">
      <caption>{legalMessages.retentionCaption}</caption>
      <thead>
        <tr>
          <th scope="col">{columns.dataItem}</th>
          <th scope="col">{columns.purpose}</th>
          <th scope="col">{columns.period}</th>
          <th scope="col">{columns.endAction}</th>
        </tr>
      </thead>
      <tbody>
        {items.map((item) => (
          <tr key={item.dataItem} data-testid="legal-retention-row">
            <td data-label={columns.dataItem}>{item.dataItem}</td>
            <td data-label={columns.purpose}>{item.purpose}</td>
            <td data-label={columns.period}>{item.period}</td>
            <td data-label={columns.endAction}>{item.endAction}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
