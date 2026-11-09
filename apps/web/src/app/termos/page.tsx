import type { Metadata } from 'next';
import { LegalPage, versionParam } from '@/features/legal/LegalPage';
import { legalMessages } from '@/features/legal/messages';

export const metadata: Metadata = { title: legalMessages.title.terms };

/** /termos (FR2.1, US2.1): public, rendered on the server from L1; `?versao=` shows an older version. */
export default async function TermsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const params = await searchParams;
  return <LegalPage kind="terms" requestedVersion={versionParam(params.versao)} />;
}
