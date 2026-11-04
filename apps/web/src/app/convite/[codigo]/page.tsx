import { InviteScreen } from '@/features/auth/InviteScreen';

/**
 * /convite/[codigo] (US1.1). The route never records its URL: the app has no access log of its own, and any log that
 * comes to exist must use the template /convite/[codigo] (NFR6.4). The middleware answers it with Cache-Control
 * no-store and Referrer-Policy no-referrer (NFR6.5).
 */
export default async function InvitePage({ params }: { params: Promise<{ codigo: string }> }) {
  const { codigo } = await params;
  return <InviteScreen code={codigo} />;
}
