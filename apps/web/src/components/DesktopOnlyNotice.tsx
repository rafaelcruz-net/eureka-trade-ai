'use client';

import { useEffect, useState } from 'react';
import { messages } from '@/lib/i18n/messages';

export const DESKTOP_MIN_WIDTH = 1280;

/** u1-legal-documents (BR1.13): the public legal pages adapt from 320 px on and never show the desktop-only notice. */
export const LEGAL_PATHS = ['/termos', '/privacidade'];

export function isLegalPath(pathname: string): boolean {
  return LEGAL_PATHS.some((path) => pathname === path || pathname.startsWith(`${path}/`));
}

/** Shows the desktop-only notice below 1280 px (NFR14.1); the rest of the app stays usable. */
export function DesktopOnlyNotice() {
  const [narrow, setNarrow] = useState(false);

  useEffect(() => {
    if (isLegalPath(window.location.pathname)) {
      return undefined;
    }
    const query = window.matchMedia(`(max-width: ${DESKTOP_MIN_WIDTH - 1}px)`);
    const update = () => setNarrow(query.matches);
    update();
    query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, []);

  if (!narrow) {
    return null;
  }
  return (
    <p role="status" className="notice notice-warning" data-testid="desktop-only-notice">
      {messages.app.desktopOnly}
    </p>
  );
}
