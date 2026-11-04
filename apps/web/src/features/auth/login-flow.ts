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

/**
 * U3 F5: the same flow with the invitation code and the accepted version of the terms; on success the account is
 * created and the session opened in one operation. Every retry asks for a NEW challenge (BR3.6).
 */
export async function signInWithInvite(
  wallet: WalletPort,
  api: GuestApi,
  invite: { code: string; termsVersion: string },
): Promise<GuestSession> {
  const { address } = await wallet.connect();
  const challenge = await api.requestChallenge(address);
  const signature = await wallet.signMessage(new TextEncoder().encode(challenge.message));
  return api.verifyLogin({
    walletAddress: address,
    message: challenge.message,
    signature: encodeBase58(signature),
    provider: wallet.provider,
    inviteCode: invite.code,
    acceptedTermsVersion: invite.termsVersion,
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

/** Only internal guest routes are accepted as the screen to return to (BR4.2, no open redirect). */
export { safeOrigin } from '@/features/auth/origin';
