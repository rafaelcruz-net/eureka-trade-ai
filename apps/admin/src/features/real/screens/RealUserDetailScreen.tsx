'use client';

import { useCallback, useRef, useState } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type { RealAdminReleaseRequest, RealAdminUser } from '@/lib/api/admin-api';
import { browserTimeZone, dateTime } from '@/lib/format';
import { useAdminAction } from '@/features/real/hooks/useAdminAction';
import { useAdminLoad } from '@/features/real/hooks/useAdminLoad';
import { formatSol, parseSol, toBaseUnits } from '@/features/real/money';
import { adminRealMessages as text } from '@/features/real/messages';
import { AdminActionDialog } from '@/features/real/components/AdminActionDialog';
import { abbreviate, RealLoadError, RealSection } from '@/features/real/components/RealSection';
import { solText } from '@/features/real/screens/RealUsersScreen';

const copy = text.detail;
type Network = RealAdminReleaseRequest['network'];
const NETWORKS: readonly Network[] = ['LOCALNET', 'DEVNET', 'MAINNET'];
type Action = 'release' | 'revoke' | 'cap' | null;

/**
 * 261113-trading-real-bot U6, A1 `/real/usuarios/[id]` (US7.1, US7.2; BR9.1 to BR9.3, BR9.5; U6-NFR1.8, U6-NFR1.11):
 * one person with the main wallet abbreviated and no key field; "Liberar", "Desligar REAL" and "Baixar teto", each
 * through the dialog with the reason and the confirmation, then the person is read again.
 */
export function RealUserDetailScreen({ userId, timeZone }: { userId: string; timeZone?: string }) {
  const api = useApi();
  const { load, reload } = useAdminLoad(useCallback(() => api.getRealUser(userId), [api, userId]));
  return (
    <RealSection title={copy.title} testId="admin-real-detail">
      {load.kind === 'loading' && <p role="status">{text.loading}</p>}
      {load.kind === 'error' && (
        <RealLoadError
          error={load.error}
          message={copy.error}
          onRetry={reload}
          testId="admin-real-detail-error"
        />
      )}
      {load.kind === 'ready' && (
        <Detail user={load.data} userId={userId} timeZone={timeZone} onChanged={reload} />
      )}
    </RealSection>
  );
}

