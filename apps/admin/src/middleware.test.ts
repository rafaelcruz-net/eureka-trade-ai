/**
 * @jest-environment node
 */
import { NextRequest } from 'next/server';
import { PERMISSIONS_POLICY, buildCsp, config, middleware, newNonce } from '@/middleware';

describe('security headers of the administrator app (security-design.md, NFR6.4)', () => {
  const original = process.env.API_ORIGIN;

  afterEach(() => {
    process.env.API_ORIGIN = original;
  });

  it('uses a fresh nonce of 128 bits on every request', () => {
    const nonceOf = (csp: string | null) => /'nonce-([^']+)'/.exec(csp ?? '')?.[1];
    const first = middleware(new NextRequest('http://localhost:3001/'));
    const second = middleware(new NextRequest('http://localhost:3001/'));

    const nonce = nonceOf(first.headers.get('Content-Security-Policy'));
    expect(nonce).toBeDefined();
    expect(Buffer.from(nonce as string, 'base64')).toHaveLength(16);
    expect(nonce).not.toEqual(nonceOf(second.headers.get('Content-Security-Policy')));
    expect(newNonce()).not.toEqual(newNonce());
  });

  it('allows scripts only with the nonce and connections only to the app and the API origin', () => {
    process.env.API_ORIGIN = 'http://api.example:8080';
    const csp = middleware(new NextRequest('http://localhost:3001/kill-switch')).headers.get(
      'Content-Security-Policy',
    );

    expect(csp).toMatch(/script-src 'self' 'nonce-[^']+' 'strict-dynamic'/);
    expect(csp).toContain("connect-src 'self' http://api.example:8080");
    expect(csp).toContain("style-src 'self' 'unsafe-inline'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toContain("base-uri 'none'");
    expect(csp).toContain("object-src 'none'");
    expect(csp).toContain("form-action 'self'");
    expect(csp).not.toContain('unsafe-eval');
  });

  it('sets the other headers and never lets a page be cached', () => {
    const response = middleware(new NextRequest('http://localhost:3001/feedback'));

    expect(response.headers.get('X-Content-Type-Options')).toBe('nosniff');
    expect(response.headers.get('X-Frame-Options')).toBe('DENY');
    expect(response.headers.get('Referrer-Policy')).toBe('no-referrer');
    expect(response.headers.get('Permissions-Policy')).toBe(PERMISSIONS_POLICY);
    expect(response.headers.get('Cache-Control')).toBe('no-store');
  });

  it('passes the nonce to the page through the request headers', () => {
    const response = middleware(new NextRequest('http://localhost:3001/'));

    expect(response.headers.get('x-middleware-request-x-nonce')).toMatch(/^[A-Za-z0-9+/=]{24}$/);
  });

  it('allows eval only in development', () => {
    expect(buildCsp('n', 'http://api', true)).toContain("'unsafe-eval'");
    expect(buildCsp('n', 'http://api', false)).not.toContain("'unsafe-eval'");
  });

  it('leaves the health route and the static assets outside', () => {
    const source = (config.matcher[0] as { source: string }).source;
    const pattern = new RegExp(`^${source}$`);

    expect(pattern.test('/api/health')).toBe(false);
    expect(pattern.test('/_next/static/chunk.js')).toBe(false);
    expect(pattern.test('/kill-switch')).toBe(true);
  });
});
