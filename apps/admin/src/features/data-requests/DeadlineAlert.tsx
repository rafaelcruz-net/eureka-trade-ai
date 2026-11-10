import type { AdminDataRequest } from '@/lib/api/admin-api';
import { messages } from '@/lib/i18n/messages';

const text = messages.dataRequests;
const DAY_MS = 24 * 60 * 60 * 1000;

/** Whole days until the due date, at least 1 while it is still ahead. */
export function daysUntil(dueAt: string, now: number): number {
  return Math.max(1, Math.ceil((new Date(dueAt).getTime() - now) / DAY_MS));
}

/**
 * u4-data-requests (BR4.16, NFR4.1): the due-date alert of an open request, an icon hidden from assistive technology
 * plus the text "Vencido" or "Vence em X dias" - never the color alone. The server decides overdue and due soon.
 */
export function DeadlineAlert({ request, now }: { request: AdminDataRequest; now: number }) {
  if (request.overdue) {
    return (
      <span className="deadline-alert deadline-alert-overdue" data-testid="deadline-alert">
        <span aria-hidden="true">⚠ </span>
        {text.overdue}
      </span>
    );
  }
  if (request.dueSoon) {
    return (
      <span className="deadline-alert" data-testid="deadline-alert">
        <span aria-hidden="true">⏰ </span>
        {text.dueIn(daysUntil(request.dueAt, now))}
      </span>
    );
  }
  return null;
}
