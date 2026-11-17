'use client';

import { useEffect, useMemo, useRef, type ReactNode } from 'react';
import { useRouter } from 'next/navigation';
import { useApi, useQuietApi } from '@/lib/api/ApiProvider';
import type { GuestApi } from '@/lib/api/guest-api';
import { AppShell } from '@/components/shell/AppShell';
import { useSession } from '@/features/auth/SessionProvider';
import { useRequireSession } from '@/features/auth/useRequireSession';
import { realReads, type BotWallet, type RealReads, type Release } from '@/features/real/api';
import { useLoad } from '@/features/real/hooks/useLoad';
import { realMessages as text } from '@/features/real/messages';
import { RealBadge } from '@/features/real/components/RealBadge';
import { RealErrorNotice } from '@/features/real/components/RealErrorNotice';

export const ACTIVATION_PATH = '/real/ativacao';

export interface RealAreaContext {
  wallet: BotWallet;
  release: { network: string; release: Release; exitsEnabled?: boolean } | null;
  api: GuestApi;
  reads: RealReads;
  reloadWallet(): void;
  setWallet(wallet: BotWallet): void;
}

/** BR1.1: the REAL is off (403 REAL_DISABLED), or the API is an older one without the read of the release (404). */
export function isRealUnavailable(code: string, status: number): boolean {
  return code === 'REAL_DISABLED' || (status === 404 && code !== 'BOT_WALLET_NOT_ACTIVE');
}

/**
 * 261113-trading-real-bot U6 (BR1.1, BR1.2, BR1.4, BR8.1): the shell of every screen of the REAL area with a bot wallet.
 *
 * - The release is read first; `403 REAL_DISABLED` shows "O REAL ainda não está disponível" (S1).
 * - Without an active bot wallet (`404` of the wallet) the screen goes to the activation (S43).
 * - `withdrawal` (the screen of deposit and withdrawal, BR1.4, BR6.4): the release is NOT read, so the withdrawal works
 *   with the REAL off, without a release and without the new acceptance; the API used is the one without the handler of
 *   the reacceptance lock.
 */
export function RealArea({
  origin,
  title,
  titleId,
  withdrawal = false,
  children,
}: {
  origin: string;
  title: string;
  titleId: string;
  withdrawal?: boolean;
  children: (context: RealAreaContext) => ReactNode;
}) {
  const status = useRequireSession(origin);
  const { expire } = useSession();
  const router = useRouter();
  const mainApi = useApi();
  const quietApi = useQuietApi();
  const api = withdrawal ? quietApi : mainApi;
  const reads = useMemo(() => realReads(api), [api]);
  const authenticated = status === 'authenticated';
  const release = useLoad(reads.release, authenticated && !withdrawal);
  const releaseSettled = withdrawal || release.load.kind === 'ready';
  const wallet = useLoad(reads.wallet, authenticated && releaseSettled);
  // A reload of the wallet (after a withdrawal) keeps the screen with the last wallet read, so its state stays.
  const lastWallet = useRef<BotWallet | null>(null);
  if (wallet.load.kind === 'ready') {
    lastWallet.current = wallet.load.data;
  }

  const walletMissing =
    wallet.load.kind === 'error' &&
    wallet.load.error.status === 404 &&
    wallet.load.error.code !== 'BOT_WALLET_NOT_ACTIVE';
  useEffect(() => {
    if (walletMissing) {
      router.replace(ACTIVATION_PATH);
    }
  }, [walletMissing, router]);

  const unauthorized =
    (release.load.kind === 'error' && release.load.error.status === 401) ||
    (wallet.load.kind === 'error' && wallet.load.error.status === 401);
  useEffect(() => {
    if (unauthorized) {
      expire();
    }
  }, [unauthorized, expire]);

  return (
    <AppShell current="real" titleId={titleId} className="real-area">
      <h1 id={titleId}>
        {title} <RealBadge testId={`${titleId}-badge`} />
      </h1>
      {body()}
    </AppShell>
  );

  function body(): ReactNode {
    if (release.load.kind === 'error') {
      const { error } = release.load;
      if (isRealUnavailable(error.code, error.status)) {
        return (
          <p role="status" className="notice" data-testid="real-unavailable">
            {text.unavailable}
          </p>
        );
      }
      return <RealErrorNotice error={error} onRetry={release.reload} testId="real-release-error" />;
    }
    if (wallet.load.kind === 'error') {
      if (walletMissing) {
        return null;
      }
      return (
        <RealErrorNotice
          error={wallet.load.error}
          onRetry={wallet.reload}
          testId="real-wallet-error"
        />
      );
    }
    const shown = wallet.load.kind === 'ready' ? wallet.load.data : lastWallet.current;
    if (!shown || (!withdrawal && release.load.kind === 'loading')) {
      return (
        <p role="status" data-testid="real-loading">
          {text.loading}
        </p>
      );
    }
    return children({
      wallet: shown,
      release: release.load.kind === 'ready' ? release.load.data : null,
      api,
      reads,
      reloadWallet: wallet.reload,
      setWallet: wallet.set,
    });
  }
}
