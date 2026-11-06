'use client';

import { useCallback, useEffect, useState } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type { FeedbackItem } from '@/lib/api/admin-api';
import { browserTimeZone, dateTime } from '@/lib/format';
import { messages } from '@/lib/i18n/messages';
import { useFailure } from '@/features/auth/use-failure';

const text = messages.feedback;

/**
 * The comments of the guests (F8; BR6.5, BR6.6; AC6.1.4): newest first by cursor, with the tenant name, the screen and
 * the time; the text is rendered as TEXT (never HTML or Markdown) and keeps its line breaks. No person, no wallet.
 */
export function FeedbackListPage({ timeZone = browserTimeZone() }: { timeZone?: string }) {
  const api = useApi();
  const handled = useFailure();
  const [items, setItems] = useState<FeedbackItem[] | null>(null);
  const [next, setNext] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  const load = useCallback(
    async (cursor: string | null) => {
      setFailed(false);
      try {
        const page = await api.listFeedback(cursor);
        setItems((current) => (cursor && current ? [...current, ...page.items] : page.items));
        setNext(page.nextCursor ?? null);
      } catch (failure) {
        if (!handled(failure)) {
          setFailed(true);
        }
      }
    },
    [api, handled],
  );

  useEffect(() => {
    void load(null);
  }, [load]);

  return (
    <section aria-labelledby="feedback-title" data-testid="feedback-page">
      <h1 id="feedback-title">{text.title}</h1>
      {failed ? (
        <p role="alert" className="field-error" data-testid="feedback-error">
          {text.error}
        </p>
      ) : null}
      {items === null && !failed ? (
        <p role="status" data-testid="feedback-loading">
          {text.loading}
        </p>
      ) : null}
      {items !== null && items.length === 0 ? (
        <p data-testid="feedback-empty">{text.empty}</p>
      ) : null}
      {items !== null && items.length > 0 ? (
        <ol className="feedback-list" data-testid="feedback-list">
          {items.map((item) => (
            <li key={item.feedbackId} data-testid={`feedback-item-${item.feedbackId}`}>
              <p className="meta">
                {text.tenant(item.tenantName)} · {text.screen(item.screen)} ·{' '}
                {text.at(dateTime(item.createdAt, timeZone))}
              </p>
              <p className="plain-text" data-testid="feedback-text">
                {item.text}
              </p>
            </li>
          ))}
        </ol>
      ) : null}
      {next ? (
        <button
          type="button"
          className="secondary"
          data-testid="feedback-more"
          onClick={() => void load(next)}
        >
          {text.more}
        </button>
      ) : null}
    </section>
  );
}
