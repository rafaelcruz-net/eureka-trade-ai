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
// u3-reacceptance (L1, L2, L3): the current legal documents, the acceptance and the status of the terms.
export type TermsStatus = components['schemas']['TermsStatus'];
// u4-data-requests (P1): the data subject requests of the person.
export type DataRequestView = components['schemas']['DataRequestView'];
export type CreateDataRequest = components['schemas']['CreateDataRequest'];
export type AccessSummary = components['schemas']['AccessSummary'];
export type DataRequestType = CreateDataRequest['type'];
export type DataRequestStatus = DataRequestView['status'];
export type CurrentLegalDocuments = components['schemas']['LegalDocuments'];

/** u3-reacceptance (L3): the code of the 403 of the reacceptance lock. */
export const TERMS_REACCEPTANCE_REQUIRED = 'TERMS_REACCEPTANCE_REQUIRED';

/** u3-reacceptance (L3): a failure that is the 403 of the reacceptance lock. */
export function isTermsReacceptance(failure: unknown): failure is ApiError {
  return (
    failure instanceof ApiError &&
    failure.status === 403 &&
    failure.code === TERMS_REACCEPTANCE_REQUIRED
  );
}

/** Called when a call meets the reacceptance lock; the handler reads the screen of origin itself. */
export type TermsReacceptanceHandler = () => void;
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
 *
 * Single-flight renewal (U9 review R-01): U3's refresh rotates the token and revokes the whole session when an
 * already exchanged refresh token comes back (REUSED). So, per API instance (one per app), at most ONE
 * `POST /auth/refresh` is in flight: every REST call that gets a 401, the live channel of the dashboard and the session
 * check on load all share the same promise through `refreshSession()`. A call whose 401 arrives after another caller
 * already renewed the token (the token in memory changed) just repeats once with the new token, without a new refresh.
 */
