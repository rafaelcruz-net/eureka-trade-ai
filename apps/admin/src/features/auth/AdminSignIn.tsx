'use client';

import { useEffect, useState } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import { messages } from '@/lib/i18n/messages';
import { useSession } from '@/features/auth/AdminSessionProvider';
import { outcomeOf, signInAsAdministrator } from '@/features/auth/sign-in';
import { watchWallets, type WalletPort } from '@/features/auth/wallets';

const text = messages.signIn;

type WalletsSource = (onChange: (wallets: WalletPort[]) => void) => () => void;

/**
 * The administrator entry screen (F1; BR1.1, BR1.2, BR7.4): one button per compatible wallet; signing shows a waiting
 * state; a 403 shows "Acesso restrito" (the same answer for a wallet outside the list and a guest wallet); 401 and 429
 * show their reason. The app never asks for a secret phrase or a key.
 */
export function AdminSignIn({ walletsSource = watchWallets }: { walletsSource?: WalletsSource }) {
  const api = useApi();
  const session = useSession();
  const [wallets, setWallets] = useState<WalletPort[]>([]);
  const [busy, setBusy] = useState(false);
  const [reason, setReason] = useState<string | null>(null);

  useEffect(() => walletsSource(setWallets), [walletsSource]);

  async function enter(wallet: WalletPort) {
    setBusy(true);
    setReason(null);
    try {
      session.signedIn(await signInAsAdministrator(wallet, api));
    } catch (failure) {
      const outcome = outcomeOf(failure);
      if (outcome === 'RESTRICTED') {
        session.restrict();
        return;
      }
      setReason(text.rejected[outcome] ?? text.rejected.GENERIC ?? '');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main id="conteudo" className="sign-in" data-testid="admin-sign-in">
      <h1>{text.title}</h1>
      <p>{text.intro}</p>
      <p className="notice">{text.neverAskSecret}</p>
      {wallets.length === 0 ? (
        <p data-testid="admin-sign-in-no-wallet">{text.noWallet}</p>
      ) : (
        <ul className="wallets">
          {wallets.map((wallet) => (
            <li key={wallet.name}>
              <button
                type="button"
                disabled={busy}
                data-testid={`admin-sign-in-connect-${wallet.provider.toLowerCase()}`}
                onClick={() => void enter(wallet)}
              >
                {text.connectWith(wallet.name)}
              </button>
            </li>
          ))}
        </ul>
      )}
      {busy ? (
        <p role="status" data-testid="admin-sign-in-signing">
          {text.signing}
        </p>
      ) : null}
      {reason ? (
        <p role="alert" className="field-error" data-testid="admin-sign-in-error">
          {reason}
        </p>
      ) : null}
    </main>
  );
}
