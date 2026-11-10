/** u3-reacceptance (T7): the screen of the new acceptance of the terms. */
export const REACCEPTANCE_PATH = '/novo-aceite';

/** Where the person goes when the return path is missing or refused: the dashboard. */
export const DEFAULT_RETURN = '/';

/**
 * Only an internal path of the app: starts with "/", not with "//" nor "/\" (both are another site for the browser),
 * no whitespace and no backslash anywhere (NFR2.4).
 */
const INTERNAL_PATH = /^\/(?![/\\])[^\s\\]*$/;

/**
 * u3-reacceptance (NFR2.4, frontend-components.md): the return path of `?voltar=`. Anything that is not an internal
 * path of the app (an absolute URL, a protocol-relative URL, a `javascript:` text, a path with spaces or backslashes)
 * goes back to the dashboard, so the screen never sends the person to another site. The screen of the new acceptance
 * itself is never a return path.
 */
export function safeReturnPath(value: string | null | undefined): string {
  if (typeof value !== 'string' || !INTERNAL_PATH.test(value)) {
    return DEFAULT_RETURN;
  }
  if (value === REACCEPTANCE_PATH || value.startsWith(`${REACCEPTANCE_PATH}?`)) {
    return DEFAULT_RETURN;
  }
  return value;
}

/** `/novo-aceite?voltar=<path>` for the screen where the lock was met. */
export function reacceptanceHref(currentPath: string): string {
  return `${REACCEPTANCE_PATH}?${new URLSearchParams({ voltar: safeReturnPath(currentPath) })}`;
}

/** The first value of a search parameter of the App Router. */
export function firstParam(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}
