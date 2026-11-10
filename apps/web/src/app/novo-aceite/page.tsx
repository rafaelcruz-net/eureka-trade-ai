import type { Metadata } from 'next';
import { fetchLegal, type LegalDocuments } from '@/features/legal/legal-api';
import { reacceptanceMessages } from '@/features/reacceptance/messages';
import { ReacceptanceScreen } from '@/features/reacceptance/ReacceptanceScreen';
import { firstParam, safeReturnPath } from '@/features/reacceptance/return-path';

export const metadata: Metadata = { title: reacceptanceMessages.title };

/**
 * /novo-aceite (T7; US4.1, US4.2; NFR2.4, NFR3.3): the documents of the effective current version come from the same
 * server client of L1 as /termos and /privacidade (300 s cache, no new dependency); when that read fails the screen
 * loads them itself and shows "Tentar de novo". `?voltar=` is accepted only as an internal path of the app.
 */
export default async function ReacceptancePage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const params = await searchParams;
  const current = await fetchLegal<LegalDocuments>('/current');
  return (
    <ReacceptanceScreen
      returnTo={safeReturnPath(firstParam(params.voltar))}
      initialDocuments={current.status === 'ok' ? current.data : null}
    />
  );
}
