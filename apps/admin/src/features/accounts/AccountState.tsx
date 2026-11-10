import { messages } from '@/lib/i18n/messages';

const text = messages.dataRequests;

/**
 * u5-anonymization (frontend-components.md; functional-spec.md "Leitores de wallet"): the account of a request in the
 * administrator lists. An anonymized account shows "Conta anonimizada" with an icon AND text (never colour alone) and
 * no masked address; any other account shows its internal identifier, and a request without an account says so.
 */
export function AccountState({
  userId,
  anonymized,
  testId = 'account-state',
}: {
  userId: string | null | undefined;
  anonymized: boolean | null | undefined;
  testId?: string;
}) {
  if (!userId) {
    return <span data-testid={testId}>{text.noAccount}</span>;
  }
  if (anonymized) {
    return (
      <span className="account-anonymized" data-testid={`${testId}-anonymized`}>
        <span aria-hidden="true">⊘</span> {text.accountAnonymized}
      </span>
    );
  }
  return <span data-testid={testId}>{userId}</span>;
}
