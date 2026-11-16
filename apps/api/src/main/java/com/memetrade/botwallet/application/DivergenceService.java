package com.memetrade.botwallet.application;

import com.memetrade.botwallet.api.RealAdminRefused;
import com.memetrade.botwallet.api.RealAdministration.DivergenceView;
import com.memetrade.botwallet.api.RealAdministration.TokenDiffView;
import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealPauses;
import com.memetrade.botwallet.domain.Compensation;
import com.memetrade.botwallet.domain.CompensationRules;
import com.memetrade.botwallet.domain.ReconciliationResult;
import com.memetrade.botwallet.domain.ReconciliationStore;
import com.memetrade.botwallet.domain.ReconciliationStore.DivergenceRow;
import com.memetrade.botwallet.domain.ReconciliationStore.RunRecord;
import com.memetrade.botwallet.domain.ReconciliationStore.WalletTarget;
import com.memetrade.botwallet.domain.TokenDiff;
import com.memetrade.botwallet.infra.RealAudit;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealLedger;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 261113-trading-real-bot U5 (F3, F4, BR2.2, BR2.3, U5-NFR4.6): the divergences of the REAL ledger with the network.
 *
 * <ul>
 *   <li>A DIVERGENT run opens the divergence of the wallet (or measures the open one again, never a second one), pauses
 *       the person DIVERGENCE and is audited; a still divergent run after the compensation puts it back to OPEN with the
 *       new values and the pause stays.
 *   <li>The compensation of the administrator appends one COMPENSATION entry per divergent asset, each EXACTLY the last
 *       measure (never an arbitrary value, never an edit of the ledger); the divergence becomes COMPENSATED and the
 *       next reconciliation (AFTER_COMPENSATION) decides.
 *   <li>A CLEAN run of a COMPENSATED divergence resolves it and resumes the DIVERGENCE pause.
 * </ul>
 */
@Service
public class DivergenceService {

    private static final Logger log = LoggerFactory.getLogger(DivergenceService.class);
    private static final int MAX_REASON = 500;

    private final ReconciliationStore store;
    private final RealLedger ledger;
    private final RealPauses pauses;
    private final RealAudit audit;
    private final Clock clock;

    public DivergenceService(
            ReconciliationStore store, RealLedger ledger, RealPauses pauses, RealAudit audit, Clock clock) {
        this.store = store;
        this.ledger = ledger;
        this.pauses = pauses;
        this.audit = audit;
        this.clock = clock;
    }

