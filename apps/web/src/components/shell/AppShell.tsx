'use client';

import type { ReactNode } from 'react';
import { navMessages } from '@/features/dashboard/messages';
import { SignOutButton } from '@/features/auth/SignOutButton';
import { FeedbackButton } from '@/features/feedback/FeedbackButton';
import { NoticesProvider, NoticesRegion } from '@/components/shell/NoticesRegion';
import { PrimaryNav, type ShellSection } from '@/components/shell/PrimaryNav';
import { WalletBadge } from '@/components/shell/WalletBadge';

/**
 * The single shell of the logged screens S2 to S8 (BR6.1 to BR6.4, CT-UX-9): the header with the app name and
 * "MODO PAPER (simulado)", the main navigation, "Feedback" (U8), the abbreviated wallet and "Sair" (U3); the "Avisos"
 * region; and `main#conteudo` (target of the "Pular para o conteúdo" link of the root layout) labelled by the h1 of
 * the screen. The screens only give their title and content.
 */
export function AppShell({
  current,
  titleId,
  className,
  children,
}: {
  current: ShellSection;
  titleId: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <NoticesProvider>
      <div data-testid="app-shell">
        <header className="app-header">
          <span className="app-name">{navMessages.appName}</span>
          <span className="tag">{navMessages.paperMode}</span>
          <PrimaryNav current={current} />
          <span className="app-actions">
            <FeedbackButton /> <WalletBadge /> <SignOutButton />
          </span>
        </header>
        <NoticesRegion />
        <main id="conteudo" aria-labelledby={titleId} className={className}>
          {children}
        </main>
      </div>
    </NoticesProvider>
  );
}
