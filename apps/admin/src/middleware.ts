import { NextResponse, type NextRequest } from 'next/server';

/**
 * Security headers of the administrator app (security-design.md "App do administrador", NFR6.4, NFR6.5): a fresh
 * 128-bit nonce per request; scripts only with the nonce ('strict-dynamic'); connect-src only the app and the API origin
 * read on the server; no framing, no plugins, no base URI, forms only to the app itself. style-src keeps
 * 'unsafe-inline' because Next.js injects inline styles (accepted limitation R-07: a style never runs a script). Every
 * page is private: never cached.
 */
export function buildCsp(nonce: string, apiOrigin: string, development: boolean): string {
  const scriptSrc = [
    `'self'`,
    `'nonce-${nonce}'`,
    "'strict-dynamic'",
    ...(development ? ["'unsafe-eval'"] : []),
  ];
  return [
    "default-src 'self'",
    `script-src ${scriptSrc.join(' ')}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    `connect-src 'self' ${apiOrigin}`,
    "frame-ancestors 'none'",
    "base-uri 'none'",
    "object-src 'none'",
    "form-action 'self'",
  ].join('; ');
}

/** 128 random bits, base64. */
export function newNonce(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return btoa(String.fromCharCode(...bytes));
}

export const PERMISSIONS_POLICY =
  'camera=(), microphone=(), geolocation=(), payment=(), usb=(), clipboard-read=(), interest-cohort=()';

export function middleware(request: NextRequest) {
  const nonce = newNonce();
  const apiOrigin = process.env.API_ORIGIN ?? 'http://localhost:8080';
  const csp = buildCsp(nonce, apiOrigin, process.env.NODE_ENV === 'development');

  const requestHeaders = new Headers(request.headers);
  requestHeaders.set('x-nonce', nonce);
  requestHeaders.set('Content-Security-Policy', csp);

  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set('Content-Security-Policy', csp);
  response.headers.set('X-Content-Type-Options', 'nosniff');
  response.headers.set('X-Frame-Options', 'DENY');
  response.headers.set('Referrer-Policy', 'no-referrer');
  response.headers.set('Permissions-Policy', PERMISSIONS_POLICY);
  response.headers.set('Cache-Control', 'no-store');
  return response;
}

export const config = {
  // The health route of the container stays outside (R-07), like the static assets.
  matcher: [{ source: '/((?!_next/static|_next/image|favicon.ico|api/health).*)' }],
};
