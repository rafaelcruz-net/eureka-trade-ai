'use client';

import { useRouter } from 'next/navigation';
import { messages } from '@/lib/i18n/messages';
import { useSession } from '@/features/auth/SessionProvider';

/** "Sair" (F3): revokes the session on the server, drops the in-memory token and returns to the entry. */
export function SignOutButton() {
  const session = useSession();
  const router = useRouter();

  async function signOut() {
    await session.signOut();
    router.replace('/entrar');
  }

  return (
    <button
      type="button"
      className="secondary"
      data-testid="app-sign-out-button"
      onClick={() => void signOut()}
    >
      {messages.app.signOut}
    </button>
  );
}