    /** BR2.2, BR2.3: what a run changes, in the transaction that records it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onRun(WalletTarget wallet, RunRecord run) {
        ReconciliationResult result = run.outcome().result();
        Optional<DivergenceRow> open = store.openDivergence(wallet.botWalletId());
        if (result == ReconciliationResult.DIVERGENT) {
            divergent(wallet, run, open);
        } else if (result == ReconciliationResult.CLEAN
                && open.isPresent()
                && "COMPENSATED".equals(open.get().state())
                && store.resolve(open.get().divergenceId(), run.runId(), run.finishedAt())) {
            pauses.resumeUser(wallet.tenantId(), wallet.userId(), RealPauseReason.DIVERGENCE);
            audit.system(
                    wallet.tenantId(),
                    "REAL_DIVERGENCE_RESOLVED",
                    "divergence:" + open.get().divergenceId(),
                    Map.of("runId", run.runId().toString()),
                    null);
            log.info("REAL divergence resolved: divergenceId={}", open.get().divergenceId());
        }
    }

    private void divergent(WalletTarget wallet, RunRecord run, Optional<DivergenceRow> open) {
        Map<String, String> details = Map.of(
                "runId", run.runId().toString(),
                "lamportsDiff", Long.toString(run.outcome().lamportsDiff()),
                "tokens", Integer.toString(run.outcome().tokenDiffs().size()));
        if (open.isEmpty()) {
            UUID divergenceId = UUID.randomUUID();
            store.insertDivergence(new DivergenceRow(
                    divergenceId,
                    run.runId(),
                    run.runId(),
                    wallet.tenantId(),
                    wallet.botWalletId(),
                    run.outcome().lamportsDiff(),
                    run.outcome().tokenDiffs(),
                    "OPEN",
                    List.of(),
                    null,
                    run.finishedAt()));
            audit.system(wallet.tenantId(), "REAL_DIVERGENCE_OPENED", "divergence:" + divergenceId, details, null);
            log.info(
                    "REAL divergence opened: divergenceId={}, lamportsDiff={}, tokens={}",
                    divergenceId,
                    run.outcome().lamportsDiff(),
                    run.outcome().tokenDiffs().size());
        } else if (store.remeasure(open.get().divergenceId(), open.get().state(), run)
                && "COMPENSATED".equals(open.get().state())) {
            audit.system(
                    wallet.tenantId(),
                    "REAL_DIVERGENCE_REOPENED",
                    "divergence:" + open.get().divergenceId(),
                    details,
                    null);
            log.info(
                    "REAL divergence reopened after the compensation: divergenceId={}",
                    open.get().divergenceId());
        }
        pauses.pauseUser(wallet.tenantId(), wallet.userId(), RealPauseReason.DIVERGENCE);
    }

    /**
     * BR2.3: the compensation of the administrator, in the transaction of the caller (with the idempotency record).
     *
     * @throws RealAdminRefused {@code NOT_FOUND}, {@code DIVERGENCE_NOT_OPEN}, {@code VALIDATION_FAILED} (reason) or
     *     {@code COMPENSATION_MISMATCH} (an item is not the measure)
     */
    @Transactional
    public DivergenceView compensate(UUID adminId, UUID divergenceId, List<Compensation> items, String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_REASON) {
            throw RealAdminRefused.validation();
        }
        DivergenceRow divergence = store.lockDivergence(divergenceId).orElseThrow(RealAdminRefused::notFound);
        if (!"OPEN".equals(divergence.state())) {
            throw new RealAdminRefused("DIVERGENCE_NOT_OPEN", 409);
        }
        if (CompensationRules.check(items, divergence.lamportsDiff(), divergence.tokenDiffs())
                .isPresent()) {
            throw new RealAdminRefused(CompensationRules.MISMATCH, 422);
        }
        List<String> references = new ArrayList<>();
        for (Compensation item : items) {
            String key = CompensationRules.idempotencyKey(divergenceId, divergence.lastRunId(), item.asset());
            ledger.record(RealEntry.compensation(
                    divergence.tenantId(),
                    divergence.botWalletId(),
                    item.isSol() ? item.amount() : 0L,
                    item.isSol() ? null : item.asset(),
                    item.isSol() ? null : item.amount(),
                    "div:" + divergenceId,
                    key));
            references.add(key);
        }
        Instant now = clock.instant();
        if (!store.compensate(divergenceId, references, adminId, trimmed, now)) {
            throw new RealAdminRefused("DIVERGENCE_NOT_OPEN", 409);
        }
        audit.admin(
                adminId,
                Optional.of(divergence.tenantId()),
                "REAL_DIVERGENCE_COMPENSATED",
                "divergence:" + divergenceId,
                Map.of("items", Integer.toString(items.size()), "reason", trimmed));
        log.info("REAL divergence compensated: divergenceId={}, items={}", divergenceId, items.size());
        return view(new DivergenceRow(
                divergence.divergenceId(),
                divergence.runId(),
                divergence.lastRunId(),
                divergence.tenantId(),
                divergence.botWalletId(),
                divergence.lamportsDiff(),
                divergence.tokenDiffs(),
                "COMPENSATED",
                references,
                now,
                divergence.openedAt()));
    }

    /** R4: the divergences OPEN or COMPENSATED, newest first, by cursor (opened at, id). */
    public List<DivergenceView> open(Optional<Instant> beforeOpenedAt, Optional<UUID> beforeId, int limit) {
        return store.openDivergences(beforeOpenedAt, beforeId, limit).stream()
                .map(DivergenceService::view)
                .toList();
    }

    static DivergenceView view(DivergenceRow row) {
        return new DivergenceView(
                row.divergenceId(),
                row.botWalletId(),
                row.state(),
                row.lamportsDiff(),
                row.tokenDiffs().stream().map(DivergenceService::view).toList(),
                row.lastRunId(),
                row.openedAt());
    }

    private static TokenDiffView view(TokenDiff diff) {
        return new TokenDiffView(diff.mint(), diff.chain(), diff.ledger(), diff.diff());
    }
}
