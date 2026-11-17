'use client';

import { useCallback, useRef, useState } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import { browserTimeZone, dateTime } from '@/lib/format';
import { useAdminAction } from '@/features/real/hooks/useAdminAction';
import { useAdminLoad } from '@/features/real/hooks/useAdminLoad';
import { adminRealMessages as text } from '@/features/real/messages';
import { AdminActionDialog } from '@/features/real/components/AdminActionDialog';
import { abbreviate, RealLoadError, RealSection } from '@/features/real/components/RealSection';

const copy = text.tokens;
const MINT = /^[1-9A-HJ-NP-Za-km-z]{32,44}$/;

/**
 * 261113-trading-real-bot U6, A1 `/real/tokens` (US7.3; BR9.2, BR9.3): the tokens blocked for every bot wallet,
 * "+ Bloquear token" with the mint and the reason, and "Desbloquear" with the confirmation (the route has no body).
 */
export function RealTokensScreen({ timeZone }: { timeZone?: string }) {
  const api = useApi();
  const zone = timeZone ?? browserTimeZone();
  const { load, reload } = useAdminLoad(useCallback(() => api.listRealTokenBlocks(), [api]));
  const [dialog, setDialog] = useState<
    { kind: 'block' } | { kind: 'unblock'; mint: string } | null
  >(null);
  const [mint, setMint] = useState('');
  const opener = useRef<HTMLElement | null>(null);
  const block = useAdminAction(
    (body: { mint: string; reason: string }, key: string) => api.blockRealToken(body, key),
    api.newKey,
  );
  const unblock = useAdminAction(
    (target: string, key: string) => api.unblockRealToken(target, key),
    api.newKey,
  );

  function close() {
    block.reset();
    unblock.reset();
    setDialog(null);
  }

  function finished(result: unknown) {
    if (result) {
      setDialog(null);
      setMint('');
      reload();
    }
  }

  return (
    <RealSection title={copy.title} testId="admin-real-tokens">
      <p>
        <button
          type="button"
          onClick={(event) => {
            opener.current = event.currentTarget;
            setDialog({ kind: 'block' });
          }}
          data-testid="admin-real-token-block"
        >
          {copy.block}
        </button>
      </p>
      {load.kind === 'loading' && <p role="status">{text.loading}</p>}
      {load.kind === 'error' && (
        <RealLoadError
          error={load.error}
          message={copy.error}
          onRetry={reload}
          testId="admin-real-tokens-error"
        />
      )}
      {load.kind === 'ready' && (load.data.items ?? []).length === 0 && (
        <p data-testid="admin-real-tokens-empty">{copy.empty}</p>
      )}
      {load.kind === 'ready' && (load.data.items ?? []).length > 0 && (
        <ul data-testid="admin-real-tokens-list">
          {(load.data.items ?? []).map((token) => (
            <li key={token.mint} data-testid={`admin-real-token-${token.mint}`}>
              <span className="mono">{abbreviate(token.mint)}</span> · {copy.reason}:{' '}
              {token.reason ?? text.noData}
              {token.blockedAt
                ? ` · ${copy.blockedAt} ${dateTime(token.blockedAt, zone)}`
                : ''}{' '}
              <button
                type="button"
                className="secondary"
                onClick={(event) => {
                  opener.current = event.currentTarget;
                  setDialog({ kind: 'unblock', mint: token.mint ?? '' });
                }}
                data-testid={`admin-real-token-unblock-${token.mint}`}
              >
                {copy.unblock}
              </button>
            </li>
          ))}
        </ul>
      )}
      {dialog?.kind === 'block' && (
        <AdminActionDialog
          title={copy.blockTitle}
          effect={copy.blockEffect}
          testId="admin-real-token-block-dialog"
          state={block.state}
          ready={MINT.test(mint.trim())}
          returnFocusTo={opener.current}
          onClose={close}
          onSubmit={(reason) => void block.submit({ mint: mint.trim(), reason }).then(finished)}
        >
          <p>
            <label htmlFor="admin-real-token-mint">{copy.mintLabel}</label>{' '}
            <input
              id="admin-real-token-mint"
              value={mint}
              autoComplete="off"
              onChange={(event) => setMint(event.target.value)}
              data-testid="admin-real-token-mint"
            />
          </p>
          {mint !== '' && !MINT.test(mint.trim()) && <p role="alert">{copy.mintInvalid}</p>}
        </AdminActionDialog>
      )}
      {dialog?.kind === 'unblock' && (
        <AdminActionDialog
          title={copy.unblockTitle}
          effect={copy.unblockEffect(abbreviate(dialog.mint))}
          testId="admin-real-token-unblock-dialog"
          state={unblock.state}
          reasonRequired={false}
          returnFocusTo={opener.current}
          onClose={close}
          onSubmit={() => void unblock.submit(dialog.mint).then(finished)}
        />
      )}
    </RealSection>
  );
}
