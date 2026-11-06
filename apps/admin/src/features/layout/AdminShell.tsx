'use client';

import type { ReactNode } from 'react';
import { messages } from '@/lib/i18n/messages';
import { useSession } from '@/features/auth/AdminSessionProvider';
import { AdminSignIn } from '@/features/auth/AdminSignIn';
import { RestrictedAccess } from '@/features/auth/RestrictedAccess';
import { AdminLayout } from '@/features/layout/AdminLayout';

/** Each administrative page: the entry while there is no session, "Acesso restrito" after a 403, otherwise the page. */
export function AdminShell({ children }: { children: ReactNode }) {
  const session = useSession();
  if (session.status === 'loading') {
    return (
      <p role="status" data-testid="admin-loading">
        {messages.app.loading}
      </p>
    );
  }
  if (session.status === 'restricted') {
    return <RestrictedAccess />;
  }
  if (session.status === 'anonymous') {
    return <AdminSignIn />;
  }
  return <AdminLayout>{children}</AdminLayout>;
}
