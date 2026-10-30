import { ApiError } from '@/lib/api/client';
import type { GuestApi, GuestSession } from '@/lib/api/guest-api';
import { encodeBase58 } from '@/lib/base58';
import type { WalletPort } from '@/features/auth/wallets';

/**
 * F1: connect, request the challenge (with Idempotency-Key), sign the EXACT message in the wallet, verify. The
 * signature only serves to sign in and never authorises trading.
 */
export async function signInWithWallet(wallet: WalletPort, api: GuestApi): Promise<GuestSession> {
  const { address } = await wallet.connect();
  const challenge = await api.requestChallenge(address);
  const signature = await wallet.signMessage(new TextEncoder().encode(challenge.message));
  return api.verifyLogin({
    walletAddress: address,
    message: challenge.message,
    signature: encodeBase58(signature),
    provider: wallet.provider,
  });
}

/** Maps any failure of the flow to a key of the pt-BR rejection messages. */
export function rejectionKey(failure: unknown): string {
  if (failure instanceof ApiError) {
    if (failure.code === 'RATE_LIMITED') {
      return 'RATE_LIMITED';
    }
    if (failure.status === 0) {
      return 'NETWORK';
    }
    return failure.reason ?? 'GENERIC';
  }
  if (
    failure instanceof Error &&
    /reject|cancel|denied/i.test(`${failure.name} ${failure.message}`)
  ) {
    return 'USER_CANCELLED';
  }
  return 'GENERIC';
}

/** Only same-app relative paths are accepted as the screen to return to (no open redirect). */
export function safeOrigin(value: string | null | undefined): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.includes('\\')) {
    return '/scanner';
  }
  return value;
}
