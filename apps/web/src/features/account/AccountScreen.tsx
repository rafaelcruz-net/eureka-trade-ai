'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { Account } from '@/lib/api/guest-api';
import { formatClock, formatInstant } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';
import { AccountMenu } from '@/features/auth/AccountMenu';
import { useSession } from '@/features/auth/SessionProvider';
import { useRequireSession } from '@/features/auth/useRequireSession';

const text = messages.account;

type Load = { kind: 'loading' } | { kind: 'error' } | { kind: 'ready'; account: Account };

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
 * S-Conta (US1.5; BR5.1 to BR5.4, BR4.4). The wallet is shown abbreviated as read-only text (there is no unlink or
 * change in the beta), with the provider, the absolute end of the session in the browser's time zone and the accepted
 * terms with their version, date and text. REAL mode is a line of text, never a control. "Sair" ends the session.
 */
export function AccountScreen({ timeZone }: { timeZone?: string }) {
  const status = useRequireSession('/conta');
  const api = useApi();
  const { expire } = useSession();
  const heading = useRef<HTMLHeadingElement>(null);
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [showTerms, setShowTerms] = useState(false);

  const fetchAccount = useCallback(async () => {
    setLoad({ kind: 'loading' });
    try {
      setLoad({ kind: 'ready', account: await api.getAccount() });
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        expire();
        return;
      }
      setLoad({ kind: 'error' });
    }
  }, [api, expire]);

  useEffect(() => {
    if (status === 'authenticated') {
      void fetchAccount();
    }
  }, [status, fetchAccount]);
  useEffect(() => heading.current?.focus(), []);

  return (
    <>
      <header className="app-header">
        <span>{messages.app.name}</span>
        <span className="muted">{messages.app.paperOnly}</span>
        <AccountMenu />
      </header>
      <main id="conteudo" aria-labelledby="account-title">
        <h1 id="account-title" ref={heading} tabIndex={-1}>
          {text.title}
        </h1>
        {load.kind === 'loading' && (
          <p role="status" data-testid="account-loading">
            {text.loading}
          </p>
        )}
        {load.kind === 'error' && (
          <div role="alert" className="notice notice-error" data-testid="account-error">
            <h2>{text.errorTitle}</h2>
            <p>{text.errorBody}</p>
            <button
              type="button"
              className="secondary"
              data-testid="account-retry"
              onClick={() => void fetchAccount()}
            >
              {text.retry}
            </button>
          </div>
        )}
        {load.kind === 'ready' && (
          <dl data-testid="account-details">
            <dt>{text.wallet}</dt>
            <dd>
              <span data-testid="account-wallet">{load.account.walletAddressMasked}</span>
              <p className="muted">{text.walletHelp}</p>
            </dd>
            <dt>{text.provider}</dt>
            <dd data-testid="account-provider">
              {text.providers[load.account.provider] ?? load.account.provider}
            </dd>
            <dt>{text.session}</dt>
            <dd data-testid="account-session">
              {text.sessionEnds(formatClock(new Date(load.account.sessionExpiresAt), timeZone))}
            </dd>
            <dt>{text.terms}</dt>
            <dd>
              {load.account.acceptedTerms ? (
                <>
                  <span data-testid="account-terms-version">
                    {text.termsVersion(load.account.acceptedTerms.version)}
                  </span>{' '}
                  <button
                    type="button"
                    className="secondary"
                    aria-expanded={showTerms}
                    aria-controls="account-terms-detail"
                    data-testid="account-terms-toggle"
                    onClick={() => setShowTerms((shown) => !shown)}
                  >
                    {showTerms ? text.hideTerms : text.viewTerms}
                  </button>
                  {showTerms && (
                    <div id="account-terms-detail" data-testid="account-terms-detail">
                      <p data-testid="account-terms-accepted-at">
                        {text.acceptedAt(
                          formatInstant(load.account.acceptedTerms.acceptedAt, timeZone),
                        )}
                      </p>
                      <h2>{text.termsTextHeading}</h2>
                      {load.account.acceptedTerms.termsText ? (
                        <Paragraphs
                          value={load.account.acceptedTerms.termsText}
                          testId="account-terms-text"
                        />
                      ) : (
                        <p>{text.textUnavailable}</p>
                      )}
                      <h2>{text.riskTextHeading}</h2>
                      {load.account.acceptedTerms.riskNoticeText ? (
                        <Paragraphs
                          value={load.account.acceptedTerms.riskNoticeText}
                          testId="account-risk-text"
                        />
                      ) : (
                        <p>{text.textUnavailable}</p>
                      )}
                    </div>
                  )}
                </>
              ) : (
                <span data-testid="account-no-terms">{text.noTerms}</span>
              )}
            </dd>
            <dt className="visually-hidden">{text.realMode}</dt>
            <dd data-testid="account-real-mode">{text.realMode}</dd>
          </dl>
        )}
      </main>
    </>
  );
}
