'use client';

import { useEffect, useRef, type ReactNode } from 'react';
import { AppShell } from '@/components/shell/AppShell';
import { botMessages } from '@/features/bots/messages';

/**
 * Title and content of the bot screens (list, S4 wizard, S5 bot screen) inside the single shell of U9, where they all
 * mark "Bots PAPER" in the main navigation (BR6.2 of U9): "MODO PAPER (simulado)" in the title, the h1 equal to the
 * menu item on the list, and the focus on the title when the screen opens (BR6.4, NFR11.1).
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
    <AppShell current="bots" titleId={`${testId}-title`}>
      <h1 id={`${testId}-title`} ref={heading} tabIndex={-1} data-testid={`${testId}-title`}>
        {title} <span className="tag">{botMessages.paperLabel}</span>
      </h1>
      {children}
    </AppShell>
  );
}
