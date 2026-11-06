'use client';

import { useRef, useState } from 'react';
import type { FeedbackScreen } from '@/lib/api/guest-api';
import { messages } from '@/lib/i18n/messages';
import { FeedbackDialog } from '@/features/feedback/FeedbackDialog';
import { screenOf } from '@/features/feedback/screens';

/**
 * "Enviar feedback" in the current header of the guest app (Q7 of the functional design, BR7.3): opens S9 over the
 * screen with the screen of origin taken from the route open at the click; after a send or a cancel the focus returns
 * to this item. A short confirmation is announced politely after a send.
 */
export function FeedbackButton({
  currentPath = () => window.location.pathname,
}: {
  currentPath?: () => string;
}) {
  const opener = useRef<HTMLButtonElement>(null);
  const [screen, setScreen] = useState<FeedbackScreen | null>(null);
  const [sent, setSent] = useState(false);
  const open = screen !== null;
  const setOpen = (value: boolean) => setScreen(value ? screenOf(currentPath()) : null);

  function close() {
    setOpen(false);
    // The dialog is gone in the next render; the focus returns to the item that opened it.
    queueMicrotask(() => opener.current?.focus());
  }

  return (
    <>
      <button
        type="button"
        className="secondary"
        ref={opener}
        data-testid="app-feedback-button"
        aria-haspopup="dialog"
        onClick={() => {
          setSent(false);
          setOpen(true);
        }}
      >
        {messages.feedback.open}
      </button>
      <span role="status" aria-live="polite" data-testid="app-feedback-status">
        {sent ? messages.feedback.sent : ''}
      </span>
      {open ? (
        <FeedbackDialog
          screen={screen}
          onClose={close}
          onSent={() => {
            setSent(true);
            close();
          }}
        />
      ) : null}
    </>
  );
}
