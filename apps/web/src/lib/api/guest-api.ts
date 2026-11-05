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
    /** U6 (C21): the refused orders of a bot; 404 means no rejection of this person (the screen says so). */
    listBotRejections(botId: string, request: { cursor?: string | null; limit?: number } = {}) {
      return withRenewal(() =>
        client.request<RejectionPage>(
          `/api/v1/bots/${encodeURIComponent(botId)}/rejections${query({
            cursor: request.cursor,
            limit: request.limit,
          })}`,
          { authenticated: true },
        ),
      );
    },
    /** U5 (C4): the two template strategies with their parameters. */
    listStrategyTemplates() {
      return withRenewal(() =>
        client.request<StrategyTemplate[]>('/api/v1/strategy-templates', { authenticated: true }),
      );
    },
    /** U5 (C4): the bots of the person, optionally of one state, with the active counter. */
    listBots(request: { state?: BotState; cursor?: string | null; limit?: number } = {}) {
      return withRenewal(() =>
        client.request<BotPage>(
          `/api/v1/bots${query({ state: request.state, cursor: request.cursor, limit: request.limit })}`,
          { authenticated: true },
        ),
      );
    },
    /** U5 (C4): one bot with its draft and limits. */
    getBot(botId: string) {
      return withRenewal(() =>
        client.request<BotDetail>(`/api/v1/bots/${encodeURIComponent(botId)}`, {
          authenticated: true,
        }),
      );
    },
    /** U5 (BR1.1): a draft from a template. */
    createBot(body: CreateBotRequest) {
      return withRenewal(() =>
        client.request<BotSummary>('/api/v1/bots', {
          method: 'POST',
          body,
          idempotent: true,
          authenticated: true,
        }),
      );
    },
    /** U5 (AC4.1.3): one accepted step of the wizard. */
    saveBotDraft(botId: string, body: DraftStepRequest) {
      return withRenewal(() =>
        client.request<BotSummary>(`/api/v1/bots/${encodeURIComponent(botId)}/draft`, {
          method: 'PUT',
          body,
          idempotent: true,
          authenticated: true,
        }),
      );
    },
    /** U5 (BR2.2 to BR2.7): start, pause, resume or stop; the answer is the confirmed state. */
    runBotCommand(botId: string, command: BotCommand) {
      return withRenewal(() =>
        client.request<BotSummary>(
          `/api/v1/bots/${encodeURIComponent(botId)}/commands/${command}`,
          { method: 'POST', idempotent: true, authenticated: true },
        ),
      );
    },
    /** U5 (BR5.1): a new version of the limits. */
    changeBotLimits(botId: string, body: BotLimits) {
      return withRenewal(() =>
        client.request<BotSummary>(`/api/v1/bots/${encodeURIComponent(botId)}/limits`, {
          method: 'POST',
          body,
          idempotent: true,
          authenticated: true,
        }),
      );
    },
    /** U5 (C4, US4.5): the explained decisions of a bot, newest first. */
    listBotDecisions(botId: string, request: { cursor?: string | null; limit?: number } = {}) {
      return withRenewal(() =>
        client.request<DecisionPage>(
          `/api/v1/bots/${encodeURIComponent(botId)}/decisions${query({
            cursor: request.cursor,
            limit: request.limit,
          })}`,
          { authenticated: true },
        ),
      );
    },
  };
}

// U5 (C4): bots, wizard, commands and decisions; U6 (C21): refused orders.
export type RejectionPage = components['schemas']['RejectionPage'];
export type OrderRejection = components['schemas']['OrderRejection'];
export type StrategyTemplate = components['schemas']['StrategyTemplate'];
export type StrategyParameter = components['schemas']['StrategyParameter'];
export type BotSummary = components['schemas']['BotSummary'];
export type BotDetail = components['schemas']['BotDetail'];
export type BotPage = components['schemas']['BotPage'];
export type BotLimits = components['schemas']['BotLimits'];
export type BotTokenSelection = components['schemas']['TokenSelection'];
export type CreateBotRequest = components['schemas']['CreateBotRequest'];
export type DraftStepRequest = components['schemas']['DraftStepRequest'];
export type Decision = components['schemas']['Decision'];
export type DecisionPage = components['schemas']['DecisionPage'];
export type BotState = BotSummary['state'];
export type PauseReason = BotSummary['pauseReasons'][number];
export type BotCommand = 'start' | 'pause' | 'resume' | 'stop';

export type GuestApi = ReturnType<typeof guestApi>;
