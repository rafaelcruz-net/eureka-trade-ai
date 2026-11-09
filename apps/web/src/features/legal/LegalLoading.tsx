import { legalMessages } from '@/features/legal/messages';

/** Loading lines of a legal page while the server reads L1 (F3 step 2). */
export function LegalLoading() {
  return (
    <main id="conteudo" aria-busy="true" data-testid="legal-loading">
      <p role="status">{legalMessages.loading}</p>
      <div className="legal-skeleton" aria-hidden="true">
        <span />
        <span />
        <span />
      </div>
    </main>
  );
}
