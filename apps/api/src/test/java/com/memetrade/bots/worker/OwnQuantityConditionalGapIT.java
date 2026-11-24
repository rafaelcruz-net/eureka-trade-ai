package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.real.ReconciliationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 261121-paper-real-unico U4, Etapa 4, T18 (written before the code; E11, decision D2 of the owner; BR1.7, BR7.2,
 * AC6.1.2): the accepted residual risk. A records a SELL of its 100 XYZ; before the queue processes it, a conditional
 * sells 200 XYZ of the wallet, shared 50 and 150, so A owns 50 XYZ. The consumer cuts only by the wallet and sells 100
 * XYZ: 50 of A and 50 of B, which the P&L shows as "sold by another bot". The bots plus "Sem bot" still match the REAL
 * ledger exactly and no mismatch is counted.
 */
@Tag("u4-own-quantity-cycle")
class OwnQuantityConditionalGapIT extends AbstractOwnQuantityIT {

    @Autowired
    private MeterRegistry registry;

    @Test
    void e11AConditionalBetweenTheReadAndTheQueueTakesPartOfBAndTheLedgerStillMatches() {
        Pair pair = pair();
        OwnQuantityCycle cycle = cycle(true).sellsAll(pair.xyz());
        cycle.run(pair.refA(), slot());
        assertThat(sellDecisions(pair.a())).containsExactly(A_UNITS);

        pair.seed().conditional(pair.xyz(), 200_000_000L, 300_000_000L, 5_000L);
        settleSale(pendingSellOf(pair.a()), A_UNITS, 150_000_000L, 5_000L, 5_000);

        assertThat(line(pair, pair.b()).shares())
                .as("the part of B sold by the order of A")
                .filteredOn(share -> "SOLD_BY_OTHER_BOT".equals(share.origin()))
                .singleElement()
                .satisfies(share -> assertThat(share.tokenBaseUnits()).isEqualTo(50_000_000L));
        assertThat(reconciliation(pair)).isEqualTo(ReconciliationResult.Status.MATCH);
        Counter mismatches = registry.find("pnl_real_reconciliation_mismatches").counter();
        assertThat(mismatches == null ? 0.0 : mismatches.count()).isZero();
    }
}
