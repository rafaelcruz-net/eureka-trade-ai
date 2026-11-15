package com.memetrade.riskcontrol.api;

import java.util.UUID;

/**
 * 261113-trading-real-bot U3, contract M5: the REAL risk barrier and the block list of tokens. Owner U3; consumers U4
 * (evaluate, handOver) and U5 (blockToken, unblockToken, only from the {@code administration} module).
 *
 * <p>{@code evaluate} and {@code handOver} require the transaction of the caller ({@code MANDATORY}): the evaluation,
 * its audit event, the reservation and the DAILY_LOSS pause are written in it, and disappear with it when the caller
 * rolls back (BR7.1, U3-NFR4.6). The block list opens its own transaction.
 */
public interface RealRiskGate {

    /**
     * Evaluates a REAL order (F1 buy, F2 sell). A BUY runs under the lock of the wallet of the person (at most 1 s of
     * wait). A repeated evaluation of the same {@code decisionId} returns the decision recorded the first time, approved
     * or rejected, without counting it again (BR6.1, U3-NFR4.2).
     *
     * <p><b>Precondition (BR6.3, review R-04):</b> the CALLER has already checked that {@code botId} belongs to
     * {@code tenantId} and {@code userId}. The barrier cannot see the bots (it reads only M1, M2, M4 and the indicator):
     * it filters every read by tenant and person, so the wallet of another tenant is never read ({@code NO_BOT_WALLET}),
     * but a {@code botId} of another tenant passed with the right tenant and person would only be used as the key of the
     * count of BR4.2 and of the records. The U4 reads the bot with its tenant before calling.
     *
     * @throws RealGateUnavailable when a dependency failed or the lock was not obtained; never approves then. After a
     *     SQL failure ({@link RealGateUnavailable#transactionUsable()} false) the transaction of the caller is aborted
     *     and marked rollback-only: the caller must roll back. After a lock timeout it is still usable.
     * @throws org.springframework.transaction.IllegalTransactionStateException without an active transaction
     */
    GateDecision evaluate(OrderIntent intent);

    /**
     * BR6.2: the U4 takes over the reservation of an approved buy, in the transaction where it records the
     * {@code RESERVE} of the ledger. Takes the same wallet lock inside. An OPEN reservation past its expiry becomes
     * EXPIRED here and answers {@link HandOverResult#RESERVATION_EXPIRED}.
     *
     * @param tenantId the tenant of the bot (every read filters by it)
     */
    HandOverResult handOver(UUID tenantId, UUID approvalId);

    /**
     * 261113-trading-real-bot U4 (Q7 of the NFR Design, BR2.1, BR8.1): runs {@code action} under the SAME lock of the
     * person that {@link #evaluate} and {@link #handOver} take, in the transaction of the caller ({@code MANDATORY}). The
     * consumer of the decision queue and the creation of a liquidation use it, so the two never run together for one
     * person (S30). The lock itself stays hidden in the U3; it lasts until the transaction of the caller ends.
     *
     * @throws RealGateUnavailable ({@code lock}) after 1 s without the lock; the transaction is still usable and nothing
     *     ran
     */
    <T> T withPersonLock(UUID tenantId, UUID userId, java.util.function.Supplier<T> action);

    /**
     * BR2.3: blocks a token for every REAL bot; repeating the block of the same token changes nothing and returns the
     * current row. Audited ({@code REAL_TOKEN_BLOCKED}) only when it changed something.
     *
     * @throws com.memetrade.shared.error.ApiException {@code VALIDATION_FAILED} for an empty reason or one longer than
     *     500 characters
     */
    BlockedToken blockToken(String chain, String mint, String reason, UUID adminId);

    /** BR2.3: removes a token from the block list; audited ({@code REAL_TOKEN_UNBLOCKED}) only when it was blocked. */
    UnblockResult unblockToken(String chain, String mint, UUID adminId);
}
