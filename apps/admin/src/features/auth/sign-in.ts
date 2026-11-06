import { ApiError } from '@/lib/api/client';
import type { AdminApi, AdminSession } from '@/lib/api/admin-api';
import { encodeBase58 } from '@/lib/base58';
import type { WalletPort } from '@/features/auth/wallets';

/**
 * F1: connect, request the administrator challenge, sign the EXACT message in the wallet, verify. The signature serves
 * only this entry and never authorises an operation (BR1.2).
 */
export async function signInAsAdministrator(
  wallet: WalletPort,
  api: AdminApi,
): Promise<AdminSession> {
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

/** What the screen does with a failure: the restricted screen for 403, otherwise a key of the pt-BR reasons. */
export function outcomeOf(failure: unknown): 'RESTRICTED' | string {
  if (failure instanceof ApiError) {
    if (failure.status === 403) {
      return 'RESTRICTED';
    }
    if (failure.status === 429) {
      return 'RATE_LIMITED';
    }
    if (failure.status === 401) {
      return 'UNAUTHENTICATED';
    }
    if (failure.status === 0) {
      return 'NETWORK';
    }
    return 'GENERIC';
  }
  if (
    failure instanceof Error &&
    /reject|cancel|denied/i.test(`${failure.name} ${failure.message}`)
  ) {
    return 'USER_CANCELLED';
  }
  return 'GENERIC';
}
