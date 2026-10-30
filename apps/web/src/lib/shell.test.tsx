import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { act, render, screen } from '@testing-library/react';
import { DesktopOnlyNotice } from '@/components/DesktopOnlyNotice';
import { tokenStore } from '@/lib/auth/token-store';
import { abbreviateMint, formatClock, formatDecimal, formatInstant } from '@/lib/format/format';
import { encodeBase58 } from '@/lib/base58';
import { colors, contrastRatio, luminance, textPairs } from '@/lib/theme/tokens';
import { messages } from '@/lib/i18n/messages';

function filesUnder(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? filesUnder(path) : [path];
  });
}

function mockMatchMedia(matches: boolean) {
  const listeners: Array<() => void> = [];
  const query = {
    matches,
    addEventListener: (_: string, l: () => void) => listeners.push(l),
    removeEventListener: jest.fn(),
  };
  window.matchMedia = jest.fn(() => query) as unknown as typeof window.matchMedia;
  return {
    change(next: boolean) {
      query.matches = next;
      listeners.forEach((l) => l());
    },
  };
}

describe('dark theme tokens', () => {
  it.each(textPairs)('%s on %s has contrast of at least 4.5:1', (text, background) => {
    expect(contrastRatio(colors[text], colors[background])).toBeGreaterThanOrEqual(4.5);
  });

  it('computes WCAG luminance and refuses malformed colors', () => {
    expect(luminance('#ffffff')).toBeCloseTo(1);
    expect(luminance('#000000')).toBe(0);
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21);
    expect(() => luminance('red')).toThrow();
  });
});

describe('token store', () => {
  afterEach(() => tokenStore.clear());

  it('keeps the token only in memory and never in web storage', () => {
    const setItem = jest.spyOn(Storage.prototype, 'setItem');
    const listener = jest.fn();
    const unsubscribe = tokenStore.subscribe(listener);

    tokenStore.set('abc');

    expect(tokenStore.get()).toBe('abc');
    expect(listener).toHaveBeenCalledWith('abc');
    expect(setItem).not.toHaveBeenCalled();
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
    expect(document.cookie).not.toContain('abc');
    unsubscribe();
    tokenStore.clear();
    expect(listener).toHaveBeenCalledTimes(1);
  });
});

describe('no secret reaches the browser bundle', () => {
  it('no NEXT_PUBLIC_ variable is used anywhere in the app or the environment example', () => {
    const sources = filesUnder(join(__dirname, '..')).filter(
      (f) => !f.endsWith('.test.ts') && !f.endsWith('.test.tsx'),
    );
    const usage = /process\.env(\.|\[['"])NEXT_PUBLIC_/;
    const offenders = sources.filter((file) => usage.test(readFileSync(file, 'utf8')));
    const envExample = readFileSync(
      join(__dirname, '..', '..', '..', '..', '.env.example'),
      'utf8',
    );

    expect(offenders).toEqual([]);
    expect(envExample).not.toContain('NEXT_PUBLIC_');
  });
});

describe('formatting', () => {
  it('groups decimals the pt-BR way without converting them to numbers', () => {
    expect(formatDecimal('1234567.891234567891')).toBe('1.234.567,891234567891');
    expect(formatDecimal('0')).toBe('0');
    expect(formatDecimal('-1000')).toBe('-1.000');
    expect(() => formatDecimal('1e5')).toThrow();
  });

  it('converts UTC instants to the given time zone only for display', () => {
    expect(formatInstant('2026-10-28T12:00:00Z', 'America/Sao_Paulo')).toContain('09:00:00');
    expect(formatInstant('2026-10-28T12:00:00Z', 'UTC')).toContain('12:00:00');
    expect(formatClock(new Date('2026-10-28T12:05:00Z'), 'UTC')).toBe('12:05');
  });

  it('abbreviates mints to 4 + 4 characters', () => {
    expect(abbreviateMint('ABCDEFGHJKLMNPQRSTUVWXYZ')).toBe('ABCD…WXYZ');
    expect(abbreviateMint('short')).toBe('short');
  });

  it('encodes signatures in Base58 with leading zeros', () => {
    expect(encodeBase58(new Uint8Array([]))).toBe('');
    expect(encodeBase58(new TextEncoder().encode('hello world'))).toBe('StV1DL6CwTryKyV');
    expect(encodeBase58(new Uint8Array([0, 0, 1]))).toBe('112');
  });
});

describe('desktop-only notice', () => {
  it('appears below 1280 px and disappears when the window grows', () => {
    const media = mockMatchMedia(true);
    render(<DesktopOnlyNotice />);

    expect(screen.getByTestId('desktop-only-notice')).toHaveTextContent(messages.app.desktopOnly);
    act(() => media.change(false));
    expect(screen.queryByTestId('desktop-only-notice')).toBeNull();
  });

  it('stays hidden on wide screens', () => {
    mockMatchMedia(false);
    render(<DesktopOnlyNotice />);
    expect(screen.queryByTestId('desktop-only-notice')).toBeNull();
  });
});
