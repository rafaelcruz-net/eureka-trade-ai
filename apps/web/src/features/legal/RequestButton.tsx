'use client';

import { useSession } from '@/features/auth/SessionProvider';
import { DpoEmail } from '@/features/legal/DeploymentFields';
import { legalMessages } from '@/features/legal/messages';

/**
 * "Fazer um pedido" (FR2.4, BR1.12): with a session it leads to the Account (where requests are made), without one to
 * the entry; there is no public form. Who cannot sign in (anonymized account, lost wallet) writes to the data
 * protection officer, whose e-mail is shown below (or "a definir").
 */
export function RequestButton({ dpoEmail }: { dpoEmail: string | null }) {
  const { status } = useSession();
  const signedIn = status === 'authenticated';
  return (
    <div className="legal-request" data-testid="legal-request">
      <a
        className="button-link"
        href={signedIn ? '/conta' : '/entrar'}
        data-testid="legal-request-button"
      >
        {legalMessages.requestButton}
      </a>
      <p className="note">
        {signedIn ? legalMessages.requestWithSession : legalMessages.requestWithoutSession}
      </p>
      <p data-testid="legal-request-email">
        {legalMessages.requestByEmail} <DpoEmail email={dpoEmail} testId="legal-request-dpo" />
      </p>
    </div>
  );
}
