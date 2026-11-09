import { legalMessages } from '@/features/legal/messages';

const text = legalMessages.footer;

/**
 * The footer of every screen of the administrator app (FR2.2, BR1.11): links to "Termos de uso" and "Privacidade".
 * An administrator never accepts the guest terms, so there is no "Versão aceita" here.
 */
export function SiteFooter() {
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
    </footer>
  );
}