function Detail({
  user,
  userId,
  timeZone,
  onChanged,
}: {
  user: RealAdminUser;
  userId: string;
  timeZone?: string;
  onChanged: () => void;
}) {
  const api = useApi();
  const zone = timeZone ?? browserTimeZone();
  const [action, setAction] = useState<Action>(null);
  const [network, setNetwork] = useState<Network>('LOCALNET');
  const [cap, setCap] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const opener = useRef<HTMLElement | null>(null);
  const currentCap = user.capLamports ? toBaseUnits(user.capLamports) : null;
  const parsedCap = parseSol(cap);
  const capReady = parsedCap.ok && (currentCap === null || parsedCap.lamports < currentCap);

  const release = useAdminAction(
    (body: RealAdminReleaseRequest, key: string) => api.releaseRealUser(userId, body, key),
    api.newKey,
  );
  const revoke = useAdminAction(
    (body: { reason: string }, key: string) => api.revokeRealUser(userId, body, key),
    api.newKey,
  );
  const lower = useAdminAction(
    (body: { capLamports: string; reason: string }, key: string) =>
      api.lowerRealCap(userId, body, key),
    api.newKey,
  );

  function open(next: Action, event: React.MouseEvent<HTMLElement>) {
    opener.current = event.currentTarget;
    setNotice(null);
    setAction(next);
  }

  function finished(result: unknown) {
    if (result) {
      setAction(null);
      setNotice(copy.done);
      onChanged();
    }
  }

  function close() {
    release.reset();
    revoke.reset();
    lower.reset();
    setAction(null);
  }

  return (
    <>
      <dl data-testid="admin-real-detail-data">
        <dt>{copy.mainWallet}</dt>
        <dd data-testid="admin-real-detail-main">{user.mainWalletShort ?? text.noData}</dd>
        <dt>{copy.botWallet}</dt>
        <dd>{abbreviate(user.botWalletAddress)}</dd>
        <dt>{copy.mode}</dt>
        <dd>{text.modes[user.mode ?? ''] ?? user.mode ?? text.noData}</dd>
        <dt>{copy.cap}</dt>
        <dd data-testid="admin-real-detail-cap">{solText(user.capLamports)}</dd>
        <dt>{copy.balance}</dt>
        <dd>{solText(user.balance?.totalLamports)}</dd>
        <dt>{copy.release}</dt>
        <dd data-testid="admin-real-detail-release">
          {text.release[user.release?.state ?? 'NONE'] ?? user.release?.state}
          {user.release?.effectiveAt ? ` · ${dateTime(user.release.effectiveAt, zone)}` : ''}
        </dd>
        <dt>{copy.pauses}</dt>
        <dd>
          {user.pausedReasons && user.pausedReasons.length > 0
            ? user.pausedReasons.join(', ')
            : copy.noPauses}
        </dd>
      </dl>
      <h2>{copy.orders}</h2>
      {(user.recentOrders ?? []).length === 0 ? (
        <p>{copy.noOrders}</p>
      ) : (
        <ul data-testid="admin-real-detail-orders">
          {(user.recentOrders ?? []).map((order) => (
            <li key={order.realOrderId}>
              {order.createdAt ? dateTime(order.createdAt, zone) : text.noData} · {order.side} ·{' '}
              {abbreviate(order.mint)} · {order.state}
            </li>
          ))}
        </ul>
      )}
      <h2>{copy.actions}</h2>
      <p>
        <button
          type="button"
          onClick={(event) => open('release', event)}
          data-testid="admin-real-release"
        >
          {copy.releaseAction}
        </button>{' '}
        <button
          type="button"
          className="secondary"
          onClick={(event) => open('revoke', event)}
          data-testid="admin-real-revoke"
        >
          {copy.revokeAction}
        </button>{' '}
        <button
          type="button"
          className="secondary"
          onClick={(event) => open('cap', event)}
          data-testid="admin-real-cap"
        >
          {copy.capAction}
        </button>
      </p>
      {notice && (
        <p role="status" data-testid="admin-real-detail-notice">
          {notice}
        </p>
      )}
      {action === 'release' && (
        <AdminActionDialog
          title={copy.releaseTitle}
          effect={copy.releaseEffect(network)}
          testId="admin-real-release-dialog"
          state={release.state}
          returnFocusTo={opener.current}
          onClose={close}
          onSubmit={(reason) => void release.submit({ network, reason }).then(finished)}
        >
          <p>
            <label htmlFor="admin-real-network">{copy.network}</label>{' '}
            <select
              id="admin-real-network"
              value={network}
              onChange={(event) => setNetwork(event.target.value as Network)}
              data-testid="admin-real-network"
            >
              {NETWORKS.map((item) => (
                <option key={item} value={item}>
                  {item}
                </option>
              ))}
            </select>
          </p>
        </AdminActionDialog>
      )}
      {action === 'revoke' && (
        <AdminActionDialog
          title={copy.revokeTitle}
          effect={copy.revokeEffect}
          testId="admin-real-revoke-dialog"
          state={revoke.state}
          returnFocusTo={opener.current}
          onClose={close}
          onSubmit={(reason) => void revoke.submit({ reason }).then(finished)}
        />
      )}
      {action === 'cap' && (
        <AdminActionDialog
          title={copy.capTitle}
          effect={
            parsedCap.ok ? copy.capEffect(`${formatSol(parsedCap.lamports)} SOL`) : copy.capInvalid
          }
          testId="admin-real-cap-dialog"
          state={lower.state}
          ready={capReady}
          returnFocusTo={opener.current}
          onClose={close}
          onSubmit={(reason) =>
            parsedCap.ok &&
            void lower.submit({ capLamports: parsedCap.lamports.toString(), reason }).then(finished)
          }
        >
          <p>
            <label htmlFor="admin-real-cap-input">{copy.capLabel}</label>{' '}
            <input
              id="admin-real-cap-input"
              inputMode="decimal"
              value={cap}
              onChange={(event) => setCap(event.target.value)}
              data-testid="admin-real-cap-input"
            />
          </p>
          {cap !== '' && !capReady && <p role="alert">{copy.capInvalid}</p>}
        </AdminActionDialog>
      )}
    </>
  );
}
