package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.RealBotPnlQuery;
import com.memetrade.pnl.api.RealModePnl;
import com.memetrade.pnl.api.RealPnlUnavailable;
import com.memetrade.testsupport.AbstractPaperRealIT;
import com.memetrade.testsupport.SyntheticIds;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 261121-paper-real-unico U2, Etapa 20 (BR10.2, F8, NFR1.10, NFR1.22, ADR-004): the read of the P&L REAL writes
 * nothing: three reads that reconcile leave every table as it was. A mismatch writes only its audit event; nothing is
 * kept between reads, so the next read reconciles again and is ready once the cause is gone.
 */
@Tag("u2-real-bot-pnl")
class RealPnlIdempotentReadIT extends AbstractPaperRealIT {

    private static final List<String> TABLES =
            List.of("real_ledger_entry", "real_entry_slot", "real_order", "real_position_epoch", "audit_outbox", "bot");

    @Autowired
    private RealBotPnlQuery query;

    @Test
    void threeReadsThatReconcileWriteNothingAndAMismatchIsNotKept() throws Exception {
        Person person = signIn(true, true);
        UUID wallet = activeWallet(person, 5 * SOL);
        UUID bot = realBotRow(person.tenantId(), person.userId());
        String mint = SyntheticIds.mint();
        RealPnlSeed seed = new RealPnlSeed(
                jdbc,
                person.tenantId(),
                person.userId(),
                wallet,
                clock.instant().minusSeconds(600),
                950_000);
        seed.buy(bot, mint, 100, 100_000_000L, 1_000L);
        seed.sell(bot, mint, 30, 45_000_000L, 1_000L);
        List<Long> before = counts();

        RealModePnl first = query.modePnl(person.tenantId(), person.userId(), PnlWindow.all());
        RealModePnl second = query.modePnl(person.tenantId(), person.userId(), PnlWindow.all());
        RealModePnl third = query.modePnl(person.tenantId(), person.userId(), PnlWindow.all());

        assertThat(counts()).isEqualTo(before);
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);

        divergingCost.diverge.set(true);
        assertThatThrownBy(() -> query.modePnl(person.tenantId(), person.userId(), PnlWindow.all()))
                .isInstanceOf(RealPnlUnavailable.class);
        divergingCost.diverge.set(false);

        assertThat(query.modePnl(person.tenantId(), person.userId(), PnlWindow.all()))
                .isEqualTo(first);
    }

    private List<Long> counts() {
        return TABLES.stream()
                .map(table -> jdbc.sql("select count(*) from " + table)
                        .query(Long.class)
                        .single())
                .toList();
    }
}
