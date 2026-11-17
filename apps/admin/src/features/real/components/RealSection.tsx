'use client';

import Link from 'next/link';
import type { ReactNode } from 'react';
import type { AdminRealError } from '@/features/real/errors';
import { adminRealMessages as text } from '@/features/real/messages';

/** BR9.1: the parts of the REAL section; "Kill switch REAL" is apart from the "Kill switch" of the PAPER (BR9.4). */
const LINKS: ReadonlyArray<[string, string, string]> = [
  ['/real', text.sections.users, 'admin-real-nav-users'],
  ['/real/tokens', text.sections.tokens, 'admin-real-nav-tokens'],
  ['/real/kill-switch', text.sections.killSwitch, 'admin-real-nav-kill-switch'],
  ['/real/divergencias', text.sections.divergences, 'admin-real-nav-divergences'],
  ['/real/restauracao', text.sections.restore, 'admin-real-nav-restore'],
];

/** The REAL label: text and icon, never the color alone (BR7.1). */
export function RealLabel() {
  return (
    <span className="real-badge" data-testid="admin-real-badge">
      <span aria-hidden="true">◆</span> {text.badge}
    </span>
  );
}

/**
 * 261113-trading-real-bot U6 (A1, BR9.1): the frame of every screen of the REAL section: the title with the REAL label
 * and the navigation of its parts.
 */
export function RealSection({
  title,
  testId,
  children,
}: {
  title: string;
  testId: string;
  children: ReactNode;
}) {
  return (
    <section aria-labelledby={`${testId}-title`} data-testid={testId}>
      <h1 id={`${testId}-title`}>
        {title} <RealLabel />
      </h1>
      <nav aria-label={text.nav} data-testid="admin-real-nav">
        {LINKS.map(([href, label, id]) => (
          <span key={href}>
            <Link href={href} data-testid={id}>
              {label}
            </Link>{' '}
          </span>
        ))}
      </nav>
      {children}
    </section>
  );
}

/** An error of a read of the REAL section, in text, with the id for support and "Tentar de novo". */
export function RealLoadError({
  error,
  message,
  onRetry,
  testId,
}: {
  error: AdminRealError;
  message: string;
  onRetry: () => void;
  testId: string;
}) {
  return (
    <div role="alert" data-testid={testId}>
      <p>{message}</p>
      <p>{error.text}</p>
      {error.correlationId && <p className="muted">{text.supportCode(error.correlationId)}</p>}
      <button type="button" onClick={onRetry} data-testid={`${testId}-retry`}>
        {text.retry}
      </button>
    </div>
  );
}

/** "AbCd…WxYz" (U6-NFR1.8). */
export function abbreviate(value: string | undefined): string {
  if (!value) {
    return text.noData;
  }
  return value.length <= 10 ? value : `${value.slice(0, 4)}…${value.slice(-4)}`;
}
