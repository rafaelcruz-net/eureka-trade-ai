'use client';

import { useEffect, useRef, type ReactNode } from 'react';

/**
 * Modal dialog of the administrator app (frontend-components.md "Acessibilidade"): the focus enters the dialog and
 * stays inside it (Tab and Shift+Tab cycle), Esc closes it without effect, and the focus returns to the element that
 * opened it.
 */
export function Dialog({
  title,
  testId,
  onClose,
  returnFocusTo,
  children,
}: {
  title: string;
  testId: string;
  onClose: () => void;
  returnFocusTo?: HTMLElement | null;
  children: ReactNode;
}) {
  const panel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const first = focusables(panel.current)[0];
    (first ?? panel.current)?.focus();
    const origin = returnFocusTo;
    return () => origin?.focus();
  }, [returnFocusTo]);

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
        aria-labelledby={`${testId}-title`}
        className="dialog"
        tabIndex={-1}
        data-testid={testId}
        onKeyDown={onKeyDown}
      >
        <h2 id={`${testId}-title`}>{title}</h2>
        {children}
      </div>
    </div>
  );
}

function focusables(root: HTMLElement | null): HTMLElement[] {
  if (!root) {
    return [];
  }
  return Array.from(
    root.querySelectorAll<HTMLElement>(
      'textarea:not([disabled]), input:not([disabled]), select:not([disabled]), button:not([disabled]), a[href]',
    ),
  );
}
