'use client';

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { navMessages } from '@/features/dashboard/messages';

/**
 * The "Avisos" region of the shell (BR6.3, BR7.1 to BR7.3; CT-UX-8): a polite live region that announces what the
 * screens send through {@link useAnnounce}. Messages received within {@link JOIN_WINDOW_MS} of the first one become
 * ONE announcement (BR7.2). Announcing never moves the focus (BR7.3).
 */
export const JOIN_WINDOW_MS = 2_000;

type Announce = (text: string) => void;

const AnnounceContext = createContext<Announce>(() => undefined);

/** Sends a message to the "Avisos" region; outside the shell it does nothing. */
export function useAnnounce(): Announce {
  return useContext(AnnounceContext);
}

export function NoticesProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState('');
  const buffer = useRef<string[]>([]);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const announce = useCallback<Announce>((text) => {
    if (!text) {
      return;
    }
    buffer.current.push(text);
    if (timer.current !== null) {
      return;
    }
    timer.current = setTimeout(() => {
      timer.current = null;
      const joined = [...new Set(buffer.current)].join(' · ');
      buffer.current = [];
      setMessage(joined);
    }, JOIN_WINDOW_MS);
  }, []);

  useEffect(
    () => () => {
      if (timer.current !== null) {
        clearTimeout(timer.current);
      }
    },
    [],
  );

  const value = useMemo(() => announce, [announce]);
  return (
    <AnnounceContext.Provider value={value}>
      <NoticesContext.Provider value={message}>{children}</NoticesContext.Provider>
    </AnnounceContext.Provider>
  );
}

const NoticesContext = createContext('');

/** The region itself, placed by the shell between the header and the main content. */
export function NoticesRegion() {
  const message = useContext(NoticesContext);
  return (
    <div
      role="region"
      aria-label={navMessages.notices}
      aria-live="polite"
      className="app-notices"
      data-testid="app-notices-region"
    >
      {message}
    </div>
  );
}
