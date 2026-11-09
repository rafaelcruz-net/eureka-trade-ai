'use client';

import { useSessionAccount } from '@/components/shell/session-account';
import { legalMessages } from '@/features/legal/messages';

const text = legalMessages.footer;

/**
 * The footer of every screen (FR2.2, BR1.11): links to "Termos de uso" and "Privacidade"; with a session also
 * "Versão aceita: vX". Plain links, so the legal pages open without a session.
 */
export function SiteFooter({ acceptedVersion }: { acceptedVersion?: string | null }) {
  return (
    <footer className="site-footer" data-testid="site-footer">
      <nav aria-label={text.label}>
        <a href="/termos" data-testid="site-footer-terms-link">
          {text.terms}
        </a>
        <a href="/privacidade" data-testid="site-footer-privacy-link">
          {text.privacy}
        </a>
      </nav>
      {acceptedVersion ? (
        <span data-testid="site-footer-accepted-version">
          {text.acceptedVersion(acceptedVersion)}
        </span>
      ) : null}
    </footer>
  );
}

/** The footer with the accepted version of the session, read once per session with the masked wallet. */
export function SessionSiteFooter() {
  const account = useSessionAccount();
  return <SiteFooter acceptedVersion={account?.acceptedVersion ?? null} />;
}
