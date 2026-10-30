'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useApi } from '@/lib/api/ApiProvider';
import { messages } from '@/lib/i18n/messages';
import { rejectionKey, safeOrigin, signInWithWallet } from '@/features/auth/login-flow';
import { useSession } from '@/features/auth/SessionProvider';
import { watchWallets, type WalletPort } from '@/features/auth/wallets';

type State = { kind: 'idle' } | { kind: 'signing' } | { kind: 'rejected'; key: string };

/**
 * S1 Entrada: connect Phantom or Solflare, sign, and go back to the screen of origin. States: no wallet installed,
 * signing, rejected (text messages) and session expired. There is no field for a secret phrase or a key.
 */
export function LoginScreen({
  walletSource = watchWallets,
}: {
  walletSource?: typeof watchWallets;
}) {
  const api = useApi();
  const session = useSession();
  const router = useRouter();
  const params = useSearchParams();
  const [wallets, setWallets] = useState<WalletPort[]>([]);
  const [state, setState] = useState<State>({ kind: 'idle' });
  const heading = useRef<HTMLHeadingElement>(null);
  const origin = safeOrigin(params.get('origem'));
  const expired = params.get('expirada') === '1';

  useEffect(() => walletSource(setWallets), [walletSource]);
  useEffect(() => heading.current?.focus(), []);

  async function connect(wallet: WalletPort) {
    setState({ kind: 'signing' });
    try {
      session.signIn(await signInWithWallet(wallet, api));
      router.replace(origin);
    } catch (failure) {
      setState({ kind: 'rejected', key: rejectionKey(failure) });
    }
  }

  const text = messages.login;
  return (
    <main id="conteudo" aria-labelledby="login-title">
      <h1 id="login-title" ref={heading} tabIndex={-1}>
        {text.title}
      </h1>
      {expired && (
        <p role="alert" className="notice notice-warning" data-testid="login-session-expired">
          {text.sessionExpired}
        </p>
      )}
      <p>{text.intro}</p>
      <p className="muted">{text.neverAskSecret}</p>

      {wallets.length === 0 ? (
        <section role="status" className="notice" data-testid="login-no-wallet">
          <p>{text.noWallet}</p>
          <p className="muted">{text.noWalletHelp}</p>
        </section>
      ) : (
        <ul aria-label="Carteiras disponíveis" data-testid="login-wallet-list">
          {wallets.map((wallet) => (
            <li key={wallet.name}>
              <button
                type="button"
                data-testid={`login-connect-${wallet.provider.toLowerCase()}`}
                disabled={state.kind === 'signing'}
                onClick={() => void connect(wallet)}
              >
                {text.connectWith(wallet.name)}
              </button>
            </li>
          ))}
        </ul>
      )}

      {state.kind === 'signing' && (
        <p role="status" data-testid="login-signing">
          {text.signing}
        </p>
      )}
      {state.kind === 'rejected' && (
        <div role="alert" className="notice notice-error" data-testid="login-rejected">
          <h2>{text.rejectedTitle}</h2>
          <p data-testid="login-rejected-reason">
            {text.rejected[state.key] ?? text.rejected.GENERIC}
          </p>
          <button
            type="button"
            className="secondary"
            data-testid="login-retry"
            onClick={() => setState({ kind: 'idle' })}
          >
            {text.tryAgain}
          </button>
        </div>
      )}
    </main>
  );
}
