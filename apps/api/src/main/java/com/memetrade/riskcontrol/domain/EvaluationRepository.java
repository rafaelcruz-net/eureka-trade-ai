package com.memetrade.riskcontrol.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Port of the {@code order_evaluation} table (implemented by the infrastructure). Every method runs in the caller's
 * transaction and filters by the tenant and the PAPER mode; no method changes or deletes a row.
 */
public interface EvaluationRepository {

    /**
     * BR2.8: inserts the evaluation unless one already exists for (tenant, PAPER, bot, decision). A conflict does not
     * abort the transaction (it waits for a concurrent uncommitted insert of the same decision).
     *
     * @return true when this call inserted the row, false when another evaluation of the decision exists
     */
    boolean insertIfAbsent(EvaluationRecord evaluation);

    /**
     * Inserts many new evaluations in one batch, in the caller's transaction (the preparation volume of the load
     * scenario {@code u6}; the same rows the gate writes). A duplicated decision fails the batch.
     */
    void insertAll(java.util.List<EvaluationRecord> evaluations);

    /** The evaluation of the decision in the tenant, if any (the winner after a conflict). */
    Optional<EvaluationRecord> find(UUID tenantId, UUID botId, UUID decisionId);

    /** BR4.1: whether the person has at least one evaluation of the bot (PAPER, own tenant). */
    boolean existsForPerson(UUID tenantId, UUID ownerUserId, UUID botId);
}
