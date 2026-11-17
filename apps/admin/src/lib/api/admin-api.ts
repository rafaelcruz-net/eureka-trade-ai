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
// u4-data-requests (P2): the data subject requests.
export type AdminDataRequest = components['schemas']['AdminDataRequest'];
export type AdminDataRequestPage = components['schemas']['AdminDataRequestPage'];
export type RegisterEmailDataRequest = components['schemas']['RegisterEmailDataRequest'];
export type DataRequestTransition = components['schemas']['DataRequestTransition'];
export type VerificationView = components['schemas']['VerificationView'];
export type AccessSummary = components['schemas']['AccessSummary'];
export type AnonymizationResult = components['schemas']['AnonymizationResult'];
export type DataRequestStatus = AdminDataRequest['status'];
export type DataRequestType = AdminDataRequest['type'];
// 261113-trading-real-bot U6 (A1): the REAL routes R4 of the U5.
export type RealAdminUser = components['schemas']['RealAdminUser'];
export type RealAdminUserPage = components['schemas']['RealAdminUserPage'];
export type RealAdminRelease = components['schemas']['RealAdminRelease'];
export type RealAdminReleaseRequest = components['schemas']['RealAdminReleaseRequest'];
export type RealAdminRevocation = components['schemas']['RealAdminRevocation'];
export type RealAdminCap = components['schemas']['RealAdminCap'];
export type RealAdminCapRequest = components['schemas']['RealAdminCapRequest'];
export type RealAdminTokenList = components['schemas']['RealAdminTokenList'];
export type RealAdminBlockedToken = components['schemas']['RealAdminBlockedToken'];
export type RealAdminTokenBlockRequest = components['schemas']['RealAdminTokenBlockRequest'];
export type RealAdminUnblock = components['schemas']['RealAdminUnblock'];
export type RealAdminKillSwitch = components['schemas']['RealAdminKillSwitch'];
export type RealAdminReasonRequest = components['schemas']['RealAdminReasonRequest'];
export type RealAdminDivergence = components['schemas']['RealAdminDivergence'];
export type RealAdminDivergencePage = components['schemas']['RealAdminDivergencePage'];
export type RealAdminCompensationRequest = components['schemas']['RealAdminCompensationRequest'];
export type RealAdminRestore = components['schemas']['RealAdminRestore'];

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
    /** u4-data-requests (P2, US5.9): one page of the requests by due date, optionally of one situation. */
    listDataRequests(status: DataRequestStatus | null, cursor: string | null) {
      return withRenewal(() =>
        client.request<AdminDataRequestPage>(
          `/api/v1/admin/privacy/requests${query({ status, cursor, limit: 50 })}`,
          { authenticated: true },
        ),
      );
    },
    /** u4-data-requests (P2, US5.3): one request with the verification and, for an open Access, the preview. */
    getDataRequest(requestId: string) {
      return withRenewal(() =>
        client.request<AdminDataRequest>(
          `/api/v1/admin/privacy/requests/${encodeURIComponent(requestId)}`,
          { authenticated: true },
        ),
      );
    },
    /** u4-data-requests (P2, US5.6): a request received by the e-mail of the DPO. */
    registerEmailRequest(body: RegisterEmailDataRequest, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<AdminDataRequest>('/api/v1/admin/privacy/requests', {
          method: 'POST',
          body,
          idempotencyKey,
          authenticated: true,
        }),
      );
    },
    /** u4-data-requests (P2, US5.3): a transition; the screen reuses the key on a new attempt of the same action. */
    transitionDataRequest(requestId: string, body: DataRequestTransition, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<AdminDataRequest>(
          `/api/v1/admin/privacy/requests/${encodeURIComponent(requestId)}/transitions`,
          { method: 'POST', body, idempotencyKey, authenticated: true },
        ),
      );
    },
    /** u4-data-requests (P2, US5.6): the verification message of an EMAIL request. */
    startVerification(requestId: string, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<VerificationView>(
          `/api/v1/admin/privacy/requests/${encodeURIComponent(requestId)}/verification`,
          { method: 'POST', idempotencyKey, authenticated: true },
        ),
      );
    },
    /** u4-data-requests (P2, US5.6): the signature received from the person; never stored by the app. */
    confirmVerification(requestId: string, signature: string, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<AdminDataRequest>(
          `/api/v1/admin/privacy/requests/${encodeURIComponent(requestId)}/verification/confirm`,
          { method: 'POST', body: { signature }, idempotencyKey, authenticated: true },
        ),
      );
    },
    /** u5-anonymization (P2, US6.1): anonymizes the account of a DELETION in progress; the key is kept per dialog. */
    anonymizeAccount(requestId: string, confirmation: string, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<AnonymizationResult>(
          `/api/v1/admin/privacy/requests/${encodeURIComponent(requestId)}/anonymization`,
          { method: 'POST', body: { confirmation }, idempotencyKey, authenticated: true },
        ),
      );
    },
    /** U6 (A1, U6-NFR3.6): the people with a bot wallet, 20 per page by cursor. */
    listRealUsers(cursor: string | null) {
      return withRenewal(() =>
        client.request<RealAdminUserPage>(
          `/api/v1/admin/real/users${query({ cursor, limit: 20 })}`,
          {
            authenticated: true,
          },
        ),
      );
    },
    /** U6 (A1): one person, with the main wallet abbreviated and no key. */
    getRealUser(userId: string) {
      return withRenewal(() =>
        client.request<RealAdminUser>(`/api/v1/admin/real/users/${encodeURIComponent(userId)}`, {
          authenticated: true,
        }),
      );
    },
    /** U6 (A1, US7.1): registers a release (24 h of quarantine). */
    releaseRealUser(userId: string, body: RealAdminReleaseRequest, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<RealAdminRelease>(
          `/api/v1/admin/real/users/${encodeURIComponent(userId)}/release`,
          { method: 'POST', body, idempotencyKey, authenticated: true },
        ),
      );
    },
    /** U6 (A1, US7.1): revokes at once; the withdrawal continues. */
    revokeRealUser(userId: string, body: RealAdminReasonRequest, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<RealAdminRevocation>(
          `/api/v1/admin/real/users/${encodeURIComponent(userId)}/release`,
          { method: 'DELETE', body, idempotencyKey, authenticated: true },
        ),
      );
    },
    /** U6 (A1, US7.2): lowers the cap (never raises). */
    lowerRealCap(userId: string, body: RealAdminCapRequest, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<RealAdminCap>(`/api/v1/admin/real/users/${encodeURIComponent(userId)}/cap`, {
          method: 'PUT',
          body,
          idempotencyKey,
          authenticated: true,
        }),
      );
    },
    /** U6 (A1, US7.3): the blocked tokens. */
    listRealTokenBlocks() {
      return withRenewal(() =>
        client.request<RealAdminTokenList>('/api/v1/admin/real/token-blocks', {
          authenticated: true,
        }),
      );
    },
    blockRealToken(body: RealAdminTokenBlockRequest, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<RealAdminBlockedToken>('/api/v1/admin/real/token-blocks', {
          method: 'POST',
          body,
          idempotencyKey,
          authenticated: true,
        }),
      );
    },
    unblockRealToken(mint: string, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<RealAdminUnblock>(
          `/api/v1/admin/real/token-blocks/${encodeURIComponent(mint)}`,
          {
            method: 'DELETE',
            idempotencyKey,
            authenticated: true,
          },
        ),
      );
    },
    /** U6 (A1, US8.1): the kill switch REAL, separate from the one of the PAPER. */
    getRealKillSwitch() {
      return withRenewal(() =>
        client.request<RealAdminKillSwitch>('/api/v1/admin/real/kill-switch', {
          authenticated: true,
        }),
      );
    },
    activateRealKillSwitch(body: RealAdminReasonRequest, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<RealAdminKillSwitch>('/api/v1/admin/real/kill-switch', {
          method: 'PUT',
          body,
          idempotencyKey,
          authenticated: true,
        }),
      );
    },
    deactivateRealKillSwitch(body: RealAdminReasonRequest, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<RealAdminKillSwitch>('/api/v1/admin/real/kill-switch', {
          method: 'DELETE',
          body,
          idempotencyKey,
          authenticated: true,
        }),
      );
    },
    /** U6 (A1, US8.2): the divergences open or compensated, by cursor. */
    listRealDivergences(cursor: string | null) {
      return withRenewal(() =>
        client.request<RealAdminDivergencePage>(
          `/api/v1/admin/real/divergences${query({ cursor, limit: 20 })}`,
          { authenticated: true },
        ),
      );
    },
    compensateRealDivergence(
      divergenceId: string,
      body: RealAdminCompensationRequest,
      idempotencyKey: string,
    ) {
      return withRenewal(() =>
        client.request<RealAdminDivergence>(
          `/api/v1/admin/real/divergences/${encodeURIComponent(divergenceId)}/compensation`,
          { method: 'POST', body, idempotencyKey, authenticated: true },
        ),
      );
    },
    /** U6 (A1): confirms the restore once every wallet came back clean. */
    confirmRealRestore(body: RealAdminReasonRequest, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<RealAdminRestore>('/api/v1/admin/real/restore/confirmation', {
          method: 'POST',
          body,
          idempotencyKey,
          authenticated: true,
        }),
      );
    },
    /** u4-data-requests (P2, BR4.17): the reply of a closed EMAIL request was sent to the contact. */
    markReplySent(requestId: string, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<AdminDataRequest>(
          `/api/v1/admin/privacy/requests/${encodeURIComponent(requestId)}/reply-sent`,
          { method: 'POST', idempotencyKey, authenticated: true },
        ),
      );
    },
  };
}

export type AdminApi = ReturnType<typeof adminApi>;
