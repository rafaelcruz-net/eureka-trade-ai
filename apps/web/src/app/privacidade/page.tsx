import type { Metadata } from 'next';
import { LegalPage, versionParam } from '@/features/legal/LegalPage';
import { legalMessages } from '@/features/legal/messages';

export const metadata: Metadata = { title: legalMessages.title.privacy };

/** /privacidade (FR2.1, US2.1, US2.4, US2.5): public, rendered on the server from L1; `?versao=` shows an older version. */
export default async function PrivacyPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const params = await searchParams;
  return <LegalPage kind="privacy" requestedVersion={versionParam(params.versao)} />;
}
