import type { Metadata } from 'next';
import { LegalPage, versionParam } from '@/features/legal/LegalPage';
import { legalMessages } from '@/features/legal/messages';

export const metadata: Metadata = { title: legalMessages.title.terms };

/** /termos of the administrator app (FR2.1): public, outside the administrator entry, rendered on the server. */
export default async function TermsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const params = await searchParams;
  const guestAppOrigin = process.env.GUEST_APP_ORIGIN ?? 'http://localhost:3000';
  return (
    <LegalPage
      kind="terms"
      requestedVersion={versionParam(params.versao)}
      guestAppOrigin={guestAppOrigin}
    />
  );
}
