'use client';

import { useEffect, useRef, type ReactNode } from 'react';
import { messages } from '@/lib/i18n/messages';
import { AccountMenu } from '@/features/auth/AccountMenu';
import { botMessages } from '@/features/bots/messages';

/**
 * Header and main region of the bot screens: "MODO PAPER (simulado)" in the title, the h1 equal to the menu item and
 * the focus on the title when the screen opens (BR6.4, NFR11.1).
 */
export function BotsShell({
  title,
  testId,
  children,
}: {
  title: string;
  testId: string;
  children: ReactNode;
}) {
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => heading.current?.focus(), []);
  return (
    <>
      <header className="app-header">
        <span>{messages.app.name}</span>
        <span className="muted">{messages.app.paperOnly}</span>
        <AccountMenu />
      </header>
      <main id="conteudo" aria-labelledby={`${testId}-title`}>
        <h1 id={`${testId}-title`} ref={heading} tabIndex={-1} data-testid={`${testId}-title`}>
          {title} <span className="tag">{botMessages.paperLabel}</span>
        </h1>
        {children}
      </main>
    </>
  );
}
