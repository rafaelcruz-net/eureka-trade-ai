import { realMessages as text } from '@/features/real/messages';

/**
 * 261113-trading-real-bot U6 (BR7.1, U6-NFR6.4; design-system-mapping.md `color.real`): the REAL label: text, icon and
 * the amber of the design system, never the color alone. The pair of the PAPER tag.
 */
export function RealBadge({ testId = 'real-badge' }: { testId?: string }) {
  return (
    <span className="real-badge" data-testid={testId}>
      <span aria-hidden="true" className="real-badge-icon">
        ◆
      </span>{' '}
      {text.badge}
    </span>
  );
}
