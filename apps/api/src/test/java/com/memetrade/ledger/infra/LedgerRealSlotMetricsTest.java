package com.memetrade.ledger.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.api.SlotWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 261121-paper-real-unico U2, Etapa 4 (written before the code; NFR2.12, NFR2.13, observability-design.md): the three
 * series of {@code ledger.real.slot_conflicts} and the series of {@code ledger.real.slot_missing} exist with 0 when the
 * process starts; a conflict counts only after the transaction of its writer commits (a confirmation rolled back counts
 * nothing); the rows SLOT_MISSING delivered by a read are added as they are.
 */
class LedgerRealSlotMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LedgerRealSlotMetrics metrics = new LedgerRealSlotMetrics(registry);

    @AfterEach
    void clearSynchronizations() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void theThreeWritersAndTheMissingSeriesStartAtZero() {
        for (SlotWriter writer : SlotWriter.values()) {
            assertThat(conflicts(writer)).as(writer.name()).isZero();
        }
        assertThat(SlotWriter.values()).hasSize(3);
        assertThat(registry.get("ledger.real.slot_missing").counter().count()).isZero();
    }

    @Test
    void aConflictOfTheOrderConfirmationCountsOnlyAfterTheCommit() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.conflict(SlotWriter.ORDER_CONFIRMATION);
        assertThat(conflicts(SlotWriter.ORDER_CONFIRMATION)).isZero();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        assertThat(conflicts(SlotWriter.ORDER_CONFIRMATION)).isEqualTo(1.0);
        assertThat(conflicts(SlotWriter.CONDITIONAL_FILL)).isZero();
        assertThat(conflicts(SlotWriter.WALLET_CONFIRMATION)).isZero();
    }

    @Test
    void aConflictOfARolledBackConfirmationCountsNothing() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.conflict(SlotWriter.WALLET_CONFIRMATION);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(conflicts(SlotWriter.WALLET_CONFIRMATION)).isZero();
    }

    @Test
    void threeRowsSlotMissingCountThree() {
        metrics.slotMissing(3);
        metrics.slotMissing(0);

        assertThat(registry.get("ledger.real.slot_missing").counter().count()).isEqualTo(3.0);
    }

    private double conflicts(SlotWriter writer) {
        return registry.get("ledger.real.slot_conflicts")
                .tag("writer", writer.name())
                .counter()
                .count();
    }
}
