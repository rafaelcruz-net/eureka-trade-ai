package com.memetrade.realexecution.api;

import java.util.List;
import java.util.UUID;

/**
 * M6 (contract-summary.md, owner U4; consumers: the cycle of the bots, U5 and U7). Tenant and person always come from
 * the bot row or from the authentication of the caller, never from a request body.
 */
public interface RealExecutionPort {

    /**
     * BR1.2: records the approved REAL decision of a bot cycle as a PENDING {@code RealDecisionRequest}. Requires the
     * transaction of the cycle ({@code MANDATORY}): a cycle rolled back takes the request with it. A second call with
     * the same {@code decisionId} returns the request recorded the first time.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException without an active transaction
     */
    UUID recordDecision(RealDecision decision);

    /** BR2.2: the bot has an order in PROPOSED, QUOTED, SIGNED, SENT or AMBIGUOUS (tenant filtered). */
    boolean hasOrderInProgress(UUID tenantId, UUID botId);

    /** BR8.1: the person has a liquidation in SELLING or WITHDRAWING; every REAL decision of their bots is discarded. */
    boolean liquidationActive(UUID tenantId, UUID userId);

    /**
     * F10: every PROPOSED and QUOTED order of the scope becomes CANCELLED with {@code reason} and its reservation is
     * released, in the transaction of the caller (or a new one). SIGNED orders are cancelled by the recheck before the
     * send (BR3.3). Returns how many orders were cancelled.
     */
    int cancelUnsent(CancelScope scope, RealFailureCode reason);

    /** The newest orders of the person, newest first, at most {@code limit} (1 to 50). */
    List<RealOrderView> recentOrders(UUID tenantId, UUID userId, int limit);

    /**
     * BR6.2: the orders of the person that reached the network (CONFIRMED, or FAILED with a transaction), oldest
     * confirmation first, with the fee and who paid it; the P&L REAL adds the fee paid by the main wallet.
     */
    List<RealOrderView> settledOrders(UUID tenantId, UUID userId);

    /**
     * BR10.1, observability-design.md "Canal ao vivo": the mark of the REAL orders of the person, the newest update in
     * milliseconds, written in the same transaction as each change of state (a change rolled back is never announced).
     */
    long changeMark(UUID tenantId, UUID userId);

    /** The orders of the person changed strictly after {@code since}, oldest change first, at most {@code limit}. */
    List<RealOrderView> changedSince(UUID tenantId, UUID userId, java.time.Instant since, int limit);
}
