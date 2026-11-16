package com.memetrade.botwallet.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 261113-trading-real-bot U5: persistence port of the reconciliations and the divergences (BR2.1 to BR2.3). A run is
 * insert-only; a divergence moves by compare-and-set on its state (U5-NFR4.1). Every row carries the tenant of its
 * wallet.
 */
public interface ReconciliationStore {

    /** The bot wallet to reconcile, ACTIVE or not, with its tenant, its person, its address and its deposit cursor. */
    Optional<WalletTarget> target(UUID botWalletId);

    /** BR2.1: withdrawals of the wallet SENT or AMBIGUOUS (money that may have left without its ledger entry yet). */
    int withdrawalsInTransit(UUID botWalletId);

    void insertRun(RunRecord run);

    /** The divergence OPEN or COMPENSATED of the wallet (at most one). */
    Optional<DivergenceRow> openDivergence(UUID botWalletId);

    /** The divergence locked FOR UPDATE until the end of the transaction. */
    Optional<DivergenceRow> lockDivergence(UUID divergenceId);

    void insertDivergence(DivergenceRow divergence);

    /** A new measure of the divergence: OPEN or COMPENSATED back to OPEN with the values of {@code run}. */
    boolean remeasure(UUID divergenceId, String expectedState, RunRecord run);

    /** BR2.3: OPEN to COMPENSATED with the references of the entries, the administrator and the reason. */
    boolean compensate(UUID divergenceId, List<String> entryReferences, UUID adminId, String reason, Instant at);

    /** BR2.3: COMPENSATED to RESOLVED after a clean run. */
    boolean resolve(UUID divergenceId, UUID runId, Instant at);

    /** BR2.4: the SOL measured on the network by the last run of the wallet that compared (CLEAN or DIVERGENT). */
    Optional<Long> lastChainLamports(UUID botWalletId);

    /** The ACTIVE wallets with what decides whether a reconciliation is due. */
    List<Candidate> candidates();

    /** The divergences OPEN or COMPENSATED, newest first, after the cursor (opened at, id), at most {@code limit}. */
    List<DivergenceRow> openDivergences(Optional<Instant> beforeOpenedAt, Optional<UUID> beforeId, int limit);

    /** The bot wallet of a reconciliation. */
    record WalletTarget(UUID tenantId, UUID userId, UUID botWalletId, String address, String depositCursor) {}

    /** One reconciliation as recorded (entities.md ReconciliationRun). */
    record RunRecord(
            UUID runId,
            UUID tenantId,
            UUID botWalletId,
            ReconciliationTrigger trigger,
            ReconciliationOutcome outcome,
            Instant startedAt,
            Instant finishedAt) {}

    /** One divergence (entities.md Divergence). */
    record DivergenceRow(
            UUID divergenceId,
            UUID runId,
            UUID lastRunId,
            UUID tenantId,
            UUID botWalletId,
            long lamportsDiff,
            List<TokenDiff> tokenDiffs,
            String state,
            List<String> compensationReferences,
            Instant compensatedAt,
            Instant openedAt) {

        public DivergenceRow {
            tokenDiffs = List.copyOf(tokenDiffs);
            compensationReferences = List.copyOf(compensationReferences);
        }
    }

    /**
     * An ACTIVE wallet and the instants that make its reconciliation due: its last run, its last AFTER_RESTORE run, the
     * compensation of its divergence and the last mark of the U1 (a confirmed withdrawal, an unknown deposit).
     */
    record Candidate(
            UUID tenantId,
            UUID userId,
            UUID botWalletId,
            Instant lastRun,
            Instant lastRestoreRun,
            Instant compensatedAt,
            Instant lastMark) {}
}
