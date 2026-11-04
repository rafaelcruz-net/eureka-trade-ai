/** Screen the guest app opens when there is no valid origin (the home page opens the scanner). */
export const HOME = '/scanner';

/**
 * Routes of the guest app a person may return to after signing in again (BR4.2). Neither the entry, the invitation
 * page (its path carries the code) nor anything administrative is a valid origin.
 */
export const GUEST_ROUTES: readonly string[] = ['/scanner', '/conta'];

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
