import { getWallets } from '@wallet-standard/app';
import type { Wallet, WalletAccount } from '@wallet-standard/base';
import type { StandardConnectFeature } from '@wallet-standard/features';
import type { SolanaSignMessageFeature } from '@solana/wallet-standard-features';

export type WalletProvider = 'PHANTOM' | 'SOLFLARE';

/**
 * What the login needs from a browser wallet. The app only ever sees the PUBLIC address and the signature; the
 * wallet extension keeps the key (FR1.5).
 */
export interface WalletPort {
  name: string;
  provider: WalletProvider;
  icon?: string;
  connect(): Promise<{ address: string }>;
  signMessage(message: Uint8Array): Promise<Uint8Array>;
}

type StandardRegistry = Pick<ReturnType<typeof getWallets>, 'get' | 'on'>;

export function providerOf(name: string): WalletProvider | null {
  const lower = name.toLowerCase();
  if (lower.includes('solflare')) {
    return 'SOLFLARE';
  }
  if (lower.includes('phantom')) {
    return 'PHANTOM';
  }
  return null;
}

/** Adapts a Wallet Standard wallet that supports connect + Solana signMessage (Phantom and Solflare alike). */
export function toWalletPort(wallet: Wallet): WalletPort | null {
  const provider = providerOf(wallet.name);
  const connectFeature = wallet.features['standard:connect'] as
    | StandardConnectFeature['standard:connect']
    | undefined;
  const signFeature = wallet.features['solana:signMessage'] as
    | SolanaSignMessageFeature['solana:signMessage']
    | undefined;
  if (!provider || !connectFeature || !signFeature) {
    return null;
  }
  let account: WalletAccount | undefined;
  return {
    name: wallet.name,
    provider,
    icon: wallet.icon,
    async connect() {
      const { accounts } = await connectFeature.connect();
      account = accounts[0];
      if (!account) {
        throw new Error('The wallet returned no account');
      }
      return { address: account.address };
    },
    async signMessage(message: Uint8Array) {
      if (!account) {
        throw new Error('Connect the wallet before signing');
      }
      const [output] = await signFeature.signMessage({ account, message });
      if (!output) {
        throw new Error('The wallet returned no signature');
      }
      return output.signature;
    },
  };
}

/** Lists compatible wallets now and calls {@code onChange} when wallets register later. */
export function watchWallets(
  onChange: (wallets: WalletPort[]) => void,
  registry: StandardRegistry = getWallets(),
): () => void {
  const publish = () =>
    onChange(
      registry
        .get()
        .map(toWalletPort)
        .filter((w): w is WalletPort => w !== null),
    );
  publish();
  const offRegister = registry.on('register', publish);
  const offUnregister = registry.on('unregister', publish);
  return () => {
    offRegister();
    offUnregister();
  };
}
