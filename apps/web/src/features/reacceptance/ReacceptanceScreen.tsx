'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { CurrentLegalDocuments } from '@/lib/api/guest-api';
import { SessionSiteFooter } from '@/components/shell/SiteFooter';
import { useSession } from '@/features/auth/SessionProvider';
import { useRequireSession } from '@/features/auth/useRequireSession';
import { LegalSummary } from '@/features/reacceptance/LegalSummary';
import { reacceptanceMessages } from '@/features/reacceptance/messages';
import {
  hasFullConsent,
  NO_CONSENT,
  TermsConsentBoxes,
  type Consent,
} from '@/features/reacceptance/TermsConsentBoxes';
import { REACCEPTANCE_PATH, safeReturnPath } from '@/features/reacceptance/return-path';

const text = reacceptanceMessages;

/** The states of T7: loading, error, ready, submitting; a 409 brings the screen back to loading with a notice. */
type View =
  | { kind: 'loading' }
  | { kind: 'error' }
  | { kind: 'ready'; documents: CurrentLegalDocuments }
  | { kind: 'submitting'; documents: CurrentLegalDocuments };

type Notice = 'outdated' | 'failed' | null;

/**
 * T7 `/novo-aceite` (F3; BR3.7, BR3.9; NFR2.4, NFR4.1, NFR4.2; US4.1, US4.2). Outside the AppShell with navigation:
 * the person can only read, accept or leave. Shows the summary of the effective current version and the links to the
 * full pages in another tab, the two boxes and "Aceitar e continuar", disabled with the reason in text until both are
 * marked. The boxes are disabled while the documents load, when they fail and while the acceptance is sent.
 *
 * - `200`: back to `returnTo` (already a safe internal path; checked again here);
 * - `409 TERMS_VERSION_OUTDATED`: the documents are loaded again, the boxes unmarked and the change explained;
 * - any other failure: the error in text, the boxes kept.
 *
 * The Idempotency-Key is generated once per submission. Every state is announced in an `aria-live="polite"` region.
 */
export function ReacceptanceScreen({
  returnTo,
  initialDocuments,
  newKey = () => crypto.randomUUID(),
}: {
  returnTo: string;
  initialDocuments?: CurrentLegalDocuments | null;
  newKey?: () => string;
}) {
  const status = useRequireSession(
    `${REACCEPTANCE_PATH}?${new URLSearchParams({ voltar: returnTo })}`,
  );
  const api = useApi();
  const { expire } = useSession();
  const router = useRouter();
  const heading = useRef<HTMLHeadingElement>(null);
  const [view, setView] = useState<View>(
    initialDocuments ? { kind: 'ready', documents: initialDocuments } : { kind: 'loading' },
  );
  const [consent, setConsent] = useState<Consent>(NO_CONSENT);
  const [notice, setNotice] = useState<Notice>(null);

  const loadDocuments = useCallback(async () => {
    setView({ kind: 'loading' });
    try {
      setView({ kind: 'ready', documents: await api.getCurrentLegalDocuments() });
    } catch (failure) {
      console.warn(
        'Legal documents request failed',
        failure instanceof ApiError ? failure.status : 'unknown',
      );
      setView({ kind: 'error' });
    }
  }, [api]);

  useEffect(() => {
    if (!initialDocuments) {
      void loadDocuments();
    }
  }, [initialDocuments, loadDocuments]);
  useEffect(() => heading.current?.focus(), []);

  async function accept() {
    if (view.kind !== 'ready' || !hasFullConsent(consent)) {
      return;
    }
    setNotice(null);
    setView({ kind: 'submitting', documents: view.documents });
    try {
      await api.acceptTerms(view.documents.version, newKey());
      router.replace(safeReturnPath(returnTo));
    } catch (failure) {
      await refused(failure, view.documents);
    }
  }

  async function refused(failure: unknown, documents: CurrentLegalDocuments) {
    if (failure instanceof ApiError && failure.code === 'TERMS_VERSION_OUTDATED') {
      setConsent(NO_CONSENT);
      setNotice('outdated');
      await loadDocuments();
      return;
    }
    if (failure instanceof ApiError && failure.status === 401) {
      expire();
      return;
    }
    setNotice('failed');
    setView({ kind: 'ready', documents });
  }

  const documents = view.kind === 'ready' || view.kind === 'submitting' ? view.documents : null;
  const boxesDisabled = view.kind !== 'ready';
  const canAccept = view.kind === 'ready' && hasFullConsent(consent) && status === 'authenticated';
  const reason =
    view.kind === 'ready' || view.kind === 'submitting' ? text.bothRequired : text.waitDocuments;

  return (
    <div className="reacceptance-page" data-testid="reacceptance-page">
      <main id="conteudo" aria-labelledby="reacceptance-title">
        <h1 id="reacceptance-title" ref={heading} tabIndex={-1}>
          {text.title}
        </h1>
        <p>{text.intro}</p>
        <p className="muted" data-testid="reacceptance-bots-note">
          {text.botsKeepRunning}
        </p>
        {documents && (
          <p className="muted" data-testid="reacceptance-version">
            {text.version(documents.version)}
          </p>
        )}
        <div aria-live="polite" data-testid="reacceptance-live">
          {view.kind === 'loading' && <p data-testid="reacceptance-loading">{text.loading}</p>}
          {view.kind === 'submitting' && (
            <p data-testid="reacceptance-submitting">{text.submitting}</p>
          )}
          {notice === 'outdated' && (
            <p className="notice notice-warning" data-testid="reacceptance-outdated">
              {text.outdated}
            </p>
          )}
          {notice === 'failed' && (
            <p className="notice notice-error" data-testid="reacceptance-failed">
              {text.failed}
            </p>
          )}
        </div>
        {view.kind === 'error' && (
          <div className="notice notice-error" data-testid="reacceptance-error">
            <h2>{text.errorTitle}</h2>
            <p>{text.errorBody}</p>
            <button
              type="button"
              className="secondary"
              data-testid="reacceptance-retry"
              onClick={() => void loadDocuments()}
            >
              {text.retry}
            </button>
          </div>
        )}
        {documents && <LegalSummary documents={documents} idPrefix="reacceptance" />}
        <TermsConsentBoxes
          idPrefix="reacceptance"
          consent={consent}
          disabled={boxesDisabled}
          onChange={setConsent}
        />
        <button
          type="button"
          data-testid="reacceptance-accept"
          disabled={!canAccept}
          aria-describedby={canAccept ? undefined : 'reacceptance-accept-reason'}
          onClick={() => void accept()}
        >
          {text.accept}
        </button>
        {!canAccept && (
          <p id="reacceptance-accept-reason" data-testid="reacceptance-accept-reason">
            {reason}
          </p>
        )}
      </main>
      <SessionSiteFooter />
    </div>
  );
}
