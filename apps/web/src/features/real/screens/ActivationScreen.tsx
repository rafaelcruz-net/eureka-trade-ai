'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useQuietApi } from '@/lib/api/ApiProvider';
import type { CurrentLegalDocuments } from '@/lib/api/guest-api';
import { encodeBase58 } from '@/lib/base58';
import { AppShell } from '@/components/shell/AppShell';
import { useRequireSession } from '@/features/auth/useRequireSession';
import type { WalletPort } from '@/features/auth/wallets';
import {
  realCall,
  realReads,
  toBotWallet,
  toRealError,
  type BotWallet,
  type RealError,
} from '@/features/real/api';
import { useLoad } from '@/features/real/hooks/useLoad';
import { useMoneyAction } from '@/features/real/hooks/useMoneyAction';
import { formatSolAmount, MAX_CAP_LAMPORTS, parseSol } from '@/features/real/money';
import { realMessages as text } from '@/features/real/messages';
import { isRealUnavailable } from '@/features/real/components/RealArea';
import { MoneyConfirmDialog } from '@/features/real/components/MoneyConfirmDialog';
import { RealBadge } from '@/features/real/components/RealBadge';
import { RealErrorNotice } from '@/features/real/components/RealErrorNotice';
import { ReleaseStatus } from '@/features/real/components/ReleaseStatus';
import { SolAmountInput } from '@/features/real/components/SolAmountInput';
import { firstWallet } from '@/features/real/components/SigningBanner';
import { updateSessionReal } from '@/features/real/session-real';

const copy = text.activation;
const TITLE_ID = 'real-activation-title';

type Step =
  | { kind: 'loading' }
  | { kind: 'unavailable' }
  | { kind: 'error'; error: RealError; retry: () => void }
  | { kind: 'release' }
  | { kind: 'terms' }
  | { kind: 'consent' }
  | { kind: 'done'; wallet: BotWallet };

/**
 * 261113-trading-real-bot U6, R1 `/real/ativacao` (US1.1, US1.2; BR1.2, BR2.1 to BR2.5, BR6.1 to BR6.3; S2 to S7, S43):
 *
 * 1. Without an effective release it shows the state of the release instead of the steps (BR2.5).
 * 2. Without the acceptance of the current terms: step 1, the text of the current version and the box; "Continuar"
 *    stays disabled with the reason in text until the box is marked; the acceptance goes through the existing route.
 * 3. Step 2: the initial cap (1 SOL by default, at most 10), the confirmation, the challenge ACTIVATE signed by the main
 *    wallet with `signMessage`, unchanged, and the activation with the Idempotency-Key of that exact body.
 * 4. Step 3: the address with "Copiar" and "Ir para Depósito e saque".
 *
 * It reads through the API without the handler of the reacceptance lock, because step 1 IS the acceptance; with an
 * active wallet it goes to `/real` (BR1.2).
 */
export function ActivationScreen({
  pickWallet = firstWallet,
  timeZone,
  newKey,
}: {
  pickWallet?: () => Promise<WalletPort | null>;
  timeZone?: string;
  newKey?: () => string;
}) {
  const status = useRequireSession('/real/ativacao');
  const api = useQuietApi();
  const reads = useMemo(() => realReads(api), [api]);
  const router = useRouter();
  const authenticated = status === 'authenticated';
  const release = useLoad(reads.release, authenticated);
  const releaseActive =
    release.load.kind === 'ready' && release.load.data.release.state === 'ACTIVE';
  const wallet = useLoad(reads.wallet, authenticated && releaseActive);
  const [done, setDone] = useState<BotWallet | null>(null);
  const hasWallet = wallet.load.kind === 'ready' && done === null;

  useEffect(() => {
    if (hasWallet) {
      router.replace('/real');
    }
  }, [hasWallet, router]);

  const step = ((): Step => {
    if (done) {
      return { kind: 'done', wallet: done };
    }
    if (release.load.kind === 'loading') {
      return { kind: 'loading' };
    }
    if (release.load.kind === 'error') {
      const { error } = release.load;
      if (error.code === 'TERMS_REACCEPTANCE_REQUIRED') {
        return { kind: 'terms' };
      }
      if (isRealUnavailable(error.code, error.status)) {
        return { kind: 'unavailable' };
      }
      return { kind: 'error', error, retry: release.reload };
    }
    if (!releaseActive) {
      return { kind: 'release' };
    }
    if (wallet.load.kind === 'loading' || wallet.load.kind === 'ready') {
      return { kind: 'loading' };
    }
    if (wallet.load.error.status === 404) {
      return { kind: 'consent' };
    }
    return { kind: 'error', error: wallet.load.error, retry: wallet.reload };
  })();

  return (
    <AppShell current="real" titleId={TITLE_ID} className="real-area">
      <h1 id={TITLE_ID}>
        {copy.title} <RealBadge testId="real-activation-badge" />
      </h1>
      {step.kind === 'loading' && (
        <p role="status" data-testid="real-loading">
          {text.loading}
        </p>
      )}
      {step.kind === 'unavailable' && (
        <p role="status" className="notice" data-testid="real-unavailable">
          {text.unavailable}
        </p>
      )}
      {step.kind === 'error' && (
        <RealErrorNotice error={step.error} onRetry={step.retry} testId="real-activation-error" />
      )}
      {step.kind === 'release' && release.load.kind === 'ready' && (
        <ReleaseStatus release={release.load.data.release} timeZone={timeZone} />
      )}
      {step.kind === 'terms' && <TermsStep onAccepted={release.reload} newKey={newKey} />}
      {step.kind === 'consent' && (
        <ConsentStep
          pickWallet={pickWallet}
          newKey={newKey}
          onActivated={(activated) => {
            updateSessionReal({ available: true, mode: activated.mode });
            setDone(activated);
          }}
        />
      )}
      {step.kind === 'done' && <DoneStep wallet={step.wallet} />}
    </AppShell>
  );
}

