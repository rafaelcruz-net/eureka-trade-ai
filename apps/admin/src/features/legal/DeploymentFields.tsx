import { legalMessages } from '@/features/legal/messages';

const labels = legalMessages.deployment;

/** The fields of the policy that depend on the environment (BR1.6): an absent field reads "a definir". */
export function DeploymentFields({
  storageLocation,
  internationalTransfer,
  dpoEmail,
}: {
  storageLocation: string | null;
  internationalTransfer: string | null;
  dpoEmail: string | null;
}) {
  return (
    <dl className="legal-deployment" data-testid="legal-deployment">
      <dt>{labels.storageLocation}</dt>
      <dd data-testid="legal-deployment-storage">{storageLocation ?? legalMessages.toBeDefined}</dd>
      <dt>{labels.internationalTransfer}</dt>
      <dd data-testid="legal-deployment-transfer">
        {internationalTransfer ?? legalMessages.toBeDefined}
      </dd>
      <dt>{labels.dpoEmail}</dt>
      <dd data-testid="legal-deployment-dpo">
        <DpoEmail email={dpoEmail} />
      </dd>
    </dl>
  );
}

/** The e-mail of the data protection officer as a `mailto:` link, or "a definir" (NFR1.2: no form). */
export function DpoEmail({ email, testId }: { email: string | null; testId?: string }) {
  if (!email) {
    return <span data-testid={testId}>{legalMessages.toBeDefined}</span>;
  }
  return (
    <a href={`mailto:${email}`} className="legal-email" data-testid={testId}>
      {email}
    </a>
  );
}
