import type { FeedbackScreen } from '@/lib/api/guest-api';

/**
 * The screen of origin of a comment, from the route that is open (BR7.3, mockups S1 to S9). The dialog itself is S9;
 * a route without a screen of its own counts as the dashboard (S2).
 */
export function screenOf(pathname: string | null): FeedbackScreen {
  const path = pathname ?? '/';
  if (path.startsWith('/convite') || path.startsWith('/entrar')) {
    return 'S1';
  }
  if (path.startsWith('/scanner')) {
    return 'S3';
  }
  if (path.startsWith('/bots/new')) {
    return 'S4';
  }
  if (/^\/bots\/[^/]+/.test(path)) {
    return 'S5';
  }
  if (path.startsWith('/bots')) {
    return 'S8';
  }
  if (path.startsWith('/pnl')) {
    return 'S6';
  }
  if (path.startsWith('/conta')) {
    return 'S7';
  }
  return 'S2';
}

/** Characters as the server counts them: code points, so an emoji is one character (BR6.1). */
export function codePoints(text: string): number {
  return Array.from(text).length;
}

export const MAX_FEEDBACK = 2000;
