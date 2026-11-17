'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import type { GuestApi } from '@/lib/api/guest-api';
import type { WalletPort } from '@/features/auth/wallets';
import { formatCountdown } from '@/lib/time/countdown';
import { realReads, toRealError, type SigningSession } from '@/features/real/api';
import type { Proposal } from '@/features/real/hooks/useSigningSession';
import { useMoneyAction } from '@/features/real/hooks/useMoneyAction';
import { formatSolAmount, formatToken } from '@/features/real/money';
import { realMessages as text } from '@/features/real/messages';
import { ModalDialog } from '@/features/real/components/ModalDialog';
import { RealErrorNotice } from '@/features/real/components/RealErrorNotice';
import {
  SignedTransactionMismatch,
  signServerTransaction,
  WalletCannotSignTransaction,
} from '@/features/real/sign-transaction';

/** How a signature ended; the banner shows the text of each for 5 s. */
export type SigningOutcome =
  | 'accepted'
  | 'expired'
  | 'refused'
  | 'rejectFailed'
  | 'notWaiting'
  | 'mismatch'
  | 'policyRefused'
  | 'walletCannotSign'
  | 'noWallet';

/** BR3.3, BR3.7: the codes of the server that end the dialog, with their outcome. */
function outcomeOf(code: string, status: number): SigningOutcome | null {
  if (code === 'SIGNING_DEADLINE_EXPIRED') {
    return 'expired';
  }
  if (code === 'SIGNED_MESSAGE_MISMATCH') {
    return 'mismatch';
  }
  if (code === 'SIGNING_POLICY_REFUSED') {
    return 'policyRefused';
  }
  if (status === 409 || status === 404) {
    return 'notWaiting';
  }
  return null;
}

/** A refusal of the wallet ("User rejected"), as opposed to a transport failure of the API. */
class WalletRefused extends Error {}

/**
 * 261113-trading-real-bot U6 (BR3.1, BR3.3, BR3.4, BR3.7; R4; interaction-spec.md SigningDialog; U6-NFR1.1, U6-NFR6.2):
 * the dialog of one proposal. It shows the side, the input, the expected and minimum outputs, the fee, the reason and
 * the deadline; "Assinar na carteira" hands the bytes of the server unchanged to the wallet and sends only the signed
 * transaction; "Recusar" (or a refusal in the wallet) calls reject. A transport failure keeps the dialog with
 * "Tentar de novo" (same body, same key); every other outcome closes it. The transaction lives only in this dialog and
 * is dropped when it closes.
 */
export function SigningDialog({
  api,
  proposal,
  seconds,
  wallet,
  returnFocusTo,
  onClose,
  onOutcome,
}: {
  api: GuestApi;
  proposal: Proposal;
  seconds: number;
  wallet: WalletPort;
  returnFocusTo: HTMLElement | null;
  onClose: () => void;
  onOutcome: (outcome: SigningOutcome) => void;
}) {
  const reads = useMemo(() => realReads(api), [api]);
  const [session, setSession] = useState<SigningSession | null>(proposal.session);
  const [waiting, setWaiting] = useState(false);
  const signButton = useRef<HTMLButtonElement>(null);
  const realOrderId = proposal.realOrderId;

  const submit = useMoneyAction((body: { signedTransactionBase64: string }, key: string) =>
    api.submitRealSignedTransaction(realOrderId, body, key),
  );

  useEffect(() => {
    if (session) {
      return;
    }
    let active = true;
    reads.signingSession(realOrderId).then(
      (loaded) => {
        if (active) {
          setSession(loaded);
        }
      },
      (failure: unknown) => {
        if (active) {
          const error = toRealError(failure);
          onOutcome(outcomeOf(error.code, error.status) ?? 'notWaiting');
        }
      },
    );
    return () => {
      active = false;
    };
  }, [session, reads, realOrderId, onOutcome]);

  async function reject(outcome: SigningOutcome) {
    try {
      await api.rejectRealSigning(realOrderId, crypto.randomUUID());
      onOutcome(outcome);
    } catch {
      // BR3.4: nothing is sent anyway, because without a signature the server never sends.
      onOutcome('rejectFailed');
    }
  }

  async function sign() {
    if (!session || waiting) {
      return;
    }
    setWaiting(true);
    let signed: string;
    try {
      await wallet.connect();
      signed = await signServerTransaction(wallet, session.transactionBase64).catch(
        (failure: unknown) => {
          if (
            failure instanceof SignedTransactionMismatch ||
            failure instanceof WalletCannotSignTransaction
          ) {
            throw failure;
          }
          throw new WalletRefused();
        },
      );
    } catch (failure) {
      setWaiting(false);
      if (failure instanceof SignedTransactionMismatch) {
        onOutcome('mismatch');
      } else if (failure instanceof WalletCannotSignTransaction) {
        onOutcome('walletCannotSign');
      } else {
        await reject('refused');
      }
      return;
    }
    setWaiting(false);
    await send({ signedTransactionBase64: signed });
  }

  async function send(body: { signedTransactionBase64: string }) {
    const result = await submit.submit(body);
    if (result) {
      onOutcome('accepted');
    }
  }

  const failed = submit.state.kind === 'error' ? submit.state.error : null;
  useEffect(() => {
    if (!failed) {
      return;
    }
    const outcome = outcomeOf(failed.code, failed.status);
    if (outcome) {
      onOutcome(outcome);
    }
  }, [failed, onOutcome]);

  const display = session?.display;
  const busy = waiting || submit.sending;
  return (
    <ModalDialog
      title={text.signing.dialogTitle(formatCountdown(seconds))}
      testId="real-signing-dialog"
      onClose={() => {
        if (!busy) {
          onClose();
        }
      }}
      initialFocus={signButton}
      returnFocusTo={returnFocusTo}
    >
      {!display ? (
        <p role="status">{text.signing.loading}</p>
      ) : (
        <dl>
          <dt>{text.signing.sides[display.side] ?? display.side}</dt>
          <dd data-testid="real-signing-input">
            {display.side === 'BUY'
              ? formatSolAmount(display.input.baseUnits)
              : formatToken(display.input.baseUnits, display.input.decimals)}
          </dd>
          <dt>{text.signing.expectedOutput}</dt>
          <dd>{formatToken(display.expectedOutput.baseUnits, display.expectedOutput.decimals)}</dd>
          <dt>{text.signing.minOutput}</dt>
          <dd>{formatToken(display.minOutput.baseUnits, display.minOutput.decimals)}</dd>
          <dt>{text.signing.fee}</dt>
          <dd>{formatSolAmount(display.fee)}</dd>
          <dt>{text.signing.reason}</dt>
          <dd>{display.reason}</dd>
        </dl>
      )}
      {failed && !outcomeOf(failed.code, failed.status) && (
        <RealErrorNotice
          error={failed}
          testId="real-signing-error"
          onRetry={() => void submit.retry().then((result) => result && onOutcome('accepted'))}
        />
      )}
      {busy && <p role="status">{text.signing.waiting}</p>}
      <p className="dialog-actions">
        <button
          ref={signButton}
          type="button"
          disabled={!display || busy}
          onClick={() => void sign()}
          data-testid="real-signing-sign"
        >
          {text.signing.sign}
        </button>{' '}
        <button
          type="button"
          className="secondary"
          disabled={busy}
          onClick={() => void reject('refused')}
          data-testid="real-signing-reject"
        >
          {text.signing.reject}
        </button>
      </p>
    </ModalDialog>
  );
}
