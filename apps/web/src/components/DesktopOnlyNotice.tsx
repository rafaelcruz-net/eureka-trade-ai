'use client';

import { useEffect, useState } from 'react';
import { messages } from '@/lib/i18n/messages';

export const DESKTOP_MIN_WIDTH = 1280;

/** Shows the desktop-only notice below 1280 px (NFR14.1); the rest of the app stays usable. */
export function DesktopOnlyNotice() {
  const [narrow, setNarrow] = useState(false);

  useEffect(() => {
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
