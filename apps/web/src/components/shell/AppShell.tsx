'use client';

import type { ReactNode } from 'react';
import { navMessages } from '@/features/dashboard/messages';
import { SignOutButton } from '@/features/auth/SignOutButton';
import { FeedbackButton } from '@/features/feedback/FeedbackButton';
import { NoticesProvider, NoticesRegion } from '@/components/shell/NoticesRegion';
import { PrimaryNav, type ShellSection } from '@/components/shell/PrimaryNav';
import { SessionSiteFooter } from '@/components/shell/SiteFooter';
import { WalletBadge } from '@/components/shell/WalletBadge';
import { LiveChannelAnnouncer } from '@/features/live/LiveChannelProvider';
import { RealBadge } from '@/features/real/components/RealBadge';
import { realMessages } from '@/features/real/messages';
import { useSessionReal } from '@/features/real/session-real';
import { SigningBanner } from '@/features/real/components/SigningBanner';

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
  const real = useSessionReal();
  return (
    <NoticesProvider>
      <div data-testid="app-shell">
        <header className="app-header">
          <span className="app-name">{navMessages.appName}</span>
          {/* 261113-trading-real-bot U6 (BR7.1): with an active bot wallet the tag is "PAPER" beside the REAL label. */}
          {real?.mode ? (
            <span data-testid="app-mode-tags">
              <span className="tag">{realMessages.paperTag}</span>{' '}
              <RealBadge testId="app-real-badge" />
            </span>
          ) : (
            <span className="tag">{navMessages.paperMode}</span>
          )}
          <PrimaryNav current={current} />
          <span className="app-actions">
            <FeedbackButton /> <WalletBadge /> <SignOutButton />
          </span>
        </header>
        {/* 261113-trading-real-bot U6 (BR3.5): the signing banner right below the header, on every screen. */}
        {real?.mode === 'SIGNED' && <SigningBanner mode={real.mode} />}
        <NoticesRegion />
        {/* 261113-trading-real-bot U6: the loss and return of the live channel, on every screen with a session. */}
        <LiveChannelAnnouncer />
        <main id="conteudo" aria-labelledby={titleId} className={className}>
          {children}
        </main>
        {/* u1-legal-documents (BR1.11): the legal links and the accepted version on every logged screen. */}
        <SessionSiteFooter />
      </div>
    </NoticesProvider>
  );
}