export function guestApi(
  client: ApiClient,
  /**
   * 261113-trading-real-bot U6 (logical-components.md "Ajustes menores", BR1.4, BR3.5, U6-NFR4.5): a second instance
   * shares the single-flight renewal of the first one (two parallel refreshes would revoke the session), but has no
   * handler of the reacceptance lock (the ApiProvider never registers one on it).
   */
  shared?: { renewSession: () => Promise<GuestSession> },
) {
  let renewal: Promise<GuestSession> | null = null;
  let termsHandler: TermsReacceptanceHandler | null = null;

  /** u3-reacceptance (BR3.7, NFR2.4): the single handler of the 403 of the lock, registered by the ApiProvider. */
  function notifyTermsReacceptance() {
    termsHandler?.();
  }

  function renewSession(): Promise<GuestSession> {
    if (shared) {
      return shared.renewSession();
    }
    if (renewal === null) {
      renewal = client
        .request<GuestSession>('/api/v1/auth/refresh', { method: 'POST', credentials: true })
        .then((session) => {
          tokenStore.set(session.accessToken);
          return session;
        })
        .finally(() => {
          renewal = null;
        });
    }
    return renewal;
  }

  async function withRenewal<T>(call: () => Promise<T>): Promise<T> {
    try {
      return await renewingOnce(call);
    } catch (failure) {
      // u3-reacceptance (BR3.7): the lock answers before the route, whatever the screen; one handler sends the
      // person to /novo-aceite and the screen still receives the error.
      if (isTermsReacceptance(failure)) {
        notifyTermsReacceptance();
      }
      throw failure;
    }
  }

  async function renewingOnce<T>(call: () => Promise<T>): Promise<T> {
    const sentWith = tokenStore.get();
    try {
      return await call();
    } catch (failure) {
      if (!(failure instanceof ApiError) || failure.status !== 401) {
        throw failure;
      }
      const current = tokenStore.get();
      if (current === null || current === sentWith) {
        try {
          await renewSession();
        } catch {
          throw failure;
        }
      }
      return call();
    }
  }

  return {
    /**
     * u3-reacceptance (NFR2.4): registers THE handler of the 403 TERMS_REACCEPTANCE_REQUIRED (one per app); the
     * returned function unregisters it. The REST calls and the live channel of the dashboard call the same handler.
     */
    setTermsReacceptanceHandler(handler: TermsReacceptanceHandler | null) {
      termsHandler = handler;
      return () => {
        if (termsHandler === handler) {
          termsHandler = null;
        }
      };
    },
    /** u3-reacceptance: for callers outside the REST layer (the live channel) that met the lock. */
    notifyTermsReacceptance() {
      notifyTermsReacceptance();
    },
    /**
     * u3-reacceptance (L1): the current legal documents, public (no token, no cookie); revalidated with the server so a
     * new version reaches the screen right after a 409.
     */
    getCurrentLegalDocuments() {
      return client.request<CurrentLegalDocuments>('/api/v1/legal/current', { revalidate: true });
    },
    /**
     * u3-reacceptance (L2): accepts the current version. The Idempotency-Key is generated ONCE per submission by the
     * screen, so a repeated attempt never records twice.
     */
    acceptTerms(version: string, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<TermsStatus>('/api/v1/terms/acceptances', {
          method: 'POST',
          body: { version },
          idempotencyKey,
          authenticated: true,
        }),
      );
    },
    /** u4-data-requests (P1, US5.2): the requests of the person, newest first. */
    listDataRequests() {
      return withRenewal(() =>
        client.request<DataRequestView[]>('/api/v1/privacy/requests', { authenticated: true }),
      );
    },
    /**
     * u4-data-requests (P1, US5.1): a new request. The Idempotency-Key is generated ONCE per submission by the form
     * and reused on every new attempt, so a repeated click never creates two requests.
     */
    createDataRequest(body: CreateDataRequest, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<DataRequestView>('/api/v1/privacy/requests', {
          method: 'POST',
          body,
          idempotencyKey,
          authenticated: true,
        }),
      );
    },
    /** u4-data-requests (P1, US5.2): one request of the person; another person's is 404. */
    getDataRequest(requestId: string) {
      return withRenewal(() =>
        client.request<DataRequestView>(
          `/api/v1/privacy/requests/${encodeURIComponent(requestId)}`,
          { authenticated: true },
        ),
      );
    },
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
    /** The shared single-flight renewal: concurrent callers get the same answer of ONE refresh request. */
    refreshSession() {
      return renewSession();
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
    /**
     * U5 (BR2.2 to BR2.7): start, pause, resume or stop; the answer is the confirmed state. U9 (BR2.2, NFR4.1): the
     * dashboard passes the Idempotency-Key of ONE click on "Pausar" and reuses it on "Tentar de novo", so a repeated
     * attempt never creates a second transition; without a key every call gets a fresh one, as before.
     */
    runBotCommand(botId: string, command: BotCommand, idempotencyKey?: string) {
      return withRenewal(() =>
        client.request<BotSummary>(
          `/api/v1/bots/${encodeURIComponent(botId)}/commands/${command}`,
          idempotencyKey
            ? { method: 'POST', idempotencyKey, authenticated: true }
            : { method: 'POST', idempotent: true, authenticated: true },
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
    /**
     * U8 (C6, US6.1): a comment with the screen of origin. The Idempotency-Key is the one of the dialog, generated when
     * it opened, so a new attempt after an error never creates a second comment (BR6.4).
     */
    sendFeedback(body: FeedbackRequest, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<FeedbackReceipt>('/api/v1/feedback', {
          method: 'POST',
          body,
          idempotencyKey,
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
    /** 261113-trading-real-bot U1 (R2): the bot wallet of the person; 404 when there is none yet. */
    getBotWallet() {
      return withRenewal(() =>
        client.request<RealBotWallet>('/api/v1/real/bot-wallet', { authenticated: true }),
      );
    },
    /** R2: the consent challenge; the message is built by the server and signed as is by the main wallet. */
    requestRealChallenge(body: RealChallengeRequest) {
      return withRenewal(() =>
        client.request<RealChallenge>('/api/v1/real/consents/challenge', {
          method: 'POST',
          body,
          idempotent: true,
          authenticated: true,
        }),
      );
    },
    /**
     * R2: activation with the signed challenge; the key is created by the signing service, never in the browser. U6
     * (BR6.2): the Idempotency-Key is the one of the exact body (challenge and signature).
     */
    activateBotWallet(body: RealActivationRequest, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<RealBotWallet>('/api/v1/real/bot-wallet', {
          method: 'POST',
          body,
          idempotencyKey,
          authenticated: true,
        }),
      );
    },
    /**
     * R2: a withdrawal, always to the registered main wallet (no destination field). The Idempotency-Key is the one of
     * the form, so a new attempt after an error never asks for a second withdrawal (BR4.8).
     */
    requestRealWithdrawal(body: RealWithdrawalRequest, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<RealWithdrawal>('/api/v1/real/bot-wallet/withdrawals', {
          method: 'POST',
          body,
          idempotencyKey,
          authenticated: true,
        }),
      );
    },
    /** U6 (rules.md "Mudança de contrato" item 1): the release of the person; 403 REAL_DISABLED with the REAL off. */
    getRealRelease() {
      return withRenewal(() =>
        client.request<RealReleaseView>('/api/v1/real/release', { authenticated: true }),
      );
    },
    /** U6 (R6): one withdrawal of the person, for the tracking of its state. */
    getRealWithdrawal(withdrawalId: string) {
      return withRenewal(() =>
        client.request<RealWithdrawal>(
          `/api/v1/real/bot-wallet/withdrawals/${encodeURIComponent(withdrawalId)}`,
          { authenticated: true },
        ),
      );
    },
    /** U6 (R6): the withdrawals of the person, newest first. */
    listRealWithdrawals() {
      return withRenewal(() =>
        client.request<RealWithdrawalPage>('/api/v1/real/bot-wallet/withdrawals', {
          authenticated: true,
        }),
      );
    },
    /**
     * U6 (BR5.2b): the cap (lowering goes straight; raising carries the signed RAISE_CAP challenge). The key is the one
     * of the exact body (BR6.2).
     */
    changeRealPreferences(body: RealPreferencesRequest, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<RealBotWallet>('/api/v1/real/bot-wallet/preferences', {
          method: 'PATCH',
          body,
          idempotencyKey,
          authenticated: true,
        }),
      );
    },
    /** U6 (R3, BR4.2, BR4.3): the mode; raising carries the signed RAISE_MODE challenge. */
    changeRealMode(body: RealModeRequest, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<RealModeView>('/api/v1/real/mode', {
          method: 'PUT',
          body,
          idempotencyKey,
          authenticated: true,
        }),
      );
    },
    /** U6 (R5): the REAL orders of the person, newest first, by cursor. */
    listRealOrders(
      request: { state?: RealOrder['state']; cursor?: string | null; limit?: number } = {},
    ) {
      return withRenewal(() =>
        client.request<RealOrderPage>(
          `/api/v1/real/orders${query({ state: request.state, cursor: request.cursor, limit: request.limit })}`,
          { authenticated: true },
        ),
      );
    },
    /** U6 (R4): the signing session of an order of the SIGNED mode; 404 or 409 when it no longer waits. */
    getRealSigningSession(realOrderId: string) {
      return withRenewal(() =>
        client.request<RealSigningSession>(
          `/api/v1/real/orders/${encodeURIComponent(realOrderId)}/signing-session`,
          { authenticated: true },
        ),
      );
    },
    /** U6 (R4, BR3.1): the transaction signed by the main wallet, unchanged otherwise. */
    submitRealSignedTransaction(
      realOrderId: string,
      body: RealSignedTransactionRequest,
      idempotencyKey: string,
    ) {
      return withRenewal(() =>
        client.request<RealOrder>(
          `/api/v1/real/orders/${encodeURIComponent(realOrderId)}/signing-session`,
          { method: 'POST', body, idempotencyKey, authenticated: true },
        ),
      );
    },
    /** U6 (R4, BR3.4): the person refuses the proposal; nothing is sent. */
    rejectRealSigning(realOrderId: string, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<RealOrder>(`/api/v1/real/orders/${encodeURIComponent(realOrderId)}/reject`, {
          method: 'POST',
          idempotencyKey,
          authenticated: true,
        }),
      );
    },
    /** U6 (R6, US5.2): sell everything and withdraw; the key is the one of the confirmation (BR6.2). */
    startRealLiquidation(idempotencyKey: string) {
      return withRenewal(() =>
        client.request<RealLiquidation>('/api/v1/real/liquidations', {
          method: 'POST',
          idempotencyKey,
          authenticated: true,
        }),
      );
    },
    /** U6 (R2, US4.2): the P&L REAL with fees, never mixed with the PAPER one. */
    getRealPnl() {
      return withRenewal(() =>
        client.request<RealPnl>('/api/v1/real/pnl', { authenticated: true }),
      );
    },
    /** U7 (R5, BR8.2): the position and the current price in USD with its source, for the dialog of the exits. */
    quoteRealConditional(positionMint: string) {
      return withRenewal(() =>
        client.request<RealConditionalQuote>(
          `/api/v1/real/conditional-orders/quote${query({ positionMint })}`,
          { authenticated: true },
        ),
      );
    },
    /** U7 (R5, BR4.4): a stop, a take-profit or an OCO; the key is the one of the confirmation of the dialog. */
    createRealConditional(body: RealConditionalRequest, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<RealConditionalCreated>('/api/v1/real/conditional-orders', {
          method: 'POST',
          body,
          idempotencyKey,
          authenticated: true,
        }),
      );
    },
    /** U7 (R5): the conditional exits of the person, newest first, by cursor. */
    listRealConditionals(request: { cursor?: string | null; limit?: number } = {}) {
      return withRenewal(() =>
        client.request<RealConditionalPage>(
          `/api/v1/real/conditional-orders${query({ cursor: request.cursor, limit: request.limit })}`,
          { authenticated: true },
        ),
      );
    },
    /** U7 (R5, BR1.2): the signature of the main wallet (the consent of the simulated path, or the transaction). */
    signRealConditional(
      conditionalOrderId: string,
      body: RealConditionalSignature,
      idempotencyKey: string,
    ) {
      return withRenewal(() =>
        client.request<RealConditional>(
          `/api/v1/real/conditional-orders/${encodeURIComponent(conditionalOrderId)}/signature`,
          { method: 'POST', body, idempotencyKey, authenticated: true },
        ),
      );
    },
    /** U7 (R5, S48): the cancellation by the person; CANCELLING or CANCELLED. */
    cancelRealConditional(conditionalOrderId: string, idempotencyKey: string) {
      return withRenewal(() =>
        client.request<RealConditional>(
          `/api/v1/real/conditional-orders/${encodeURIComponent(conditionalOrderId)}`,
          { method: 'DELETE', idempotencyKey, authenticated: true },
        ),
      );
    },
    /** U7 (R5, BR7, BR8.4): the fiscal CSV of the period, as a file kept only until the browser saves it. */
    exportRealFiscal(request: { from?: string; to?: string; tz?: string } = {}) {
      return withRenewal(() =>
        client.request<Blob>(
          `/api/v1/real/exports/fiscal${query({ from: request.from, to: request.to, tz: request.tz })}`,
          { authenticated: true, responseType: 'blob' },
        ),
      );
    },
  };
}

