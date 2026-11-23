package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.ledger.api.NetworkOrderedEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.RealBotPnlQuery;
import com.memetrade.pnl.api.RealModePnl;
import com.memetrade.testsupport.AbstractPaperRealIT;
import com.memetrade.testsupport.SyntheticIds;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 261121-paper-real-unico U2, Etapa 20 (NFR4.2, NFR4.3; project.md "REAL e PAPER estritamente separados"): with orders
 * in both modes, the stream of the REAL ledger delivers no PAPER row, and the P&L REAL (lines, total, reconciliation)
 * does not change when PAPER trades are added for the same person.
 */
@Tag("u2-real-bot-pnl")
class RealNeverPaperIT extends AbstractPaperRealIT {

    @Autowired
    private RealBotPnlQuery query;

    @Autowired
    private RealLedger realLedger;

    @Test
    void theRealPnlNeverReadsNorChangesWithThePaperLedger() throws Exception {
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
                960_000);
        seed.buy(bot, mint, 100, 100_000_000L, 1_000L);
        RealModePnl before = query.modePnl(person.tenantId(), person.userId(), PnlWindow.all());

        paperBot(person, "Simulado A", "12.400000", "0.400000");
        paperBot(person, "Simulado B", "-3.100000", "0.100000");

        List<NetworkOrderedEntry> rows = new ArrayList<>();
        transactions.executeWithoutResult(status -> realLedger.streamInNetworkOrder(
                person.tenantId(), List.of(wallet), EnumSet.allOf(RealEntryKind.class), rows::add));
        assertThat(rows).isNotEmpty().allSatisfy(row -> assertThat(row.botWalletId())
                .isEqualTo(wallet));
        assertThat(query.modePnl(person.tenantId(), person.userId(), PnlWindow.all()))
                .isEqualTo(before);
    }

    /** An ACTIVE PAPER bot of the person with a PAPER account and one closed day of realized and fees. */
    private UUID paperBot(Person person, String name, String realized, String fees) {
        com.memetrade.testsupport.LedgerRows rows = new com.memetrade.testsupport.LedgerRows(jdbc);
        UUID botId = rows.account(principal(person), "OPEN");
        jdbc.sql("insert into bot_owner (tenant_id, owner_user_id, created_at) values (:t, :u, now())"
                        + " on conflict do nothing")
                .param("t", person.tenantId())
                .param("u", person.userId())
                .update();
        jdbc.sql("""
                        insert into bot (bot_id, tenant_id, owner_user_id, name, template_id, template_version, state,
                          created_at, updated_at, market)
                        values (:b, :t, :u, :name, 'MOMENTUM_VOLUME', 'v1', 'ACTIVE', clock_timestamp(),
                          clock_timestamp(), 'PAPER')""")
                .param("b", botId)
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("name", name)
                .update();
        rows.daily(principal(person), botId, java.time.LocalDate.of(2026, 10, 27), realized, fees, 1);
        return botId;
    }
}
