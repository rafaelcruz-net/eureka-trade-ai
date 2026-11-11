/** Screen the guest app opens when there is no valid origin: the dashboard S2 since U9 (BR1.1, FR12.1). */
export const HOME = '/';

/**
 * Routes of the guest app a person may return to after signing in again (BR4.2). Neither the entry, the invitation
 * page (its path carries the code) nor anything administrative is a valid origin. U9: the dashboard itself, so an
 * expired session on the dashboard comes back to it (BR5.5 of U9). The bots (U5) and P&L (U7) screens too, so the lock
 * of the new acceptance (u3-reacceptance) and an expired session keep the screen of origin.
 */
export const GUEST_ROUTES: readonly string[] = ['/', '/scanner', '/conta', '/bots', '/pnl'];

// Control characters (tab, CR, LF included) and DEL.
const CONTROL = /[\u0000-\u001f\u007f]/;

function hasUnsafeShape(value: string): boolean {
  return (
    CONTROL.test(value) || value.includes('\\') || !value.startsWith('/') || value.startsWith('//')
  );
}

/**
 * BR4.2 / NFR6.11: the return after the entry accepts only an internal path of the guest app. Refuses control
 * characters and backslashes, anything that does not start with exactly one "/", decodes ONCE and repeats those
 * checks (so "/%2F%2Fexemplo.com" is refused), and finally requires the path, without query or fragment, to be one of
 * the guest routes. Any other value becomes the home page.
 */
export function safeOrigin(value: string | null | undefined): string {
  if (!value || hasUnsafeShape(value)) {
    return HOME;
  }
  let decoded: string;
  try {
    decoded = decodeURIComponent(value);
  } catch {
    return HOME;
  }
  if (hasUnsafeShape(decoded)) {
    return HOME;
  }
  const path = decoded.split(/[?#]/, 1)[0] ?? '';
  return GUEST_ROUTES.includes(path) ? value : HOME;
}
