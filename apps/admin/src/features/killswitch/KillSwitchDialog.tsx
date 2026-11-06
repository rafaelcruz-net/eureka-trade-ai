'use client';

import { useState } from 'react';
import { Dialog } from '@/components/Dialog';
import { useApi } from '@/lib/api/ApiProvider';
import type { KillSwitch } from '@/lib/api/admin-api';
import { codePoints, count } from '@/lib/format';
import { messages } from '@/lib/i18n/messages';

const text = messages.killSwitch;
const MAX_REASON = 500;

/**
 * Confirmation of the kill switch (BR7.2, BR2.6): switching ON says how many ACTIVE bots will be paused (read when the
 * screen opened); switching OFF says the bots stay paused until each owner resumes. The reason (1 to 500 characters) is
 * required. The Idempotency-Key is generated when the dialog opens and reused on a new attempt; the route is called only
 * after the confirmation.
 */
export function KillSwitchDialog({
  target,
  activeBots,
  onClose,
  onChanged,
  onFailure,
  returnFocusTo,
}: {
  target: 'ON' | 'OFF';
  activeBots: number;
  onClose: () => void;
  onChanged: (next: KillSwitch) => void;
  onFailure: (failure: unknown) => boolean;
  returnFocusTo?: HTMLElement | null;
}) {
  const api = useApi();
  const [key] = useState(() => api.newKey());
  const [reason, setReason] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const used = codePoints(reason.trim());

  async function confirm() {
    if (used === 0) {
      setError(text.reasonEmpty);
      return;
    }
    if (used > MAX_REASON) {
      setError(text.reasonTooLong);
      return;
    }
    setSending(true);
    setError(null);
    try {
      onChanged(await api.changeKillSwitch({ state: target, reason: reason.trim() }, key));
    } catch (failure) {
      if (!onFailure(failure)) {
        setError(text.changeError);
      }
      setSending(false);
    }
  }

  return (
    <Dialog
      title={target === 'ON' ? text.activateTitle : text.deactivateTitle}
      testId="kill-switch-dialog"
      onClose={onClose}
      returnFocusTo={returnFocusTo}
    >
      <p data-testid="kill-switch-dialog-impact">
        {target === 'ON' ? text.activeBots(activeBots) : text.deactivateBody}
      </p>
      <label htmlFor="kill-switch-dialog-reason">{text.reasonLabel}</label>
      <p id="kill-switch-dialog-help">{text.reasonHelp}</p>
      <textarea
        id="kill-switch-dialog-reason"
        rows={3}
        value={reason}
        aria-describedby="kill-switch-dialog-help kill-switch-dialog-counter"
        data-testid="kill-switch-dialog-reason"
        onChange={(event) => setReason(event.target.value)}
      />
      <p id="kill-switch-dialog-counter" data-testid="kill-switch-dialog-counter">
        {text.reasonCounter(count(used))}
      </p>
      {error ? (
        <p role="alert" className="field-error" data-testid="kill-switch-dialog-error">
          {error}
        </p>
      ) : null}
      <div className="dialog-actions">
        <button
          type="button"
          className={target === 'ON' ? 'danger' : undefined}
          disabled={sending}
          data-testid="kill-switch-dialog-confirm"
          onClick={() => void confirm()}
        >
          {sending ? text.sending : target === 'ON' ? text.confirmOn : text.confirmOff}
        </button>
        <button
          type="button"
          className="secondary"
          data-testid="kill-switch-dialog-cancel"
          onClick={onClose}
        >
          {text.cancel}
        </button>
      </div>
    </Dialog>
  );
}
