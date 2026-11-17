'use client';

import { useRef, useState } from 'react';
import type { GuestApi } from '@/lib/api/guest-api';
import { encodeBase58 } from '@/lib/base58';
import type { WalletPort } from '@/features/auth/wallets';
import { MODES, realCall, toRealError, type BotWallet, type Mode } from '@/features/real/api';
import { useMoneyAction } from '@/features/real/hooks/useMoneyAction';
import { realMessages as text } from '@/features/real/messages';
import { ModeSelector } from '@/features/real/components/ModeSelector';
import { MoneyConfirmDialog } from '@/features/real/components/MoneyConfirmDialog';
import { PauseBanner } from '@/features/real/components/PauseBanner';
import { RealArea } from '@/features/real/components/RealArea';
import { RealErrorNotice } from '@/features/real/components/RealErrorNotice';
import { firstWallet } from '@/features/real/components/SigningBanner';
import { updateSessionReal } from '@/features/real/session-real';

/** A mode above another one opens more risk and needs the consent (BR4.2). */
export function isRaise(from: Mode, to: Mode): boolean {
  return MODES.indexOf(to) > MODES.indexOf(from);
}

type ModeBody = { mode: Mode; challengeId?: string; signature?: string };

/**
 * 261113-trading-real-bot U6, R3 `/real/modo` (US3.1; BR4.1 to BR4.4; S17 to S20, S40): raising the mode opens the
 * confirmation and goes through the challenge RAISE_MODE, the signature with `signMessage` and the `PUT /mode`; going
 * back to SIGNED asks only the confirmation that says the orders not sent yet are cancelled, and takes effect at once.
 * The refusals of the server are in text. After `422 CONSENT_EXPIRED` a new confirmation asks a new challenge, a new
 * signature and so a NEW Idempotency-Key (BR6.2).
 */
export function ModeScreen({
  pickWallet = firstWallet,
  newKey,
}: {
  pickWallet?: () => Promise<WalletPort | null>;
  newKey?: () => string;
}) {
  return (
    <RealArea origin="/real/modo" title={text.mode.title} titleId="real-mode-title">
      {({ wallet, api, setWallet }) => (
        <>
          <PauseBanner reason={wallet.pausedReason} />
          <ModeForm
            wallet={wallet}
            api={api}
            pickWallet={pickWallet}
            newKey={newKey}
            onSaved={setWallet}
          />
        </>
      )}
    </RealArea>
  );
}

function ModeForm({
  wallet,
  api,
  pickWallet,
  newKey,
  onSaved,
}: {
  wallet: BotWallet;
  api: GuestApi;
  pickWallet: () => Promise<WalletPort | null>;
  newKey?: () => string;
  onSaved: (wallet: BotWallet) => void;
}) {
  const [choice, setChoice] = useState<Mode>(wallet.mode);
  const [confirming, setConfirming] = useState(false);
  const [signing, setSigning] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const opener = useRef<HTMLButtonElement>(null);
  const change = useMoneyAction(
    (body: ModeBody, key: string) => realCall(() => api.changeRealMode(body, key)),
    { newKey },
  );
  const raising = isRaise(wallet.mode, choice);
  const name = (mode: Mode) => text.mode.options[mode]?.name ?? mode;

  async function confirm() {
    if (signing || change.sending) {
      return;
    }
    setProblem(null);
    let body: ModeBody = { mode: choice };
    if (raising) {
      const main = await pickWallet();
      if (!main) {
        setProblem(text.activation.noWallet);
        return;
      }
      setSigning(true);
      try {
        await main.connect();
        const challenge = await realCall(() =>
          api.requestRealChallenge({ kind: 'RAISE_MODE', mode: choice }),
        );
        const signature = await main.signMessage(new TextEncoder().encode(challenge.message ?? ''));
        body = {
          mode: choice,
          challengeId: challenge.challengeId ?? '',
          signature: encodeBase58(signature),
        };
      } catch (failure) {
        setSigning(false);
        setProblem(
          failure instanceof Error && failure.name === 'RealError'
            ? toRealError(failure).text
            : text.walletRefused,
        );
        return;
      }
      setSigning(false);
    }
    done(await change.submit(body));
  }

  function done(result: { mode: Mode } | null) {
    if (result) {
      setConfirming(false);
      onSaved({ ...wallet, mode: result.mode });
      updateSessionReal({ available: true, mode: result.mode });
      setNotice(text.mode.saved(name(result.mode)));
    }
  }

  return (
    <section data-testid="real-mode-form">
      <ModeSelector
        current={wallet.mode}
        value={choice}
        onChange={(mode) => {
          setChoice(mode);
          setNotice(null);
        }}
      />
      {choice === wallet.mode && (
        <p className="muted" id="real-mode-unchanged">
          {text.mode.unchanged}
        </p>
      )}
      <button
        ref={opener}
        type="button"
        disabled={choice === wallet.mode}
        aria-describedby={choice === wallet.mode ? 'real-mode-unchanged' : undefined}
        onClick={() => setConfirming(true)}
        data-testid="real-mode-save"
      >
        {text.mode.save}
      </button>
      {notice && (
        <p role="status" data-testid="real-mode-notice">
          {notice}
        </p>
      )}
      {problem && !confirming && (
        <p role="alert" data-testid="real-mode-problem">
          {problem}
        </p>
      )}
      {!confirming && change.state.kind === 'error' && (
        <RealErrorNotice error={change.state.error} testId="real-mode-error" />
      )}
      {confirming && (
        <MoneyConfirmDialog
          title={raising ? text.mode.raiseTitle : text.mode.lowerTitle}
          effect={raising ? text.mode.raiseEffect(name(choice)) : text.mode.lowerEffect}
          action={{ ...change, sending: change.sending || signing }}
          onConfirm={() => void confirm()}
          onClose={() => {
            change.reset();
            setProblem(null);
            setConfirming(false);
          }}
          returnFocusTo={opener.current}
          notice={problem}
          onRetry={() => void change.retry().then(done)}
          testId="real-mode-confirm"
        />
      )}
    </section>
  );
}
