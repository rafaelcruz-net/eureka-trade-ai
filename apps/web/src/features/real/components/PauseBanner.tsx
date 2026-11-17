import { realMessages as text } from '@/features/real/messages';

/** BR8.3: the text of a paused reason; an unknown reason is "REAL pausado". */
export function pausedText(reason: string): string {
  return text.paused.reasons[reason] ?? text.paused.title;
}

/**
 * 261113-trading-real-bot U6 (BR8.3, AC2.2, mockups R2 "REAL pausado"): the banner of a paused REAL with its reason in
 * text and the reminder that the withdrawal stays available. Nothing is shown when the REAL is not paused.
 */
export function PauseBanner({ reason }: { reason: string }) {
  if (reason === 'NONE' || reason === '') {
    return null;
  }
  return (
    <div role="status" className="notice notice-warning" data-testid="real-pause-banner">
      <p>
        <span aria-hidden="true">⏸ </span>
        {pausedText(reason)}
      </p>
      <p>{text.paused.withdrawStill}</p>
    </div>
  );
}
