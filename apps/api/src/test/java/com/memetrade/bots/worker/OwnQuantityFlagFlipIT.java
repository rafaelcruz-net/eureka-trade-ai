package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.real.ReconciliationResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U4, Etapa 5, T19 (written before the code; BR5.5, BR7.2, AC6.2.2): a SELL decision recorded
 * with the flag off (the position of the wallet) and processed after the flag was turned on sells as today, its excess
 * goes to the proration of the U2 ("sold by another bot") and the reconciliation closes. The read-only query of the
 * runbook, {@code jsonb_exists(d.context, 'ownQuantity')}, finds exactly that decision.
 */
@Tag("u4-own-quantity-cycle")
class OwnQuantityFlagFlipIT extends AbstractOwnQuantityIT {

    @Test
    void aSaleRecordedBeforeTheTurnSellsAsTodayAndTheQueryOfTheRunbookFindsIt() {
        Pair pair = pair();
        OwnQuantityCycle cycle = cycle(false).sellsAll(pair.xyz());
        cycle.run(pair.refA(), slot());
        UUID old = pendingSellOf(pair.a());

        cycle.flag.on = true;
        settleSale(old, A_UNITS + B_UNITS, 800_000_000L, 5_000L, 5_000);

        assertThat(line(pair, pair.b()).shares())
                .filteredOn(share -> "SOLD_BY_OTHER_BOT".equals(share.origin()))
                .singleElement()
                .satisfies(share -> assertThat(share.tokenBaseUnits()).isEqualTo(B_UNITS));
        assertThat(reconciliation(pair)).isEqualTo(ReconciliationResult.Status.MATCH);

        // The cycle after the turn records its note; the query of the runbook keeps finding only the old decision.
        // A new buy of A, after the sale on the network (its slot follows the slot 5,000 of the settlement).
        pair.seed().slotAt(6_000).buy(pair.a(), pair.xyz(), 10L, 10L, 5_000);
        cycle.run(pair.refA(), nextSlot());
        List<UUID> withoutNote = transactions.execute(status -> {
            jdbc.sql("set transaction isolation level repeatable read").update();
            return jdbc.sql("""
                            SELECT r.decision_request_id
                              FROM real_decision_request r JOIN decision d ON d.decision_id = r.decision_id
                             WHERE r.side = 'SELL' AND r.bot_id = :bot
                               AND NOT jsonb_exists(d.context, 'ownQuantity')""").param("bot", pair.a()).query(UUID.class).list();
        });
        assertThat(withoutNote).containsExactly(old);
        assertThat(sellDecisions(pair.a())).hasSize(2);
    }
}
