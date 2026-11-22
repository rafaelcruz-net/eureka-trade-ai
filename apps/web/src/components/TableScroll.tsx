import type { ReactNode } from 'react';

/**
 * 261121-paper-real-unico U1 (NFR6.1, BR9.3): a wide data table scrolls inside its own region, never the page. The
 * region is named for screen readers and reachable by keyboard (Tab, then the arrow keys scroll it), so no column is
 * clipped or out of reach at 360 px.
 */
export function TableScroll({
  label,
  testId,
  children,
}: {
  label: string;
  testId: string;
  children: ReactNode;
}) {
  return (
    <div
      className="table-scroll"
      role="region"
      aria-label={label}
      tabIndex={0}
      data-testid={testId}
    >
      {children}
    </div>
  );
}
