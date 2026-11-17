'use client';

import { useEffect, useRef, useState, type ReactNode } from 'react';
import { codePoints } from '@/lib/format';
import type { AdminActionState } from '@/features/real/hooks/useAdminAction';
import { adminRealMessages as text } from '@/features/real/messages';

export const REASON_MAX = 500;

/** U6-NFR1.11: the reason is counted in CHARACTERS (code points, as the server counts), never bytes, after trimming. */
export function reasonLength(reason: string): number {
  return codePoints(reason.trim());
}

export function reasonProblem(reason: string): string | null {
  const length = reasonLength(reason);
  if (length === 0) {
    return text.reason.empty;
  }
  return length > REASON_MAX ? text.reason.tooLong : null;
}

/**
 * 261113-trading-real-bot U6 (BR9.2, BR9.3; U6-NFR1.11, U6-NFR6.2, U6-NFR3.3): every action of the REAL section goes
 * through this native `<dialog>`: the effect in text, the fields of the action, a reason of 1 to 500 characters checked
 * before sending, and the confirmation. The first focus is "Cancelar"; Esc closes without effect and the focus returns
 * to the opener. While sending, "Confirmar" is disabled; a refusal of the server is shown in text with the id for
 * support.
 */
export function AdminActionDialog<R>({
  title,
  effect,
  testId,
  state,
  onSubmit,
  onClose,
  returnFocusTo,
  reasonRequired = true,
  ready = true,
  children,
}: {
  title: string;
  effect: string;
  testId: string;
  state: AdminActionState<R>;
  onSubmit: (reason: string) => void;
  onClose: () => void;
  returnFocusTo?: HTMLElement | null;
  /** The unblock of a token has no body on the server, so it asks only the confirmation. */
  reasonRequired?: boolean;
  /** The fields of the action are valid. */
  ready?: boolean;
  children?: ReactNode;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const sending = state.kind === 'sending';
  const problem = reasonRequired ? reasonProblem(reason) : null;
  const closeRef = useRef(onClose);
  closeRef.current = onClose;

  useEffect(() => {
    const element = dialog.current;
    if (element && !element.open) {
      element.showModal();
    }
    cancel.current?.focus();
    const origin = returnFocusTo;
    return () => {
      if (element?.open) {
        element.close();
      }
      origin?.focus();
    };
  }, [returnFocusTo]);

  function close() {
    if (!sending) {
      closeRef.current();
    }
  }

  return (
    <dialog
      ref={dialog}
      role="alertdialog"
      aria-modal="true"
      aria-labelledby={`${testId}-title`}
      className="dialog"
      data-testid={testId}
      onCancel={(event) => {
        event.preventDefault();
        close();
      }}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault();
          close();
        }
      }}
    >
      <h2 id={`${testId}-title`}>{title}</h2>
      <p data-testid={`${testId}-effect`}>{effect}</p>
      {children}
      {reasonRequired && (
        <p>
          <label htmlFor={`${testId}-reason`}>{text.reason.label}</label>
          <br />
          <textarea
            id={`${testId}-reason`}
            value={reason}
            rows={3}
            aria-invalid={touched && problem ? true : undefined}
            aria-describedby={`${testId}-reason-count`}
            onChange={(event) => setReason(event.target.value)}
            data-testid={`${testId}-reason`}
          />
          <br />
          <span id={`${testId}-reason-count`} className="muted">
            {text.reason.count(reasonLength(reason))}
          </span>
        </p>
      )}
      {touched && problem && (
        <p role="alert" data-testid={`${testId}-reason-error`}>
          {problem}
        </p>
      )}
      {state.kind === 'error' && (
        <div role="alert" data-testid={`${testId}-error`}>
          <p>{state.error.text}</p>
          {state.error.correlationId && (
            <p className="muted">{text.supportCode(state.error.correlationId)}</p>
          )}
        </div>
      )}
      <p>
        <button
          ref={cancel}
          type="button"
          className="secondary"
          disabled={sending}
          onClick={close}
          data-testid={`${testId}-cancel`}
        >
          {text.cancel}
        </button>{' '}
        <button
          type="button"
          disabled={sending || !ready}
          onClick={() => {
            setTouched(true);
            if (!problem) {
              onSubmit(reason.trim());
            }
          }}
          data-testid={`${testId}-submit`}
        >
          {sending ? text.sending : state.kind === 'error' ? text.retry : text.confirm}
        </button>
      </p>
    </dialog>
  );
}
