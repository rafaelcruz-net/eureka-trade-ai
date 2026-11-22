'use client';

import { useEffect, useRef, useState } from 'react';
import { ApiError } from '@/lib/api/client';
import type { RealIneligibilityReason } from '@/lib/api/guest-api';
import { Dialog } from '@/features/bots/Dialog';
import { realConfirmMessages as text } from '@/features/bots/mode-messages';

/** What a REAL creation answered, as the dialog hands it to its parent. */
export type RealCreateOutcome =
  | { kind: 'created'; botId: string }
  | { kind: 'refused'; reason: RealIneligibilityReason | string; code: string };

type Phase = { kind: 'idle' } | { kind: 'sending' } | { kind: 'technicalError'; code: string };

/**
 * 261121-paper-real-unico U1 (US1.3; BR1.4, BR1.5, BR2.8; interaction-spec "RealConfirmDialog"): the confirmation of
 * a REAL bot. "Criar bot real" stays disabled until the box "Este bot opera com dinheiro real" is checked; the first
 * click disables both buttons and shows "Criando o bot real…". The idempotency key is created when the dialog opens and
 * reused on every retry, so a double click or "Tentar de novo" never creates a second bot.
 *
 * <ul>
 *   <li>201: the parent opens the wizard;
 *   <li>409 REAL_NOT_ELIGIBLE: the dialog closes and hands the new reason to the parent (no "Tentar de novo");
 *   <li>5xx, timeout or no connection: the dialog stays open with "Tentar de novo" on the same key.
 * </ul>
 *
 * The focus starts on "Cancelar" and returns to the button that opened the dialog; Esc closes it, except while
 * sending.
 */
export function RealConfirmDialog({
  draft,
  cap,
  onConfirm,
  onRefused,
  onClose,
  returnFocusTo,
  newKey = () => crypto.randomUUID(),
}: {
  draft: { name: string; templateName: string };
  cap?: string;
  onConfirm: (idempotencyKey: string) => Promise<{ botId: string }>;
  onRefused: (outcome: Extract<RealCreateOutcome, { kind: 'refused' }>) => void;
  onClose: () => void;
  returnFocusTo: HTMLElement | null;
  newKey?: () => string;
}) {
  const [key] = useState(newKey);
  const [confirmed, setConfirmed] = useState(false);
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' });
  const cancel = useRef<HTMLButtonElement>(null);
  const sending = phase.kind === 'sending';

  useEffect(() => cancel.current?.focus(), []);

  async function create() {
    if (!confirmed || sending) {
      return;
    }
    setPhase({ kind: 'sending' });
    try {
      await onConfirm(key);
    } catch (failure) {
      if (
        failure instanceof ApiError &&
        failure.status === 409 &&
        failure.code === 'REAL_NOT_ELIGIBLE'
      ) {
        onRefused({
          kind: 'refused',
          reason: failure.reason ?? 'REAL_DISABLED',
          code: failure.correlationId ?? failure.code,
        });
        return;
      }
      const code =
        failure instanceof ApiError ? (failure.correlationId ?? failure.code) : 'NETWORK';
      setPhase({ kind: 'technicalError', code });
    }
  }

  return (
    <Dialog
      title={text.title}
      testId="real-confirm"
      returnFocusTo={returnFocusTo}
      onClose={() => {
        if (!sending) {
          onClose();
        }
      }}
    >
      <p data-testid="real-confirm-summary">{text.summary(draft.name, draft.templateName)}</p>
      {cap && <p data-testid="real-confirm-cap">{text.capLine(cap)}</p>}
      <div role="alert">
        <input
          type="checkbox"
          id="real-confirm-check"
          checked={confirmed}
          disabled={sending}
          data-testid="real-confirm-check"
          onChange={(event) => setConfirmed(event.target.checked)}
        />
        <label htmlFor="real-confirm-check">{text.checkbox}</label>
      </div>
      {phase.kind === 'technicalError' && (
        <div className="notice notice-error" data-testid="real-confirm-error">
          <p>{text.technicalError}</p>
          <p className="muted">{text.supportCode(phase.code)}</p>
        </div>
      )}
      <p id="real-confirm-help" className="muted">
        {text.checkboxHelp}
      </p>
      <button
        ref={cancel}
        type="button"
        className="secondary"
        disabled={sending}
        data-testid="real-confirm-cancel"
        onClick={onClose}
      >
        {text.cancel}
      </button>
      <button
        type="button"
        disabled={!confirmed || sending}
        aria-describedby="real-confirm-help"
        data-testid="real-confirm-create"
        onClick={() => void create()}
      >
        {sending ? text.creating : phase.kind === 'technicalError' ? text.retry : text.create}
      </button>
    </Dialog>
  );
}
