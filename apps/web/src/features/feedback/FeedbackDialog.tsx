'use client';

import { useEffect, useRef, useState } from 'react';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { FeedbackScreen } from '@/lib/api/guest-api';
import { messages } from '@/lib/i18n/messages';
import { MAX_FEEDBACK, codePoints } from '@/features/feedback/screens';

const COUNTER = new Intl.NumberFormat('pt-BR');

/**
 * S9 (BR6.1, BR6.3, BR7.3; NFR10.5): the comment dialog. It never leaves the screen: the screen of origin comes from the
 * route, the counter shows the characters used of 2.000, and the text stays in the field until a 201 arrives (400, 429,
 * 503 or no network show the reason and keep it). The Idempotency-Key is generated when the dialog opens and reused on
 * every new attempt, so a retry never creates a second comment. The focus stays inside the dialog (Tab cycles, Esc
 * closes) and returns to the item that opened it.
 */
export function FeedbackDialog({
  screen,
  onClose,
  onSent,
  newKey = () => crypto.randomUUID(),
}: {
  screen: FeedbackScreen;
  onClose: () => void;
  onSent: () => void;
  newKey?: () => string;
}) {
  const api = useApi();
  const [key] = useState(newKey);
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const panel = useRef<HTMLDivElement>(null);
  const field = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    field.current?.focus();
  }, []);

  const used = codePoints(text);
  const tooLong = used > MAX_FEEDBACK;
  const blank = text.trim().length === 0;

  async function send() {
    if (blank) {
      setError(messages.feedback.empty);
      return;
    }
    if (tooLong) {
      setError(messages.feedback.tooLong);
      return;
    }
    setSending(true);
    setError(null);
    try {
      await api.sendFeedback({ screen, text }, key);
      onSent();
    } catch (failure) {
      setError(reasonOf(failure));
      setSending(false);
    }
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key !== 'Tab') {
      return;
    }
    const items = focusables(panel.current);
    const first = items[0];
    const last = items[items.length - 1];
    if (!first || !last) {
      return;
    }
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  return (
    <div className="dialog-backdrop">
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby="feedback-dialog-title"
        aria-describedby="feedback-dialog-visibility"
        className="dialog"
        data-testid="feedback-dialog"
        onKeyDown={onKeyDown}
      >
        <h2 id="feedback-dialog-title">{messages.feedback.title}</h2>
        <p data-testid="feedback-dialog-screen">{messages.feedback.screen(screen)}</p>
        <p id="feedback-dialog-visibility" data-testid="feedback-dialog-visibility">
          {messages.feedback.visibility}
        </p>
        <label htmlFor="feedback-dialog-text">{messages.feedback.label}</label>
        <p id="feedback-dialog-help">{messages.feedback.help}</p>
        <textarea
          id="feedback-dialog-text"
          ref={field}
          rows={6}
          value={text}
          aria-describedby="feedback-dialog-help feedback-dialog-counter"
          aria-invalid={tooLong || undefined}
          data-testid="feedback-dialog-text"
          onChange={(event) => setText(event.target.value)}
        />
        <p
          id="feedback-dialog-counter"
          data-testid="feedback-dialog-counter"
          className={tooLong ? 'field-error' : undefined}
        >
          {messages.feedback.counter(COUNTER.format(used))}
        </p>
        {error ? (
          <p role="alert" className="field-error" data-testid="feedback-dialog-error">
            {error}
          </p>
        ) : null}
        <div className="dialog-actions">
          <button
            type="button"
            data-testid="feedback-dialog-send"
            disabled={sending}
            onClick={() => void send()}
          >
            {sending ? messages.feedback.sending : messages.feedback.send}
          </button>
          <button
            type="button"
            className="secondary"
            data-testid="feedback-dialog-cancel"
            onClick={onClose}
          >
            {messages.feedback.cancel}
          </button>
        </div>
      </div>
    </div>
  );
}

/** The reason in pt-BR for each failure of C6; the text always stays in the field. */
export function reasonOf(failure: unknown): string {
  if (!(failure instanceof ApiError)) {
    return messages.feedback.unavailable;
  }
  if (failure.status === 0) {
    return messages.feedback.network;
  }
  if (failure.status === 429) {
    const minutes = Math.max(1, Math.ceil((failure.retryAfterSeconds ?? 60) / 60));
    return messages.feedback.rateLimited(minutes);
  }
  if (failure.status === 400) {
    const tooLong = failure.fields.some(
      (field) => field.field === 'text' && /2000/.test(field.message),
    );
    return tooLong ? messages.feedback.tooLong : messages.feedback.invalid;
  }
  if (failure.status === 401) {
    return messages.feedback.sessionEnded;
  }
  return messages.feedback.unavailable;
}

function focusables(root: HTMLElement | null): HTMLElement[] {
  if (!root) {
    return [];
  }
  return Array.from(
    root.querySelectorAll<HTMLElement>(
      'textarea:not([disabled]), button:not([disabled]), input:not([disabled]), a[href]',
    ),
  );
}
