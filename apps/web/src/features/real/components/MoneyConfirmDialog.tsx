'use client';

import { useRef } from 'react';
import type { MoneyActionState } from '@/features/real/hooks/useMoneyAction';
import { ModalDialog } from '@/features/real/components/ModalDialog';
import { RealErrorNotice } from '@/features/real/components/RealErrorNotice';
import { realMessages as text } from '@/features/real/messages';

/**
 * 261113-trading-real-bot U6 (BR6.1, BR6.3, BR6.5; U6-NFR3.3, U6-NFR6.2; interaction-spec.md MoneyConfirmDialog): the
 * confirmation of an action with money (activate, raise the mode or the cap, withdraw, sell everything and withdraw).
 * It names the amount, the destination and the effect; the first focus is "Cancelar"; while sending, "Confirmar" is
 * disabled and says "Enviando…"; an error shows its text with "Tentar de novo", which resends the same body with the
 * same key. Closing it discards the key (the caller resets the action).
 */
export function MoneyConfirmDialog({
  title,
  amount,
  destination,
  effect,
  action,
  onConfirm,
  onClose,
  returnFocusTo,
  notice,
  onRetry,
  testId = 'real-confirm',
}: {
  title: string;
  amount?: string;
  destination?: string;
  effect: string;
  action: { state: MoneyActionState<unknown>; sending: boolean; retry(): Promise<unknown> };
  onConfirm: () => void;
  onClose: () => void;
  returnFocusTo?: HTMLElement | null;
  /** A refusal before the request (the wallet refused, no wallet), shown inside the dialog. */
  notice?: string | null;
  /** "Tentar de novo": resends the same body with the same key; by default the retry of the action. */
  onRetry?: () => void;
  testId?: string;
}) {
  const cancel = useRef<HTMLButtonElement>(null);
  const { state, sending } = action;
  return (
    <ModalDialog
      title={title}
      testId={testId}
      role="alertdialog"
      onClose={() => {
        if (!sending) {
          onClose();
        }
      }}
      initialFocus={cancel}
      returnFocusTo={returnFocusTo}
    >
      <dl>
        {amount !== undefined && (
          <>
            <dt>{text.confirmDialog.amount}</dt>
            <dd data-testid={`${testId}-amount`}>{amount}</dd>
          </>
        )}
        {destination !== undefined && (
          <>
            <dt>{text.confirmDialog.destination}</dt>
            <dd data-testid={`${testId}-destination`}>{destination}</dd>
          </>
        )}
        <dt>{text.confirmDialog.effect}</dt>
        <dd data-testid={`${testId}-effect`}>{effect}</dd>
      </dl>
      {notice && (
        <p role="alert" data-testid={`${testId}-notice`}>
          {notice}
        </p>
      )}
      {state.kind === 'error' && (
        <RealErrorNotice
          error={state.error}
          testId={`${testId}-error`}
          onRetry={onRetry ?? (() => void action.retry())}
        />
      )}
      <p className="dialog-actions">
        <button
          ref={cancel}
          type="button"
          className="secondary"
          disabled={sending}
          onClick={onClose}
          data-testid={`${testId}-cancel`}
        >
          {text.cancel}
        </button>{' '}
        <button
          type="button"
          disabled={sending}
          aria-busy={sending}
          onClick={onConfirm}
          data-testid={`${testId}-submit`}
        >
          {sending ? text.sending : text.confirm}
        </button>
      </p>
    </ModalDialog>
  );
}
