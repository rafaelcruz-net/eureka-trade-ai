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

    /**
     * 261113-trading-real-bot U5 (BR2.1): the orders of the person SENT or AMBIGUOUS, whose effect on the network may not
     * be in the ledger yet; the reconciliation of the wallet waits for them (DEFERRED).
     */
    int ordersInTransit(UUID tenantId, UUID userId);

    /**
     * 261113-trading-real-bot U5 (U5-NFR5.4): the REAL orders of everybody not sent yet (PROPOSED, QUOTED or SIGNED),
     * for the gauge {@code real_kill_switch_unsent_orders}.
     */
    int unsentOrders();

    /**
     * 261113-trading-real-bot U5 (BR2.5): which of these signatures are transactions of REAL orders recorded by the U4
     * (the restore check never looks those up on the network).
     */
    java.util.Set<String> knownTransactions(java.util.Collection<String> signatures);

    /** The newest orders of the person, newest first, at most {@code limit} (1 to 50). */
    List<RealOrderView> recentOrders(UUID tenantId, UUID userId, int limit);

    /**
     * BR6.2: the orders of the person that reached the network (CONFIRMED, or FAILED with a transaction), oldest
     * confirmation first, with the fee and who paid it; the P&L REAL adds the fee paid by the main wallet.
     */
    List<RealOrderView> settledOrders(UUID tenantId, UUID userId);

    /**
     * 261121-paper-real-unico U1 (performance-design.md "Limite e gatilho da leitura REAL"): the same orders, at most
     * {@code max} of them (the oldest first), so a reader can tell "more than it may read" without loading them all.
     * The default reads everything and cuts; the store answers with a {@code limit} in the query.
     */
    default List<RealOrderView> settledOrders(UUID tenantId, UUID userId, int max) {
        List<RealOrderView> all = settledOrders(tenantId, userId);
        return all.size() <= max ? all : all.subList(0, max);
    }

    /**
     * BR10.1, observability-design.md "Canal ao vivo": the mark of the REAL orders of the person, the newest update in
     * milliseconds, written in the same transaction as each change of state (a change rolled back is never announced).
     */
    long changeMark(UUID tenantId, UUID userId);

    /** The orders of the person changed strictly after {@code since}, oldest change first, at most {@code limit}. */
    List<RealOrderView> changedSince(UUID tenantId, UUID userId, java.time.Instant since, int limit);

    /**
     * 261121-paper-real-unico U2 (C13, BR10.1): the orders of the person that reached the network (CONFIRMED, or FAILED
     * with a transaction), handed one by one to {@code consumer}, never as a list, in no particular order (the order of
     * the P&L comes from the ledger, C12). Filtered by tenant and person; fetched 500 at a time in the read transaction
     * of the caller, or in a read-only one of its own; never in the transaction of the cycle that writes.
     */
    void streamSettledOrders(UUID tenantId, UUID userId, java.util.function.Consumer<RealOrderView> consumer);

    /** The same read under a hard deadline (NFR1.12), applied at every row and before every block of 500 rows. */
    void streamSettledOrders(
            UUID tenantId,
            UUID userId,
            com.memetrade.shared.jdbc.ReadDeadline deadline,
            java.util.function.Consumer<RealOrderView> consumer);

    /**
     * 261121-paper-real-unico U2 (performance-design.md "Consistência com o view"): one settled order of THIS person by
     * its id, read on the spot when a ledger row names an order the stream did not bring (it was confirmed meanwhile);
     * empty when it is not the person's, not settled, or gone (purged).
     */
    java.util.Optional<RealOrderView> settledOrder(UUID tenantId, UUID userId, UUID realOrderId);
}
