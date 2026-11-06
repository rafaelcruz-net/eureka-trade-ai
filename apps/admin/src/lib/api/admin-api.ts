import { ApiError, type ApiClient } from '@/lib/api/client';
import type { components } from '@/lib/api/schema';
import { tokenStore } from '@/lib/auth/token-store';

export type ChallengeResponse = components['schemas']['ChallengeResponse'];
export type AdminVerifyRequest = components['schemas']['AdminVerifyRequest'];
export type AdminSession = components['schemas']['AdminSession'];
export type SystemStatus = components['schemas']['SystemStatus'];
export type CountPart = components['schemas']['CountPart'];
export type CountMapPart = components['schemas']['CountMapPart'];
export type SourcesPart = components['schemas']['SourcesPart'];
export type KillSwitchPart = components['schemas']['KillSwitchPart'];
export type SourceState = components['schemas']['SourceState'];
export type KillSwitch = components['schemas']['KillSwitch'];
export type KillSwitchRequest = components['schemas']['KillSwitchRequest'];
export type Invite = components['schemas']['Invite'];
export type InvitePage = components['schemas']['InvitePage'];
export type IssueInviteRequest = components['schemas']['IssueInviteRequest'];
export type TenantOption = components['schemas']['TenantOption'];
export type FeedbackItem = components['schemas']['FeedbackItem'];
export type FeedbackPage = components['schemas']['FeedbackPage'];

/** Query string with only the parameters that have a value. */
function query(values: Record<string, string | number | null | undefined>): string {
  const params = new URLSearchParams();
  for (const [name, value] of Object.entries(values)) {
    if (value !== undefined && value !== null && value !== '') {
      params.set(name, String(value));
    }
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}

/**
 * The typed administrative API (C8, types generated from openapi-admin.yaml only, ADR-009). Every authenticated call
 * that answers 401 tries ONE renewal through the administrator cookie and repeats the call once; when the renewal fails
 * the original 401 reaches the screen, which goes back to the entry.
 */
export function adminApi(client: ApiClient) {
  async function withRenewal<T>(call: () => Promise<T>): Promise<T> {
    try {
      return await call();
    } catch (failure) {
      if (!(failure instanceof ApiError) || failure.status !== 401) {
        throw failure;
      }
      let renewed: AdminSession;
      try {
        renewed = await client.request<AdminSession>('/api/v1/admin/auth/refresh', {
          method: 'POST',
          credentials: true,
        });
      } catch {
        throw failure;
      }
      tokenStore.set(renewed.accessToken);
      return call();
    }
  }

  return {
    newKey(): string {
      return client.newId();
    },
    requestChallenge(walletAddress: string) {
      return client.request<ChallengeResponse>('/api/v1/admin/auth/challenge', {
        method: 'POST',
        body: { walletAddress },
        idempotencyKey: client.newId(),
      });
    },
    verifyLogin(body: AdminVerifyRequest) {
      return client.request<AdminSession>('/api/v1/admin/auth/verify', {
        method: 'POST',
        body,
        idempotencyKey: client.newId(),
        credentials: true,
      });
    },
    refreshSession() {
      return client.request<AdminSession>('/api/v1/admin/auth/refresh', {
        method: 'POST',
        credentials: true,
      });
    },
    logout() {
      return client.request<void>('/api/v1/admin/auth/logout', {
        method: 'POST',
        idempotencyKey: client.newId(),
        credentials: true,
        authenticated: true,
      });
    },
    getSystemStatus() {
      return withRenewal(() =>
        client.request<SystemStatus>('/api/v1/admin/system-status', { authenticated: true }),
      );
    },
    getKillSwitch() {
      return withRenewal(() =>
        client.request<KillSwitch>('/api/v1/admin/kill-switch', { authenticated: true }),
      );
    },
    changeKillSwitch(body: KillSwitchRequest, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<KillSwitch>('/api/v1/admin/kill-switch', {
          method: 'PUT',
          body,
          idempotencyKey,
          authenticated: true,
        }),
      );
    },
    listInvites(cursor: string | null) {
      return withRenewal(() =>
        client.request<InvitePage>(`/api/v1/admin/invites${query({ cursor, limit: 20 })}`, {
          authenticated: true,
        }),
      );
    },
    issueInvite(body: IssueInviteRequest, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<Invite>('/api/v1/admin/invites', {
          method: 'POST',
          body,
          idempotencyKey,
          authenticated: true,
        }),
      );
    },
    revokeInvite(inviteId: string, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<Invite>(`/api/v1/admin/invites/${encodeURIComponent(inviteId)}/revoke`, {
          method: 'POST',
          idempotencyKey,
          authenticated: true,
        }),
      );
    },
    listTenants() {
      return withRenewal(() =>
        client.request<TenantOption[]>('/api/v1/admin/tenants', { authenticated: true }),
      );
    },
    listFeedback(cursor: string | null) {
      return withRenewal(() =>
        client.request<FeedbackPage>(`/api/v1/admin/feedback${query({ cursor, limit: 20 })}`, {
          authenticated: true,
        }),
      );
    },
  };
}

export type AdminApi = ReturnType<typeof adminApi>;
