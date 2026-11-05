'use client';

import Link from 'next/link';
import { messages } from '@/lib/i18n/messages';
import { SignOutButton } from '@/features/auth/SignOutButton';

/**
 * Account menu of the guest app header (BR4.4): the P&L screen (U7), the Account screen and "Sair". There is no
 * "disconnect wallet".
 */
export function AccountMenu() {
  return (
    <nav aria-label={messages.app.accountMenu} data-testid="app-account-menu">
      <Link href="/bots" data-testid="app-bots-link">
        {messages.app.bots}
      </Link>{' '}
      <Link href="/pnl" data-testid="app-pnl-link">
        {messages.app.pnl}
      </Link>{' '}
      <Link href="/conta" data-testid="app-account-link">
        {messages.app.account}
      </Link>{' '}
      <SignOutButton />
    </nav>
  );
}
