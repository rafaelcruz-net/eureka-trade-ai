package com.memetrade.realexits.application;

import com.memetrade.botwallet.api.EscrowBalances;
import com.memetrade.botwallet.api.OpenExitsCheck;
import com.memetrade.realexecution.api.RealExitChanges;
import com.memetrade.realexits.domain.ConditionalOrder;
import com.memetrade.realexits.infra.JdbcConditionalStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The implementations by {@code realexits} of the extension interfaces declared by the M1 and the M6
 * (logical-components.md "Interfaces de extensão"): the custody accounts for the reconciliation (BR5.3b), the refusal
 * of the anonymization (BR6.9) and the changes for the live channel (BR5.5). Every read filters by tenant (and person).
 */
@Component
public class ExitsExtensions implements EscrowBalances, OpenExitsCheck, RealExitChanges {

    private final JdbcConditionalStore store;

    public ExitsExtensions(JdbcConditionalStore store) {
        this.store = store;
    }

    @Override
    public List<Escrow> openEscrows(UUID tenantId, UUID botWalletId) {
        return store.openAtTheProvider(tenantId, botWalletId).stream()
                .map(conditional -> new Escrow(conditional.positionMint(), conditional.escrowAccount()))
                .toList();
    }

    @Override
    public boolean hasOpenExits(UUID tenantId, UUID userId) {
        return store.hasNotFinal(tenantId, userId);
    }

    @Override
    public long changeMark(UUID tenantId, UUID userId) {
        return store.changeMark(tenantId, userId);
    }

    @Override
    public List<ConditionalChange> changedSince(UUID tenantId, UUID userId, Instant since, int limit) {
        return store.changedSince(tenantId, userId, since, Math.max(1, Math.min(100, limit))).stream()
                .map(ExitsExtensions::change)
                .toList();
    }

    private static ConditionalChange change(ConditionalOrder conditional) {
        return new ConditionalChange(
                conditional.conditionalOrderId(),
                conditional.positionMint(),
                conditional.state().name(),
                conditional.filledSide().map(Enum::name),
                conditional.updatedAt());
    }
}
