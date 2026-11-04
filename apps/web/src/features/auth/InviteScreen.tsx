'use client';

import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { InviteLookup } from '@/lib/api/guest-api';
import { formatInstant } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';
import {
  clearAcceptance,
  readAcceptance,
  writeAcceptance,
  type Acceptance,
} from '@/features/auth/acceptance-store';
import { rejectionKey, signInWithInvite } from '@/features/auth/login-flow';
import { HOME } from '@/features/auth/origin';
import { useSession } from '@/features/auth/SessionProvider';
import { watchWallets, type WalletPort } from '@/features/auth/wallets';

const text = messages.invite;

export const PHANTOM_DOWNLOAD = 'https://phantom.app/download';
export const SOLFLARE_DOWNLOAD = 'https://solflare.com/download';

type Load =
  | { kind: 'loading' }
  | { kind: 'missing' }
  | { kind: 'notFound' }
  | { kind: 'rateLimited'; seconds?: number }
  | { kind: 'error' }
  | { kind: 'invalid'; status: string }
  | { kind: 'ready'; invite: InviteLookup };

type Flow =
  | { kind: 'idle' }
  | { kind: 'signing' }
  | { kind: 'rejected'; key: string }
  | { kind: 'linked' };

/** Plain-text paragraphs: the terms are never rendered as HTML (tech-stack-decisions.md). */
function Paragraphs({ value, testId }: { value: string; testId: string }) {
  return (
    <div data-testid={testId}>
      {value
        .split(/\n\s*\n/)
        .map((paragraph) => paragraph.trim())
        .filter((paragraph) => paragraph.length > 0)
        .map((paragraph, index) => (
          <p key={index}>{paragraph}</p>
        ))}
    </div>
  );
}

/**
 * Entry with an invitation (US1.1; BR3.1, BR6.1, BR6.2; F4, F5). Shows the terms and the risk notice, keeps the two
 * marks in the browser session, and only then lets the person connect Phantom or Solflare; the account is created by
 * the verification with the code and the accepted version. Loading, technical error and every refusal are distinct
 * states, always in text. No field ever asks for a secret phrase or a private key; the code is never logged.
 */
