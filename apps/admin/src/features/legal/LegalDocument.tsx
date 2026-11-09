'use client';

import { useState, type MouseEvent, type ReactNode } from 'react';
import { DeploymentFields, DpoEmail } from '@/features/legal/DeploymentFields';
import type { LegalDocuments, LegalText } from '@/features/legal/legal-api';
import {
  formatEffectiveDate,
  LEGAL_PATH,
  legalMessages,
  type LegalKind,
} from '@/features/legal/messages';
import { RetentionTable } from '@/features/legal/RetentionTable';

/**
 * One legal page of the administrator app (T1 and T2 of the mockups, BR1.13 to BR1.15), the same document as in the
 * guest app: title, version and date, old-version banner, "not published" notice, PAPER notice, summary, contents
 * (collapsed below 768 px) and sections; on the privacy page also the retention table, the deployment fields and the
 * request button. Every text is rendered as TEXT, never as HTML (NFR6.4).
 */
export function LegalDocument({
  document,
  kind,
  isCurrent,
  requestSlot,
  historySlot,
}: {
  document: LegalDocuments;
  kind: LegalKind;
  isCurrent: boolean;
  requestSlot?: ReactNode;
  historySlot?: ReactNode;
}) {
  const text = kind === 'terms' ? document.terms : document.privacy;
  return (
    <article className="legal-document" data-testid="legal-document">
      <h1 id="legal-title" data-testid="legal-title">
        {legalMessages.title[kind]}
      </h1>
      <p data-testid="legal-version">
        {legalMessages.version(document.version, formatEffectiveDate(document.effectiveAt))}
        {document.provisional ? ` ${legalMessages.provisional}` : ''}
      </p>
      {!isCurrent && (
        <p
          className="legal-notice legal-notice-warning"
          role="note"
          data-testid="legal-old-version-banner"
        >
          {legalMessages.notCurrent}{' '}
          <a href={LEGAL_PATH[kind]} data-testid="legal-current-link">
            {legalMessages.seeCurrent}
          </a>
        </p>
      )}
      {!document.publishable && (
        <p
          className="legal-notice legal-notice-warning"
          role="note"
          data-testid="legal-not-publishable"
        >
          {legalMessages.notPublishable}
        </p>
      )}
      <p className="legal-notice" role="note" data-testid="legal-paper-notice">
        {legalMessages.paperNotice}
      </p>
      {text ? (
        <LegalTextBody text={text} />
      ) : (
        <MissingPolicy dpoEmail={document.deployment.dpoEmail} />
      )}
      {kind === 'terms' && <RiskNotice text={document.riskNotice} />}
      {kind === 'privacy' && text && (
        <>
          <section aria-labelledby="legal-retention-heading" data-testid="legal-retention">
            <h2 id="legal-retention-heading">{legalMessages.retentionHeading}</h2>
            <RetentionTable items={document.retention} />
          </section>
          <section aria-labelledby="legal-deployment-heading">
            <h2 id="legal-deployment-heading">{legalMessages.deploymentHeading}</h2>
            <DeploymentFields
              storageLocation={document.deployment.storageLocation}
              internationalTransfer={document.deployment.internationalTransfer}
              dpoEmail={document.deployment.dpoEmail}
            />
          </section>
        </>
      )}
      {kind === 'privacy' && requestSlot && (
        <section aria-labelledby="legal-request-heading">
          <h2 id="legal-request-heading">{legalMessages.requestHeading}</h2>
          {requestSlot}
        </section>
      )}
      {historySlot}
    </article>
  );
}

function LegalTextBody({ text }: { text: LegalText }) {
  return (
    <>
      {text.summary && text.summary.length > 0 && (
        <section aria-labelledby="legal-summary-heading" data-testid="legal-summary">
          <h2 id="legal-summary-heading">{legalMessages.summaryHeading}</h2>
          <ul>
            {text.summary.map((topic) => (
              <li key={topic}>{topic}</li>
            ))}
          </ul>
        </section>
      )}
      {text.sections.length > 1 && <TableOfContents text={text} />}
      {text.sections.map((section) => (
        <section key={section.id} aria-labelledby={section.id} data-testid="legal-section">
          <h2 id={section.id} tabIndex={-1}>
            {section.title}
          </h2>
          {section.paragraphs.map((paragraph, index) => (
            <p key={`${section.id}-${index}`}>{paragraph}</p>
          ))}
        </section>
      ))}
    </>
  );
}

function TableOfContents({ text }: { text: LegalText }) {
  const [open, setOpen] = useState(false);

  function focusSection(event: MouseEvent<HTMLAnchorElement>, id: string) {
    const heading = window.document.getElementById(id);
    if (!heading) {
      return;
    }
    event.preventDefault();
    window.history.replaceState(null, '', `#${id}`);
    heading.focus();
    heading.scrollIntoView?.();
  }

  return (
    <nav
      aria-label={legalMessages.tocLabel}
      className="legal-toc"
      data-open={open ? 'true' : 'false'}
      data-testid="legal-toc"
    >
      <button
        type="button"
        className="secondary legal-toc-toggle"
        aria-expanded={open}
        aria-controls="legal-toc-list"
        data-testid="legal-toc-toggle"
        onClick={() => setOpen((value) => !value)}
      >
        {open ? legalMessages.tocToggleClose : legalMessages.tocToggleOpen}
      </button>
      <p className="legal-toc-title">{legalMessages.tocLabel}</p>
      <ol id="legal-toc-list">
        {text.sections.map((section) => (
          <li key={section.id}>
            <a
              href={`#${section.id}`}
              data-testid={`legal-toc-link-${section.id}`}
              onClick={(event) => focusSection(event, section.id)}
            >
              {section.title}
            </a>
          </li>
        ))}
      </ol>
    </nav>
  );
}

function RiskNotice({ text }: { text: LegalText }) {
  return (
    <section aria-labelledby="legal-risk-heading" data-testid="legal-risk-notice">
      <h2 id="legal-risk-heading">{legalMessages.riskNoticeHeading}</h2>
      {text.sections.map((section) => (
        <div key={section.id}>
          {text.sections.length > 1 && <h3>{section.title}</h3>}
          {section.paragraphs.map((paragraph, index) => (
            <p key={`${section.id}-${index}`}>{paragraph}</p>
          ))}
        </div>
      ))}
    </section>
  );
}

function MissingPolicy({ dpoEmail }: { dpoEmail: string | null }) {
  return (
    <section data-testid="legal-privacy-missing">
      <p className="legal-notice legal-notice-warning">{legalMessages.privacyMissing}</p>
      <p>
        {legalMessages.deployment.dpoEmail}:{' '}
        <DpoEmail email={dpoEmail} testId="legal-missing-dpo" />
      </p>
    </section>
  );
}
