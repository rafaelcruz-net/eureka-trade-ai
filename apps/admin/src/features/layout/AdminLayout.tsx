'use client';

import Link from 'next/link';
import type { ReactNode } from 'react';
import { messages } from '@/lib/i18n/messages';
import { useSession } from '@/features/auth/AdminSessionProvider';
import { KillSwitchBanner } from '@/features/layout/KillSwitchBanner';
import { KillSwitchProvider } from '@/features/layout/KillSwitchContext';

const text = messages.app;

/**
 * Header of every administrative screen (BR7.1, BR1.6): "MemeTrade Admin", the navigation (Estado, Convites, Kill
 * switch, Feedback), the masked wallet of the administrator and "Sair"; below it the kill switch banner and the fixed
 * note that the administration has no access to the data of the guests (AC7.4.2).
 */
export function AdminLayout({ children }: { children: ReactNode }) {
  const session = useSession();
  return (
    <KillSwitchProvider>
      <header className="app-header">
        <strong data-testid="admin-app-name">{text.name}</strong>
        <nav aria-label={text.navigation} data-testid="admin-navigation">
          <Link href="/" data-testid="admin-nav-status">
            {text.status}
          </Link>{' '}
          <Link href="/convites" data-testid="admin-nav-invites">
            {text.invites}
          </Link>{' '}
          <Link href="/kill-switch" data-testid="admin-nav-kill-switch">
            {text.killSwitch}
          </Link>{' '}
          <Link href="/feedback" data-testid="admin-nav-feedback">
            {text.feedback}
          </Link>
        </nav>
        <span data-testid="admin-wallet">
          {session.wallet ? text.signedAs(session.wallet) : ''}
        </span>
        <button
          type="button"
          className="secondary"
          data-testid="admin-sign-out"
          onClick={() => void session.signOut()}
        >
          {text.signOut}
        </button>
      </header>
      <KillSwitchBanner />
      <main id="conteudo">
        {children}
        <p className="note" data-testid="admin-no-personal-data">
          {text.noPersonalData}
        </p>
      </main>
    </KillSwitchProvider>
  );
}
