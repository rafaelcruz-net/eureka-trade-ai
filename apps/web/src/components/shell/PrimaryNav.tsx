'use client';

import Link from 'next/link';
import { navMessages } from '@/features/dashboard/messages';
import { RealBadge } from '@/features/real/components/RealBadge';
import { realMessages } from '@/features/real/messages';
import { useSessionReal } from '@/features/real/session-real';

/**
 * The item of the main navigation a screen belongs to (S4 wizard and S5 bot screen belong to "Bots PAPER"). 261113-
 * trading-real-bot U6: the screens of the REAL belong to "Carteira do bot".
 */
export type ShellSection = 'dashboard' | 'scanner' | 'bots' | 'pnl' | 'account' | 'real';

export const NAV_ITEMS: ReadonlyArray<{ section: ShellSection; href: string }> = [
  { section: 'dashboard', href: '/' },
  { section: 'scanner', href: '/scanner' },
  { section: 'bots', href: '/bots' },
  { section: 'pnl', href: '/pnl' },
  { section: 'account', href: '/conta' },
];

/** U6 (BR1.1): "Carteira do bot" with the REAL label, only while the REAL is on in the server. */
export const REAL_NAV_ITEM = { section: 'real' as const, href: '/real' };

/**
 * "Navegação principal" (BR6.1, BR6.2; mockups "navegação principal"): Painel, Scanner, Bots PAPER, P&L PAPER and
 * Conta, in this order, with `aria-current="page"` on the item of the current screen. U6 (BR1.1, BR7.1): "Carteira do
 * bot" with the REAL label before "Conta", only with the REAL on.
 */
export function PrimaryNav({ current }: { current: ShellSection }) {
  const real = useSessionReal();
  const items = real?.available
    ? [...NAV_ITEMS.slice(0, 4), REAL_NAV_ITEM, ...NAV_ITEMS.slice(4)]
    : NAV_ITEMS;
  return (
    <nav aria-label={navMessages.label} className="app-nav" data-testid="app-primary-nav">
      <ul>
        {items.map((item) => (
          <li key={item.section}>
            <Link
              href={item.href}
              aria-current={item.section === current ? 'page' : undefined}
              data-testid={`app-nav-${item.section}-link`}
            >
              {item.section === 'real' ? (
                <>
                  {realMessages.navItem} <RealBadge testId="app-nav-real-badge" />
                </>
              ) : (
                navMessages.items[item.section]
              )}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}
