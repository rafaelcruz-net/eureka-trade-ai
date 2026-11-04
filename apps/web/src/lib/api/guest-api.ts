import { ApiError, type ApiClient } from '@/lib/api/client';
import type { components } from '@/lib/api/schema';
import { tokenStore } from '@/lib/auth/token-store';

export type ChallengeResponse = components['schemas']['ChallengeResponse'];
export type VerifyRequest = components['schemas']['VerifyRequest'];
export type GuestSession = components['schemas']['GuestSession'];
export type ScannerPage = components['schemas']['ScannerPage'];
export type ScannerRow = components['schemas']['ScannerRow'];
export type MarketValue = components['schemas']['MarketValue'];
export type KnownValue = components['schemas']['KnownValue'];
export type UnknownValue = components['schemas']['UnknownValue'];
export type Divergence = components['schemas']['Divergence'];
export type SourceStatus = components['schemas']['SourceStatus'];
export type RiskSummary = components['schemas']['RiskSummary'];
export type Assessment = components['schemas']['Assessment'];
export type AssessmentFactor = components['schemas']['AssessmentFactor'];
export type InviteLookup = components['schemas']['InviteLookup'];
export type Account = components['schemas']['Account'];
export type AcceptedTerms = components['schemas']['AcceptedTerms'];
// U7 (C5): PAPER ledger, P&L and reconciliation.
export type PnlReport = components['schemas']['PnlReport'];
export type PnlAmount = components['schemas']['PnlAmount'];
export type PnlTokenLine = components['schemas']['PnlTokenLine'];
export type PaperTrade = components['schemas']['PaperTrade'];
export type PaperTradePage = components['schemas']['PaperTradePage'];
export type LedgerEntry = components['schemas']['LedgerEntry'];
export type LedgerEntryPage = components['schemas']['LedgerEntryPage'];
export type DayUsage = components['schemas']['DayUsage'];
export type ReconciliationResult = components['schemas']['ReconciliationResult'];
export type ReconciliationDivergence = components['schemas']['ReconciliationDivergence'];

/** Query of the P&L: UTC instants (the screen converts the person's dates) and an optional bot. */
export interface PnlRequest {
  from: string;
  to: string;
  botId?: string;
}

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

export type SortField = 'AGE' | 'LIQUIDITY' | 'VOLUME_24H' | 'HOLDERS' | 'RISK_SCORE';
export type SortOrder = 'ASC' | 'DESC';

/** Filters of C2 as text, exactly as they travel in the query string (decimals never become JS numbers). */
export interface ScannerFilters {
  maxAgeMinutes?: string;
  minLiquidity?: string;
  minVolume24h?: string;
  minHolders?: string;
  maxRiskScore?: string;
  sourceId?: string;
}

export interface ListRequest {
  cursor: string | null;
  limit: number;
  sort?: SortField;
  order?: SortOrder;
  filters?: ScannerFilters;
}

/** Query string of the list: only the parameters that have a value. */
export function listQuery(request: ListRequest): string {
  const query = new URLSearchParams({ limit: String(request.limit) });
  if (request.cursor) {
    query.set('cursor', request.cursor);
  }
  if (request.sort) {
    query.set('sort', request.sort);
  }
  if (request.order) {
    query.set('order', request.order);
  }
  for (const [name, value] of Object.entries(request.filters ?? {})) {
    if (value !== undefined && value !== '') {
      query.set(name, value);
    }
  }
  return query.toString();
}

/**
 * Typed calls of contracts C1, C2 and C3 (types generated from the API contract, never written by hand). An
 * authenticated call that answers 401 tries ONE renewal through the HttpOnly cookie and, when it works, repeats the
 * call once without the person noticing (U3 F6 step 1); when the renewal fails too, the original 401 reaches the
 * screen, which sends the person to the entry with the screen of origin.
 */
export function guestApi(client: ApiClient) {
  async function withRenewal<T>(call: () => Promise<T>): Promise<T> {
    try {
      return await call();
    } catch (failure) {
      if (!(failure instanceof ApiError) || failure.status !== 401) {
        throw failure;
      }
      let renewed: GuestSession;
      try {
        renewed = await client.request<GuestSession>('/api/v1/auth/refresh', {
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
    listTokens(request: ListRequest) {
      return withRenewal(() =>
        client.request<ScannerPage>(`/api/v1/scanner/tokens?${listQuery(request)}`, {
          authenticated: true,
        }),
      );
    },
    getAssessment(chain: string, mint: string) {
      return withRenewal(() =>
        client.request<Assessment>(
          `/api/v1/tokens/${encodeURIComponent(chain)}/${encodeURIComponent(mint)}/assessment`,
          { authenticated: true },
        ),
      );
    },
    /** U3 (C3): no session, no cookie; the code goes only in the path and is never logged by the app. */
    lookupInvite(code: string) {
      return client.request<InviteLookup>(`/api/v1/invites/${encodeURIComponent(code)}`);
    },
    /** U3 (C3): the caller's own account; person and tenant come from the token. */
    getAccount() {
      return withRenewal(() => client.request<Account>('/api/v1/account', { authenticated: true }));
    },
    /** U7 (C5): the P&L of a period [from, to) in UTC. */
    getPnl(request: PnlRequest) {
      return withRenewal(() =>
        client.request<PnlReport>(
          `/api/v1/pnl${query({ from: request.from, to: request.to, botId: request.botId })}`,
          { authenticated: true },
        ),
      );
    },
    /** U7 (C5): the entries of the person, newest first, by cursor. */
    listLedger(request: { botId?: string; cursor?: string | null; limit?: number } = {}) {
      return withRenewal(() =>
        client.request<LedgerEntryPage>(
          `/api/v1/ledger${query({ botId: request.botId, cursor: request.cursor, limit: request.limit })}`,
          { authenticated: true },
        ),
      );
    },
    /** U7 (C5): the orders of one bot, each once, with every hypothesis of its fill. */
    listBotTrades(botId: string, request: { cursor?: string | null; limit?: number } = {}) {
      return withRenewal(() =>
        client.request<PaperTradePage>(
          `/api/v1/bots/${encodeURIComponent(botId)}/trades${query({
            cursor: request.cursor,
            limit: request.limit,
          })}`,
          { authenticated: true },
        ),
      );
    },
    /** U7 (C5): exposure and loss of the UTC day; the limits come from U5. */
    getDayUsage(botId: string) {
      return withRenewal(() =>
        client.request<DayUsage>(`/api/v1/bots/${encodeURIComponent(botId)}/day-usage`, {
          authenticated: true,
        }),
      );
    },
    /** U7 (C5): the latest reconciliation of the person. */
    getReconciliation(botId?: string) {
      return withRenewal(() =>
        client.request<ReconciliationResult>(`/api/v1/reconciliation${query({ botId })}`, {
          authenticated: true,
        }),
      );
    },
    /** U7 (BR8.3): "verificar agora", with a fresh Idempotency-Key. */
    runReconciliation() {
      return withRenewal(() =>
        client.request<ReconciliationResult>('/api/v1/reconciliation/run', {
          method: 'POST',
          idempotent: true,
          authenticated: true,
        }),
      );
    },
  };
}

export type GuestApi = ReturnType<typeof guestApi>;
