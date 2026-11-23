package com.memetrade.pnl.real;

import com.memetrade.pnl.api.PnlWindow;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 261121-paper-real-unico U2 (ENT-213, FR5.3, BR8.1 to BR8.5, BR3.4): the reconciliation of the lines (the bots plus
 * "Sem bot") with the mode computed from the ledger, for one person and one period. MATCH only when every check is an
 * exact equality in lamports and base units. The failed checks are in alphabetical order and the differences ordered by
 * key, so the fingerprint of the audit event is stable (security-design.md).
 *
 * @param unrealizedDiffLamports lines minus ledger, present when both sides are known
 * @param diffs the difference of each check that failed with a number (lines minus ledger), by name
 */
public record ReconciliationResult(
        PnlWindow window,
        Status status,
        long realizedDiffLamports,
        long feesDiffLamports,
        Optional<Long> unrealizedDiffLamports,
        List<Check> failedChecks,
        Map<String, Long> diffs) {

    public enum Status {
        MATCH,
        MISMATCH
    }

    /** The checks of FR5.3 (the label {@code check} of {@code pnl_real_reconciliation_mismatches_total}). */
    public enum Check {
        REALIZED,
        FEES,
        UNREALIZED,
        COST_CONSERVATION,
        QUANTITY_CONSERVATION,
        EVENT_SUMS,
        UNOWNED_WITHOUT_REASON,
        UNKNOWN_BOT
    }

    public ReconciliationResult {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(unrealizedDiffLamports, "unrealizedDiffLamports");
        failedChecks = failedChecks.stream()
                .sorted(Comparator.comparing(Check::name))
                .distinct()
                .toList();
        diffs = Collections.unmodifiableSortedMap(new TreeMap<>(diffs));
        if ((status == Status.MATCH) != failedChecks.isEmpty()) {
            throw new IllegalArgumentException("MATCH exactly when no check failed");
        }
    }
}
