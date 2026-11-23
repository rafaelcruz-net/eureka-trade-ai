package com.memetrade.pnl.application;

import com.memetrade.pnl.api.RealPnlUnavailable;
import com.memetrade.pnl.modeview.AfterBlockRelease;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 261121-paper-real-unico U2 (BR8.7, F4; review R-01, decision of the founder of 2026-11-23): the answer of a REAL
 * reconciliation that did not match, {@link RealPnlUnavailable} with {@code RECONCILIATION_MISMATCH} (the REAL block
 * UNAVAILABLE, no partial number), carrying the audit of the mismatch that must run only after the read transaction
 * ended. {@link RealBotPnl} runs it at once when it owned its transaction; inside the transaction of a block, the pool of
 * the blocks runs it after the transaction ({@link AfterBlockRelease}). The audit runs at most once.
 */
public class ReconciliationMismatch extends RealPnlUnavailable implements AfterBlockRelease {

    private static final long serialVersionUID = 1L;

    private final transient Supplier<String> audit;
    private final AtomicBoolean done = new AtomicBoolean();

    ReconciliationMismatch(Supplier<String> audit) {
        super(RealPnlUnavailable.RECONCILIATION_MISMATCH);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /**
     * Writes the audit event now (bounded by {@code write-timeout}) and gives its outcome: {@code true},
     * {@code deduplicated}, {@code failed}, or {@code already} when it already ran. A failure is counted and logged by
     * the gate; it never changes the answer.
     */
    public String audit() {
        if (!done.compareAndSet(false, true)) {
            return "already";
        }
        return audit.get();
    }

    @Override
    public void afterTransaction() {
        audit();
    }
}
