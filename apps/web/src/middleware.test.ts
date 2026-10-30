/**
 * @jest-environment node
 */
import { NextRequest } from 'next/server';
import { buildCsp, middleware, newNonce } from '@/middleware';

describe('middleware CSP', () => {
  const original = process.env.API_ORIGIN;

  afterEach(() => {
    process.env.API_ORIGIN = original;
  });

  it('uses a different nonce on every request', () => {
    process.env.API_ORIGIN = 'http://localhost:8080';
    const first = middleware(new NextRequest('http://localhost:3000/scanner'));
    const second = middleware(new NextRequest('http://localhost:3000/scanner'));

    const nonceOf = (csp: string | null) => /'nonce-([^']+)'/.exec(csp ?? '')?.[1];
    expect(nonceOf(first.headers.get('Content-Security-Policy'))).toBeDefined();
    expect(nonceOf(first.headers.get('Content-Security-Policy'))).not.toEqual(
      nonceOf(second.headers.get('Content-Security-Policy')),
    );
  });

  it('allows connections only to the app itself and the API origin', () => {
    process.env.API_ORIGIN = 'http://api.example:8080';
    const csp = middleware(new NextRequest('http://localhost:3000/')).headers.get(
      'Content-Security-Policy',
    );

    expect(csp).toContain("connect-src 'self' http://api.example:8080");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toContain("object-src 'none'");
    expect(csp).toContain("base-uri 'self'");
  });

  it('sets the security headers', () => {
    const response = middleware(new NextRequest('http://localhost:3000/'));

    expect(response.headers.get('X-Content-Type-Options')).toBe('nosniff');
    expect(response.headers.get('X-Frame-Options')).toBe('DENY');
    expect(response.headers.get('Referrer-Policy')).toBe('no-referrer');
  });

  it('never allows eval outside development', () => {
    expect(buildCsp('n', 'http://api', false)).not.toContain('unsafe-eval');
    expect(buildCsp('n', 'http://api', true)).toContain('unsafe-eval');
    expect(buildCsp('n', 'http://api', false)).toContain("script-src 'nonce-n' 'strict-dynamic'");
  });

  it('generates 128-bit nonces', () => {
    expect(Buffer.from(newNonce(), 'base64')).toHaveLength(16);
  });
});
