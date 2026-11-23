package com.memetrade.ledger.infra;

import com.memetrade.ledger.api.SlotWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 261121-paper-real-unico U2 (NFR2.12, NFR2.13, observability-design.md): the metrics of the slot of the REAL ledger,
 * every series registered with 0 when the process starts, so the first increment shows in {@code increase()}:
 *
 * <ul>
 *   <li>{@code ledger.real.slot_conflicts{writer}} (exported {@code ledger_real_slot_conflicts_total}): +1 per slot
 *       refused, counted only after the transaction of the writer commits;
 *   <li>{@code ledger.real.slot_missing} (exported {@code ledger_real_slot_missing_total}): +1 per row delivered
 *       SLOT_MISSING by the read in network order.
 * </ul>
 *
 * No label carries a tenant, a wallet, a signature or a mint.
 */
@Component
public class LedgerRealSlotMetrics {

    private final Map<SlotWriter, Counter> conflicts = new EnumMap<>(SlotWriter.class);
    private final Counter missing;

    public LedgerRealSlotMetrics(MeterRegistry registry) {
        for (SlotWriter writer : SlotWriter.values()) {
            conflicts.put(
                    writer,
                    Counter.builder("ledger.real.slot_conflicts")
                            .description("REAL slots refused because the signature already had another slot")
                            .tag("writer", writer.name())
                            .register(registry));
        }
        this.missing = Counter.builder("ledger.real.slot_missing")
                .description("REAL ledger rows read in network order without a recorded slot")
                .register(registry);
        this.epochFallbacks = Counter.builder("ledger.real.epoch.fallbacks")
                .description("REAL views read from the whole history because a mark of zero disagreed with the fills")
                .tag("reason", EPOCH_COUNT_MISMATCH)
                .register(registry);
    }

    /** 261121-paper-real-unico U2a, Etapa 14b: the reason of a fall back of the view to the whole history. */
    public static final String EPOCH_COUNT_MISMATCH = "COUNT_MISMATCH";

    private final Counter epochFallbacks;

    /**
     * Etapa 14b ({@code ledger.real.epoch.fallbacks{reason}}, exported {@code ledger_real_epoch_fallbacks_total}): one
     * view read from the whole history because the count of the fills of a mint disagreed with its latest mark (a fill
     * written without keeping the marks, or a late confirmation not marked yet). Counted at once: it is a read.
     */
    public void epochFallback() {
        epochFallbacks.increment();
    }

    /** One refused slot of {@code writer}; counted after the commit of the current transaction, or now without one. */
    public void conflict(SlotWriter writer) {
        Counter counter = conflicts.get(writer);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    counter.increment();
                }
            });
        } else {
            counter.increment();
        }
    }

    /** {@code rows} rows delivered SLOT_MISSING by one read. */
    public void slotMissing(long rows) {
        if (rows < 0) {
            throw new IllegalArgumentException("a count of rows is never negative");
        }
        if (rows > 0) {
            missing.increment(rows);
        }
    }
}
