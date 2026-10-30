import { NextResponse, type NextRequest } from 'next/server';

/**
 * Builds the Content-Security-Policy with a fresh nonce for every request (security-design.md Q8): scripts only with
 * the nonce, connect-src only the app itself and the API origin, no framing, no plugins.
 */
export function buildCsp(nonce: string, apiOrigin: string, development: boolean): string {
  const scriptSrc = [
    `'nonce-${nonce}'`,
    "'strict-dynamic'",
    ...(development ? ["'unsafe-eval'"] : []),
  ];
  return [
    "default-src 'self'",
    `script-src ${scriptSrc.join(' ')}`,
    `style-src 'self' 'nonce-${nonce}'`,
    "img-src 'self' data:",
    "font-src 'self'",
    `connect-src 'self' ${apiOrigin}`,
    "frame-ancestors 'none'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
  ].join('; ');
}

export function newNonce(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return btoa(String.fromCharCode(...bytes));
}

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
  return response;
}

export const config = {
  matcher: [{ source: '/((?!_next/static|_next/image|favicon.ico).*)' }],
};