/** Step 1 (BR2.1, BR2.2, S4, S5): the text of the current terms, the box and the acceptance. */
function TermsStep({ onAccepted, newKey }: { onAccepted: () => void; newKey?: () => string }) {
  const api = useQuietApi();
  const [documents, setDocuments] = useState<CurrentLegalDocuments | null>(null);
  const [loadError, setLoadError] = useState<RealError | null>(null);
  const [checked, setChecked] = useState(false);
  const accept = useMoneyAction(
    (version: string, key: string) => realCall(() => api.acceptTerms(version, key)),
    { newKey },
  );

  const load = useCallback(() => {
    setLoadError(null);
    api
      .getCurrentLegalDocuments()
      .then(setDocuments, (failure: unknown) => setLoadError(toRealError(failure)));
  }, [api]);
  useEffect(load, [load]);

  async function submit() {
    if (documents && (await accept.submit(documents.version))) {
      onAccepted();
    }
  }

  return (
    <section aria-labelledby="real-terms-title" data-testid="real-activation-terms">
      <p className="muted">{copy.step(1)}</p>
      <h2 id="real-terms-title">{copy.termsTitle}</h2>
      {!documents && !loadError && <p role="status">{copy.termsLoading}</p>}
      {loadError && (
        <RealErrorNotice
          error={loadError}
          message={copy.termsError}
          onRetry={load}
          testId="real-terms-load-error"
        />
      )}
      {documents && (
        <div className="real-terms-text" tabIndex={0} data-testid="real-terms-text">
          {documents.terms.sections.map((section) => (
            <section key={section.id}>
              <h3>{section.title}</h3>
              {section.paragraphs.map((paragraph, index) => (
                <p key={index}>{paragraph}</p>
              ))}
            </section>
          ))}
        </div>
      )}
      <p>
        <input
          id="real-terms-box"
          type="checkbox"
          checked={checked}
          disabled={!documents || accept.sending}
          onChange={(event) => setChecked(event.target.checked)}
          data-testid="real-terms-box"
        />{' '}
        <label htmlFor="real-terms-box">{copy.termsBox}</label>
      </p>
      {!checked && (
        <p className="muted" id="real-terms-reason" data-testid="real-terms-reason">
          {copy.continueDisabled}
        </p>
      )}
      {accept.state.kind === 'error' && (
        <RealErrorNotice
          error={accept.state.error}
          message={copy.termsFailed}
          onRetry={() => void accept.retry().then((result) => result && onAccepted())}
          testId="real-terms-error"
        />
      )}
      <button
        type="button"
        disabled={!checked || !documents || accept.sending}
        aria-describedby={checked ? undefined : 'real-terms-reason'}
        onClick={() => void submit()}
        data-testid="real-terms-continue"
      >
        {accept.sending ? text.sending : copy.continue}
      </button>
    </section>
  );
}