// 261113-trading-real-bot U1 (R2): the bot wallet.
export type RealBotWallet = components['schemas']['BotWalletView'];
export type RealChallengeRequest = components['schemas']['RealChallengeRequest'];
export type RealChallenge = components['schemas']['RealChallengeView'];
export type RealActivationRequest = components['schemas']['RealActivationRequest'];
export type RealWithdrawalRequest = components['schemas']['RealWithdrawalRequest'];
export type RealWithdrawal = components['schemas']['RealWithdrawalView'];
// 261113-trading-real-bot U6: the screens of the REAL.
export type RealWithdrawalPage = components['schemas']['RealPage'];
export type RealReleaseView = components['schemas']['RealReleaseView'];
export type RealReleaseStatus = components['schemas']['RealReleaseStatus'];
export type RealPreferencesRequest = components['schemas']['RealPreferencesRequest'];
export type RealModeRequest = components['schemas']['RealModeRequest'];
export type RealModeView = components['schemas']['RealModeView'];
export type RealOrder = components['schemas']['RealOrder'];
export type RealOrderPage = components['schemas']['RealOrderPage'];
export type RealTokenAmount = components['schemas']['RealTokenAmount'];
export type RealSigningSession = components['schemas']['RealSigningSession'];
export type RealSignedTransactionRequest = components['schemas']['RealSignedTransactionRequest'];
export type RealLiquidation = components['schemas']['RealLiquidationView'];
export type RealPnl = components['schemas']['RealPnl'];
export type RealPnlPosition = components['schemas']['RealPnlPosition'];
// 261113-trading-real-bot U7 (R5): the conditional exits and the fiscal export.
export type RealConditional = components['schemas']['ConditionalOrder'];
export type RealConditionalPage = components['schemas']['ConditionalOrderPage'];
export type RealConditionalRequest = components['schemas']['CreateConditionalOrderRequest'];
export type RealConditionalCreated = components['schemas']['CreatedConditionalOrder'];
export type RealConditionalSignature = components['schemas']['ConditionalSignatureRequest'];
export type RealConditionalQuote = components['schemas']['ConditionalQuote'];

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

// U8 (C6): the comment of a guest.
export type FeedbackRequest = components['schemas']['FeedbackRequest'];
export type FeedbackReceipt = components['schemas']['FeedbackReceipt'];
export type FeedbackScreen = FeedbackRequest['screen'];

export type GuestApi = ReturnType<typeof guestApi>;
