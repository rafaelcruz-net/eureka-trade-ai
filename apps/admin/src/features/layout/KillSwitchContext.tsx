'use client';

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type { KillSwitch } from '@/lib/api/admin-api';

interface KillSwitchValue {
  killSwitch: KillSwitch | null;
  failed: boolean;
  refresh(): Promise<void>;
  replace(next: KillSwitch): void;
}

const KillSwitchContext = createContext<KillSwitchValue | null>(null);

/**
 * The state of the kill switch shared by the banner of every screen (BR7.1) and the kill switch screen: read once when
 * the layout opens and replaced by the answer of every change.
 */
export function KillSwitchProvider({ children }: { children: ReactNode }) {
  const api = useApi();
  const [killSwitch, setKillSwitch] = useState<KillSwitch | null>(null);
  const [failed, setFailed] = useState(false);

  const refresh = useCallback(async () => {
    try {
      setKillSwitch(await api.getKillSwitch());
      setFailed(false);
    } catch {
      setFailed(true);
    }
  }, [api]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return (
    <KillSwitchContext.Provider value={{ killSwitch, failed, refresh, replace: setKillSwitch }}>
      {children}
    </KillSwitchContext.Provider>
  );
}

export function useKillSwitch(): KillSwitchValue {
  const value = useContext(KillSwitchContext);
  if (!value) {
    throw new Error('useKillSwitch must be used inside KillSwitchProvider');
  }
  return value;
}