/** Step 2 (BR2.4, BR5.1, BR5.2, BR6.1 to BR6.3, S7): the initial cap and the consent ACTIVATE signed by the main wallet. */
function ConsentStep({
  pickWallet,
  newKey,
  onActivated,
}: {
  pickWallet: () => Promise<WalletPort | null>;
  newKey?: () => string;
  onActivated: (wallet: BotWallet) => void;
}) {
  const api = useQuietApi();
  const [cap, setCap] = useState('1');
  const [confirming, setConfirming] = useState(false);
  const [signing, setSigning] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const opener = useRef<HTMLButtonElement>(null);
  const parsed = parseSol(cap, { max: MAX_CAP_LAMPORTS });
  const capError = parsed.ok ? null : (text.cap.invalid[parsed.reason] ?? null);
  const activate = useMoneyAction(
    (body: { challengeId: string; signature: string }, key: string) =>
      realCall(() => api.activateBotWallet(body, key)).then(toBotWallet),
    { newKey },
  );

  async function confirm() {
    if (!parsed.ok || signing || activate.sending) {
      return;
    }
    setProblem(null);
    const wallet = await pickWallet();
    if (!wallet) {
      setProblem(copy.noWallet);
      return;
    }
    setSigning(true);
    let body: { challengeId: string; signature: string };
    try {
      await wallet.connect();
      const challenge = await realCall(() =>
        api.requestRealChallenge({ kind: 'ACTIVATE', capLamports: parsed.lamports.toString() }),
      );
      const signature = await wallet.signMessage(new TextEncoder().encode(challenge.message ?? ''));
      body = { challengeId: challenge.challengeId ?? '', signature: encodeBase58(signature) };
    } catch (failure) {
      setSigning(false);
      setProblem(
        failure instanceof Error && failure.name === 'RealError'
          ? toRealError(failure).text
          : copy.walletRefused,
      );
      return;
    }
    setSigning(false);
    finished(await activate.submit(body));
  }

  function finished(activated: BotWallet | null) {
    if (activated) {
      setConfirming(false);
      onActivated(activated);
    }
  }

  return (
    <section aria-labelledby="real-consent-title" data-testid="real-activation-consent">
      <p className="muted">{copy.step(2)}</p>
      <h2 id="real-consent-title">{copy.consentTitle}</h2>
      <p>{copy.consentIntro}</p>
      <p>
        <strong>{copy.consentNotLogin}</strong>
      </p>
      <SolAmountInput
        id="real-activation-cap"
        label={copy.capLabel}
        value={cap}
        onChange={setCap}
        error={capError}
        testId="real-activation-cap"
      />
      {problem && !confirming && (
        <p role="alert" data-testid="real-activation-problem">
          {problem}
        </p>
      )}
      <button
        ref={opener}
        type="button"
        disabled={!parsed.ok}
        onClick={() => setConfirming(true)}
        data-testid="real-activation-sign"
      >
        {copy.sign}
      </button>
      {confirming && parsed.ok && (
        <MoneyConfirmDialog
          title={copy.confirmTitle}
          amount={formatSolAmount(parsed.lamports)}
          effect={copy.confirmEffect(formatSolAmount(parsed.lamports))}
          action={{ ...activate, sending: activate.sending || signing }}
          onConfirm={() => void confirm()}
          onClose={() => {
            activate.reset();
            setProblem(null);
            setConfirming(false);
          }}
          returnFocusTo={opener.current}
          notice={problem}
          onRetry={() => void activate.retry().then(finished)}
          testId="real-activation-confirm"
        />
      )}
    </section>
  );
}

/** Step 3 (S7): the address of the bot wallet with "Copiar" and the way to the deposit. */
function DoneStep({ wallet }: { wallet: BotWallet }) {
  const [copied, setCopied] = useState<string | null>(null);
  async function copyAddress() {
    try {
      await navigator.clipboard.writeText(wallet.address);
      setCopied(text.copied);
    } catch {
      setCopied(text.copyFailed);
    }
  }
  return (
    <section aria-labelledby="real-done-title" data-testid="real-activation-done">
      <p className="muted">{copy.step(3)}</p>
      <h2 id="real-done-title">{copy.doneTitle}</h2>
      <p>
        {copy.address}:{' '}
        <span className="mono" data-testid="real-activation-address">
          {wallet.address}
        </span>{' '}
        <button
          type="button"
          className="secondary"
          onClick={() => void copyAddress()}
          data-testid="real-activation-copy"
        >
          {text.copy}
        </button>
      </p>
      {copied && <p role="status">{copied}</p>}
      <p>{copy.next}</p>
      <Link href="/real/deposito-saque" data-testid="real-activation-deposit-link">
        {copy.goDeposit}
      </Link>
    </section>
  );
}
