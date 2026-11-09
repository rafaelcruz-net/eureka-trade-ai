import { DpoEmail } from '@/features/legal/DeploymentFields';
import { legalMessages } from '@/features/legal/messages';

/**
 * "Fazer um pedido" in the administrator app (FR2.4, BR1.12): requests of data subjects are made in the Account of the
 * GUEST app, and the administrator app never holds a guest session, so the button always leads to the guest entry
 * (where a signed-in guest reaches the Account). Who cannot sign in writes to the data protection officer.
 */
export function RequestButton({
  dpoEmail,
  guestAppOrigin,
}: {
  dpoEmail: string | null;
  guestAppOrigin: string;
}) {
  return (
    <div className="legal-request" data-testid="legal-request">
      <a
        className="button-link"
        href={`${guestAppOrigin.replace(/\/+$/, '')}/entrar`}
        data-testid="legal-request-button"
      >
        {legalMessages.requestButton}
      </a>
      <p className="legal-note">{legalMessages.requestGuestApp}</p>
      <p data-testid="legal-request-email">
        {legalMessages.requestByEmail} <DpoEmail email={dpoEmail} testId="legal-request-dpo" />
      </p>
    </div>
  );
}
