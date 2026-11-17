'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { watchWallets, type WalletPort } from '@/features/auth/wallets';
import {
  clockSkewMs,
  formatCountdown,
  milestoneCrossed,
  remainingSeconds,
  TICK_MS,
} from '@/lib/time/countdown';
import type { Mode } from '@/features/real/api';
import { useSigningSession, type Proposal } from '@/features/real/hooks/useSigningSession';
import { formatSolAmount, formatToken } from '@/features/real/money';
import { realMessages as text } from '@/features/real/messages';
import { RealBadge } from '@/features/real/components/RealBadge';
import { SigningDialog, type SigningOutcome } from '@/features/real/components/SigningDialog';

/** The first compatible wallet of the browser (the same discovery as the login). */
export function firstWallet(): Promise<WalletPort | null> {
  return new Promise((resolve) => {
    const stop = watchWallets((wallets) => resolve(wallets[0] ?? null));
    stop();
  });
}

/** BR3.3: how long an outcome ("A proposta expirou. Nada foi enviado.") stays before the banner leaves. */
export const OUTCOME_MS = 5_000;

const OUTCOME_TEXT: Record<SigningOutcome, string> = {
  accepted: text.signing.accepted,
  expired: text.signing.expired,
  refused: text.signing.refused,
  rejectFailed: text.signing.rejectFailed,
  notWaiting: text.signing.notWaiting,
  mismatch: text.errors.SIGNED_MESSAGE_MISMATCH,
  policyRefused: text.errors.SIGNING_POLICY_REFUSED,
  walletCannotSign: text.signing.walletCannotSign,
  noWallet: text.signing.noWallet,
};

function summary(proposal: Proposal): string | null {
  const display = proposal.session?.display;
  if (!display) {
    return null;
  }
  const side = text.signing.sides[display.side] ?? display.side;
  const amount =
    display.side === 'BUY'
      ? formatSolAmount(display.input.baseUnits)
      : formatToken(display.input.baseUnits, display.input.decimals);
  return `${side} · ${amount}`;
}

/**
 * 261113-trading-real-bot U6 (BR3.2 to BR3.6; R4; interaction-spec.md SigningBanner; U6-NFR3.1, U6-NFR3.2, U6-NFR6.3): the
 * banner "Ordem para assinar" on every screen, only in the SIGNED mode while a proposal waits. It counts down on the
 * clock of the server (the difference of clocks by `serverTime` is discounted) every 250 ms, and its live region speaks
 * only at 10 s and 5 s. At zero it says "A proposta expirou. Nada foi enviado." for 5 s and leaves. "Ver" opens the
 * signing dialog, unless the wallet does not sign transactions (BR3.6).
 */
export function SigningBanner({
  mode,
  pickWallet = firstWallet,
}: {
  mode: Mode | null;
  pickWallet?: () => Promise<WalletPort | null>;
}) {
  const { proposal, clear, api } = useSigningSession(mode);
  const [now, setNow] = useState(() => Date.now());
  const [outcome, setOutcome] = useState<SigningOutcome | null>(null);
  const [dialog, setDialog] = useState<{ wallet: WalletPort } | null>(null);
  const [announced, setAnnounced] = useState('');
  const viewButton = useRef<HTMLButtonElement>(null);
  const lastSeconds = useRef<number | null>(null);
  const outcomeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const skew = proposal?.session
    ? clockSkewMs(proposal.session.serverTime, proposal.receivedAtMs)
    : 0;
  const seconds = proposal ? remainingSeconds(proposal.deadline, now, skew) : null;

  const finish = useCallback(
    (result: SigningOutcome) => {
      setDialog(null);
      clear();
      setOutcome(result);
      if (outcomeTimer.current !== null) {
        clearTimeout(outcomeTimer.current);
      }
      outcomeTimer.current = setTimeout(() => {
        outcomeTimer.current = null;
        setOutcome(null);
      }, OUTCOME_MS);
    },
    [clear],
  );

  useEffect(
    () => () => {
      if (outcomeTimer.current !== null) {
        clearTimeout(outcomeTimer.current);
      }
    },
    [],
  );

  // The countdown ticks only while a proposal waits.
  useEffect(() => {
    if (!proposal) {
      lastSeconds.current = null;
      setAnnounced('');
      return undefined;
    }
    setNow(Date.now());
    const tick = setInterval(() => setNow(Date.now()), TICK_MS);
    return () => clearInterval(tick);
  }, [proposal]);

  useEffect(() => {
    if (seconds === null) {
      return;
    }
    const mark = milestoneCrossed(lastSeconds.current, seconds);
    lastSeconds.current = seconds;
    if (mark !== null) {
      setAnnounced(text.signing.milestone(mark));
    }
    if (seconds === 0) {
      finish('expired');
    }
  }, [seconds, finish]);

  async function openDialog() {
    const wallet = await pickWallet();
    if (!wallet) {
      setOutcome('noWallet');
      return;
    }
    if (!wallet.canSignTransaction) {
      setOutcome('walletCannotSign');
      return;
    }
    setOutcome(null);
    setDialog({ wallet });
  }

  if (!proposal && !outcome) {
    return null;
  }

  return (
    <div className="real-signing-banner" data-testid="real-signing-region">
      {proposal && seconds !== null && (
        <div role="region" aria-label={text.signing.bannerTitle} data-testid="real-signing-banner">
          <RealBadge testId="real-signing-badge" />{' '}
          <span>
            {summary(proposal)
              ? text.signing.banner(summary(proposal) as string)
              : text.signing.bannerShort}
          </span>{' '}
          <span data-testid="real-signing-countdown">{formatCountdown(seconds)}</span>{' '}
          <button
            ref={viewButton}
            type="button"
            onClick={() => void openDialog()}
            data-testid="real-signing-view"
          >
            {text.signing.view}
          </button>
        </div>
      )}
      <p aria-live="polite" className="visually-hidden" data-testid="real-signing-live">
        {announced}
      </p>
      {outcome && (
        <p role="status" data-testid="real-signing-outcome">
          {OUTCOME_TEXT[outcome]}
        </p>
      )}
      {dialog && proposal && seconds !== null && (
        <SigningDialog
          api={api}
          proposal={proposal}
          seconds={seconds}
          wallet={dialog.wallet}
          returnFocusTo={viewButton.current}
          onClose={() => setDialog(null)}
          onOutcome={finish}
        />
      )}
    </div>
  );
}
