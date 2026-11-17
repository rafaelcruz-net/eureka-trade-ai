'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type { RealAdminUser } from '@/lib/api/admin-api';
import { toAdminRealError, type AdminRealError } from '@/features/real/errors';
import { formatSol, toBaseUnitsOrUnknown, isUnknown } from '@/features/real/money';
import { adminRealMessages as text } from '@/features/real/messages';
import { abbreviate, RealLoadError, RealSection } from '@/features/real/components/RealSection';

const copy = text.users;

/** A lamport text of the API, formatted from bigint; a missing one is "sem dado", never zero. */
export function solText(lamports: string | undefined): string {
  const value = toBaseUnitsOrUnknown(lamports);
  return isUnknown(value) ? text.noData : `${formatSol(value)} SOL`;
}

type State =
  | { kind: 'loading' }
  | { kind: 'error'; error: AdminRealError }
  | { kind: 'ready'; items: RealAdminUser[]; nextCursor: string | null; loadingMore: boolean };

/**
 * 261113-trading-real-bot U6, A1 `/real` (US7.1; BR9.1, BR9.5; U6-NFR3.6): the people with a bot wallet, 20 per page by
 * cursor: mode, cap, REAL balance, release and divergences, each row with its detail. The main wallet never shows here.
 */
export function RealUsersScreen() {
  const api = useApi();
  const [state, setState] = useState<State>({ kind: 'loading' });

  const first = useCallback(() => {
    setState({ kind: 'loading' });
    api.listRealUsers(null).then(
      (page) =>
        setState({
          kind: 'ready',
          items: page.items ?? [],
          nextCursor: page.nextCursor ?? null,
          loadingMore: false,
        }),
      (failure: unknown) => setState({ kind: 'error', error: toAdminRealError(failure) }),
    );
  }, [api]);
  useEffect(first, [first]);

  function more() {
    if (state.kind !== 'ready' || !state.nextCursor) {
      return;
    }
    const current = state;
    setState({ ...current, loadingMore: true });
    api.listRealUsers(current.nextCursor).then(
      (page) =>
        setState({
          kind: 'ready',
          items: [...current.items, ...(page.items ?? [])],
          nextCursor: page.nextCursor ?? null,
          loadingMore: false,
        }),
      (failure: unknown) => setState({ kind: 'error', error: toAdminRealError(failure) }),
    );
  }

  return (
    <RealSection title={copy.title} testId="admin-real-users">
      {state.kind === 'loading' && <p role="status">{text.loading}</p>}
      {state.kind === 'error' && (
        <RealLoadError
          error={state.error}
          message={copy.error}
          onRetry={first}
          testId="admin-real-users-error"
        />
      )}
      {state.kind === 'ready' && state.items.length === 0 && (
        <p data-testid="admin-real-users-empty">{copy.empty}</p>
      )}
      {state.kind === 'ready' && state.items.length > 0 && (
        <table data-testid="admin-real-users-table">
          <thead>
            <tr>
              {Object.values(copy.columns).map((column) => (
                <th key={column} scope="col">
                  {column}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {state.items.map((user) => (
              <tr key={user.userId} data-testid={`admin-real-user-${user.userId}`}>
                <td>{abbreviate(user.userId)}</td>
                <td>{text.modes[user.mode ?? ''] ?? user.mode ?? text.noData}</td>
                <td>{solText(user.capLamports)}</td>
                <td>{solText(user.balance?.totalLamports)}</td>
                <td data-testid={`admin-real-user-release-${user.userId}`}>
                  {text.release[user.release?.state ?? 'NONE'] ?? user.release?.state}
                </td>
                <td>
                  {user.openDivergences && user.openDivergences.length > 0
                    ? copy.divergences(user.openDivergences.length)
                    : copy.noDivergence}
                </td>
                <td>
                  <Link
                    href={`/real/usuarios/${encodeURIComponent(user.userId ?? '')}`}
                    data-testid={`admin-real-user-detail-${user.userId}`}
                  >
                    {copy.detail}
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {state.kind === 'ready' && state.nextCursor && (
        <button
          type="button"
          className="secondary"
          disabled={state.loadingMore}
          onClick={more}
          data-testid="admin-real-users-more"
        >
          {text.more}
        </button>
      )}
    </RealSection>
  );
}
