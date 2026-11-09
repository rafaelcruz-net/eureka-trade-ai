import Link from 'next/link';
import { SiteFooter } from '@/components/SiteFooter';
import {
  loadLegalPage,
  type LegalFetchSettings,
  type LegalPageData,
} from '@/features/legal/legal-api';
import { LegalDocument } from '@/features/legal/LegalDocument';
import { LEGAL_PATH, legalMessages, type LegalKind } from '@/features/legal/messages';
import { RequestButton } from '@/features/legal/RequestButton';
import { VersionHistory } from '@/features/legal/VersionHistory';

/**
 * `/termos` and `/privacidade` of the administrator app (FR2.1): built on the SERVER from L1, without a session and
 * outside the administrator entry; every page of this app stays `no-store` (middleware).
 */
export async function LegalPage({
  kind,
  requestedVersion,
  guestAppOrigin,
  settings,
}: {
  kind: LegalKind;
  requestedVersion?: string;
  guestAppOrigin: string;
  settings?: LegalFetchSettings;
}) {
  const data = await loadLegalPage(requestedVersion, settings);
  return (
    <LegalPageView
      kind={kind}
      data={data}
      requestedVersion={requestedVersion}
      guestAppOrigin={guestAppOrigin}
    />
  );
}

export function LegalPageView({
  kind,
  data,
  requestedVersion,
  guestAppOrigin,
}: {
  kind: LegalKind;
  data: LegalPageData;
  requestedVersion?: string;
  guestAppOrigin: string;
}) {
  return (
    <div className="legal-page" data-testid={`legal-page-${kind}`}>
      <header className="legal-header">
        <Link href="/" data-testid="legal-brand-link">
          <strong>{legalMessages.brand}</strong>
        </Link>
        <Link href="/" data-testid="legal-sign-in-link">
          {legalMessages.signIn}
        </Link>
      </header>
      <main id="conteudo" aria-labelledby="legal-title">
        <LegalContent
          kind={kind}
          data={data}
          requestedVersion={requestedVersion}
          guestAppOrigin={guestAppOrigin}
        />
      </main>
      <SiteFooter />
    </div>
  );
}

function LegalContent({
  kind,
  data,
  requestedVersion,
  guestAppOrigin,
}: {
  kind: LegalKind;
  data: LegalPageData;
  requestedVersion?: string;
  guestAppOrigin: string;
}) {
  const path = LEGAL_PATH[kind];
  if (data.state === 'error') {
    const retryHref = requestedVersion
      ? `${path}?versao=${encodeURIComponent(requestedVersion)}`
      : path;
    return (
      <div className="legal-notice legal-notice-error" role="alert" data-testid="legal-error">
        <h1 id="legal-title">{legalMessages.errorTitle}</h1>
        <p>{legalMessages.errorBody}</p>
        <a className="button-link" href={retryHref} data-testid="legal-retry">
          {legalMessages.retry}
        </a>
      </div>
    );
  }
  if (data.state === 'not-found') {
    return (
      <div className="legal-notice legal-notice-warning" role="alert" data-testid="legal-not-found">
        <h1 id="legal-title">{legalMessages.notFoundTitle}</h1>
        <p>{legalMessages.notFoundBody}</p>
        <a href={path} data-testid="legal-current-link">
          {legalMessages.seeCurrent}
        </a>
      </div>
    );
  }
  return (
    <LegalDocument
      document={data.document}
      kind={kind}
      isCurrent={data.document.version === data.currentVersion}
      requestSlot={
        <RequestButton
          dpoEmail={data.document.deployment.dpoEmail}
          guestAppOrigin={guestAppOrigin}
        />
      }
      historySlot={
        data.history ? (
          <VersionHistory
            versions={data.history}
            currentVersion={data.currentVersion}
            basePath={path}
          />
        ) : null
      }
    />
  );
}

/** The single value of `?versao=` (the first one when repeated). */
export function versionParam(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}