export function InviteScreen({
  code,
  walletSource = watchWallets,
  timeZone,
}: {
  code?: string;
  walletSource?: typeof watchWallets;
  timeZone?: string;
}) {
  const api = useApi();
  const session = useSession();
  const router = useRouter();
  const heading = useRef<HTMLHeadingElement>(null);
  const [load, setLoad] = useState<Load>(code ? { kind: 'loading' } : { kind: 'missing' });
  const [flow, setFlow] = useState<Flow>({ kind: 'idle' });
  const [acceptance, setAcceptance] = useState<Acceptance>({ terms: false, risk: false });
  const [termsChanged, setTermsChanged] = useState(false);
  const [wallets, setWallets] = useState<WalletPort[]>([]);
  const [typed, setTyped] = useState('');

  useEffect(() => walletSource(setWallets), [walletSource]);
  useEffect(() => heading.current?.focus(), []);

  const fetchInvite = useCallback(async () => {
    if (!code) {
      return;
    }
    setLoad({ kind: 'loading' });
    try {
      const invite = await api.lookupInvite(code);
      if (invite.status !== 'PENDING') {
        setLoad({ kind: 'invalid', status: invite.status });
        return;
      }
      setAcceptance(readAcceptance(invite.termsVersion));
      setLoad({ kind: 'ready', invite });
    } catch (failure) {
      setLoad(loadFailure(failure));
    }
  }, [api, code]);

  useEffect(() => {
    void fetchInvite();
  }, [fetchInvite]);

  function mark(change: Partial<Acceptance>) {
    if (load.kind !== 'ready') {
      return;
    }
    const next = { ...acceptance, ...change };
    setAcceptance(next);
    writeAcceptance(load.invite.termsVersion, next);
  }

  async function connect(wallet: WalletPort) {
    if (load.kind !== 'ready' || !code) {
      return;
    }
    setFlow({ kind: 'signing' });
    try {
      const opened = await signInWithInvite(wallet, api, {
        code,
        termsVersion: load.invite.termsVersion,
      });
      clearAcceptance();
      session.signIn(opened);
      router.replace(HOME);
    } catch (failure) {
      await handleRefusal(failure);
    }
  }

  async function handleRefusal(failure: unknown) {
    if (failure instanceof ApiError && failure.code === 'WALLET_ALREADY_LINKED') {
      setFlow({ kind: 'linked' });
      return;
    }
    if (failure instanceof ApiError && failure.code === 'INVITE_NOT_FOUND') {
      setFlow({ kind: 'idle' });
      setLoad({ kind: 'notFound' });
      return;
    }
    if (
      failure instanceof ApiError &&
      failure.code === 'INVITE_INVALID' &&
      failure.reason !== 'TENANT_INACTIVE'
    ) {
      setFlow({ kind: 'idle' });
      setLoad({ kind: 'invalid', status: failure.reason ?? 'USED' });
      return;
    }
    if (failure instanceof ApiError && failure.code === 'TERMS_NOT_ACCEPTED') {
      clearAcceptance();
      setFlow({ kind: 'idle' });
      setTermsChanged(true);
      await fetchInvite();
      return;
    }
    setFlow({ kind: 'rejected', key: rejectionKey(failure) });
  }

  function submitCode(event: FormEvent) {
    event.preventDefault();
    const value = typed.trim();
    if (value) {
      router.push(`/convite/${encodeURIComponent(value)}`);
    }
  }

  return (
    <main id="conteudo" aria-labelledby="invite-title">
      <h1 id="invite-title" ref={heading} tabIndex={-1}>
        {text.title}
      </h1>
      <p className="muted">{text.neverAskSecret}</p>
      {load.kind === 'loading' && (
        <p role="status" data-testid="invite-loading">
          {text.loading}
        </p>
      )}
      {load.kind === 'missing' && (
        <form onSubmit={submitCode} data-testid="invite-code-form">
          <label htmlFor="invite-code">{text.codeLabel}</label>
          <p className="muted" id="invite-code-help">
            {text.codeHelp}
          </p>
          <input
            id="invite-code"
            type="text"
            autoComplete="off"
            aria-describedby="invite-code-help"
            data-testid="invite-code-input"
            value={typed}
            onChange={(event) => setTyped(event.target.value)}
          />
          <button type="submit" data-testid="invite-code-submit">
            {text.codeSubmit}
          </button>
        </form>
      )}
      {load.kind === 'notFound' && (
        <p role="alert" className="notice notice-error" data-testid="invite-not-found">
          {text.notFound}
        </p>
      )}
      {load.kind === 'rateLimited' && (
        <p role="alert" className="notice notice-warning" data-testid="invite-rate-limited">
          {load.seconds ? text.rateLimited(load.seconds) : text.rateLimitedNoWait}
        </p>
      )}
      {load.kind === 'error' && (
        <div role="alert" className="notice notice-error" data-testid="invite-error">
          <h2>{text.errorTitle}</h2>
          <p>{text.errorBody}</p>
          <button
            type="button"
            className="secondary"
            data-testid="invite-retry"
            onClick={() => void fetchInvite()}
          >
            {text.retry}
          </button>
        </div>
      )}
      {load.kind === 'invalid' && (
        <div role="alert" className="notice notice-warning" data-testid="invite-status">
          <p>{text.status[load.status] ?? text.status.USED}</p>
          <p>{text.askAnother}</p>
        </div>
      )}
      {load.kind === 'ready' && (
        <ReadyInvite
          invite={load.invite}
          acceptance={acceptance}
          wallets={wallets}
          flow={flow}
          termsChanged={termsChanged}
          timeZone={timeZone}
          onMark={mark}
          onConnect={(wallet) => void connect(wallet)}
          onRetry={() => setFlow({ kind: 'idle' })}
        />
      )}
    </main>
  );
}

function loadFailure(failure: unknown): Load {
  if (failure instanceof ApiError && failure.status === 404) {
    return { kind: 'notFound' };
  }
  if (failure instanceof ApiError && failure.status === 429) {
    return { kind: 'rateLimited', seconds: failure.retryAfterSeconds };
  }
  return { kind: 'error' };
}

