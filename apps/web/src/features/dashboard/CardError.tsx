'use client';

import { dashboardMessages as text } from '@/features/dashboard/messages';

/**
 * The error of ONE card (BR1.2, CT-UX-3): what happened, that the rest of the screen still works, and "Tentar de
 * novo", which repeats only the calls of that card.
 */
export function CardError({
  card,
  message,
  onRetry,
}: {
  card: 'bots' | 'pnl';
  message: string;
  onRetry: () => void;
}) {
  return (
    <div role="alert" className="notice notice-error" data-testid={`dashboard-card-error-${card}`}>
      <p>{message}</p>
      <button
        type="button"
        className="secondary"
        data-testid={`dashboard-card-retry-${card}`}
        onClick={onRetry}
      >
        {text.retry}
      </button>
    </div>
  );
}
