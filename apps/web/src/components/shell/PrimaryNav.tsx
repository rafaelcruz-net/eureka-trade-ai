'use client';

import Link from 'next/link';
import { navMessages } from '@/features/dashboard/messages';

/** The item of the main navigation a screen belongs to (S4 wizard and S5 bot screen belong to "Bots PAPER"). */
export type ShellSection = 'dashboard' | 'scanner' | 'bots' | 'pnl' | 'account';

export const NAV_ITEMS: ReadonlyArray<{ section: ShellSection; href: string }> = [
  { section: 'dashboard', href: '/' },
  { section: 'scanner', href: '/scanner' },
  { section: 'bots', href: '/bots' },
  { section: 'pnl', href: '/pnl' },
  { section: 'account', href: '/conta' },
];

/**
 * "Navegação principal" (BR6.1, BR6.2; mockups "navegação principal"): Painel, Scanner, Bots PAPER, P&L PAPER and
 * Conta, in this order, with `aria-current="page"` on the item of the current screen.
 */
export function PrimaryNav({ current }: { current: ShellSection }) {
  return (
    <nav aria-label={navMessages.label} className="app-nav" data-testid="app-primary-nav">
      <ul>
        {NAV_ITEMS.map((item) => (
          <li key={item.section}>
            <Link
              href={item.href}
              aria-current={item.section === current ? 'page' : undefined}
              data-testid={`app-nav-${item.section}-link`}
            >
              {navMessages.items[item.section]}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}