function ReadyInvite({
  invite,
  acceptance,
  wallets,
  flow,
  termsChanged,
  timeZone,
  onMark,
  onConnect,
  onRetry,
}: {
  invite: InviteLookup;
  acceptance: Acceptance;
  wallets: WalletPort[];
  flow: Flow;
  termsChanged: boolean;
  timeZone?: string;
  onMark: (change: Partial<Acceptance>) => void;
  onConnect: (wallet: WalletPort) => void;
  onRetry: () => void;
}) {
  const accepted = acceptance.terms && acceptance.risk;
  const busy = flow.kind === 'signing';
  return (
    <>
      <p>{text.intro}</p>
      <p className="muted" data-testid="invite-valid-until">
        {text.validUntil(formatInstant(invite.expiresAt, timeZone))}
      </p>
      {termsChanged && (
        <p role="alert" className="notice notice-warning" data-testid="invite-terms-changed">
          {text.termsChanged}
        </p>
      )}
      <section aria-labelledby="invite-terms-heading">
        <h2 id="invite-terms-heading">{text.termsHeading(invite.termsVersion)}</h2>
        <Paragraphs value={invite.termsText} testId="invite-terms" />
      </section>
      <section aria-labelledby="invite-risk-heading">
        <h2 id="invite-risk-heading">{text.riskHeading}</h2>
        <Paragraphs value={invite.riskNoticeText} testId="invite-risk" />
      </section>
      <fieldset>
        <div>
          <input
            id="invite-accept-terms"
            type="checkbox"
            data-testid="invite-accept-terms"
            checked={acceptance.terms}
            onChange={(event) => onMark({ terms: event.target.checked })}
          />
          <label htmlFor="invite-accept-terms">{text.acceptTerms}</label>
        </div>
        <div>
          <input
            id="invite-accept-risk"
            type="checkbox"
            data-testid="invite-accept-risk"
            checked={acceptance.risk}
            onChange={(event) => onMark({ risk: event.target.checked })}
          />
          <label htmlFor="invite-accept-risk">{text.acceptRisk}</label>
        </div>
        <p className="muted">{text.acceptancesKept}</p>
      </fieldset>

      {wallets.length === 0 ? (
        <section role="status" className="notice" data-testid="invite-no-wallet">
          <p>{text.noWallet}</p>
          <p>
            <a
              href={PHANTOM_DOWNLOAD}
              rel="noopener noreferrer"
              data-testid="invite-install-phantom"
            >
              {text.installPhantom}
            </a>
            {' · '}
            <a
              href={SOLFLARE_DOWNLOAD}
              rel="noopener noreferrer"
              data-testid="invite-install-solflare"
            >
              {text.installSolflare}
            </a>
          </p>
          <p className="muted">{text.installHelp}</p>
        </section>
      ) : !accepted ? (
        <div>
          <button
            type="button"
            disabled
            aria-describedby="invite-connect-reason"
            data-testid="invite-connect-disabled"
          >
            {text.connectWallet}
          </button>
          <p id="invite-connect-reason" data-testid="invite-connect-reason">
            {text.connectUnavailable}
          </p>
        </div>
      ) : (
        <ul aria-label={text.connectWallet} data-testid="invite-wallet-list">
          {wallets.map((wallet) => (
            <li key={wallet.name}>
              <button
                type="button"
                data-testid={`invite-connect-${wallet.provider.toLowerCase()}`}
                disabled={busy}
                onClick={() => onConnect(wallet)}
              >
                {text.connectWith(wallet.name)}
              </button>
            </li>
          ))}
        </ul>
      )}

      {flow.kind === 'signing' && (
        <p role="status" data-testid="invite-signing">
          {text.signing}
        </p>
      )}
      {flow.kind === 'linked' && (
        <div role="alert" className="notice notice-warning" data-testid="invite-wallet-linked">
          <p>{text.walletLinked}</p>
          <a href="/entrar" data-testid="invite-go-to-entry">
            {text.goToEntry}
          </a>
        </div>
      )}
      {flow.kind === 'rejected' && (
        <div role="alert" className="notice notice-error" data-testid="invite-rejected">
          <h2>{text.rejectedTitle}</h2>
          <p data-testid="invite-rejected-reason">
            {text.rejected[flow.key] ?? text.rejected.GENERIC}
          </p>
          <p className="muted">{text.tryAgainHelp}</p>
          <button
            type="button"
            className="secondary"
            data-testid="invite-try-again"
            onClick={onRetry}
          >
            {text.retry}
          </button>
        </div>
      )}
    </>
  );
}
