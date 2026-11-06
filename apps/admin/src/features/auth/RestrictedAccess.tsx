'use client';

import { messages } from '@/lib/i18n/messages';
import { useSession } from '@/features/auth/AdminSessionProvider';

/** BR7.4: "Acesso restrito" without any data, and "Sair". The refusal was already audited by the API. */
export function RestrictedAccess() {
  const session = useSession();
  return (
    <main id="conteudo" className="restricted" data-testid="admin-restricted">
      <h1>
        <span aria-hidden="true">⛔ </span>
        {messages.restricted.title}
      </h1>
      <p>{messages.restricted.body}</p>
      <button
        type="button"
        className="secondary"
        data-testid="admin-restricted-sign-out"
        onClick={() => void session.signOut()}
      >
        {messages.restricted.signOut}
      </button>
    </main>
  );
}
