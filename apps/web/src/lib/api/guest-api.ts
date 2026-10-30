import type { ApiClient } from '@/lib/api/client';
import type { components } from '@/lib/api/schema';

export type ChallengeResponse = components['schemas']['ChallengeResponse'];
export type VerifyRequest = components['schemas']['VerifyRequest'];
export type GuestSession = components['schemas']['GuestSession'];
export type ScannerPage = components['schemas']['ScannerPage'];
export type ScannerRow = components['schemas']['ScannerRow'];
export type MarketValue = components['schemas']['MarketValue'];
export type KnownValue = components['schemas']['KnownValue'];
export type UnknownValue = components['schemas']['UnknownValue'];
export type SourceStatus = components['schemas']['SourceStatus'];

/** Typed calls of contracts C1 and C2 (types generated from the API contract, never written by hand). */
export function guestApi(client: ApiClient) {
  return {
    requestChallenge(walletAddress: string) {
      return client.request<ChallengeResponse>('/api/v1/auth/challenge', {
        method: 'POST',
        body: { walletAddress },
        idempotent: true,
      });
    },
    verifyLogin(body: VerifyRequest) {
      return client.request<GuestSession>('/api/v1/auth/verify', {
        method: 'POST',
        body,
        idempotent: true,
        credentials: true,
      });
    },
    refreshSession() {
      return client.request<GuestSession>('/api/v1/auth/refresh', {
        method: 'POST',
        credentials: true,
      });
    },
    logout() {
      return client.request<void>('/api/v1/auth/logout', {
        method: 'POST',
        idempotent: true,
        credentials: true,
        authenticated: true,
      });
    },
    listTokens(cursor: string | null, limit = 50) {
      const query = new URLSearchParams({ limit: String(limit) });
      if (cursor) {
        query.set('cursor', cursor);
      }
      return client.request<ScannerPage>(`/api/v1/scanner/tokens?${query.toString()}`, {
        authenticated: true,
      });
    },
  };
}

export type GuestApi = ReturnType<typeof guestApi>;
