'use client';

import { useEffect, useRef, type ReactNode, type RefObject } from 'react';

/**
 * 261113-trading-real-bot U6 (U6-NFR6.2; interaction-spec.md): the native `<dialog>` opened with `showModal()`, which
 * traps the focus and closes with Esc in the browser. The first focus goes to `initialFocus` (Cancelar in the money
 * confirmation, "Assinar na carteira" in the signing dialog) and returns to the element that opened it. Esc (the
 * `cancel` event) closes without effect.
 */
export function ModalDialog({
  title,
  testId,
  role = 'dialog',
  onClose,
  initialFocus,
  returnFocusTo,
  children,
}: {
  title: string;
  testId: string;
  role?: 'dialog' | 'alertdialog';
  onClose: () => void;
  initialFocus?: RefObject<HTMLElement | null>;
  returnFocusTo?: HTMLElement | null;
  children: ReactNode;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;

  useEffect(() => {
    const element = dialog.current;
    if (element && !element.open) {
      element.showModal();
    }
    (initialFocus?.current ?? element)?.focus();
    const origin = returnFocusTo;
    return () => {
      if (element?.open) {
        element.close();
      }
      origin?.focus();
    };
  }, [initialFocus, returnFocusTo]);

  return (
    <dialog
      ref={dialog}
      role={role}
      aria-modal="true"
      aria-labelledby={`${testId}-title`}
      className="dialog real-dialog"
      data-testid={testId}
      onCancel={(event) => {
        event.preventDefault();
        closeRef.current();
      }}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault();
          closeRef.current();
        }
      }}
    >
      <h2 id={`${testId}-title`}>{title}</h2>
      {children}
    </dialog>
  );
}
