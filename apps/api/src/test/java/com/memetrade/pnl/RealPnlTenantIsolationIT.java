package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.RealBotPnlLine;
import com.memetrade.pnl.api.RealBotPnlQuery;
import com.memetrade.pnl.api.RealModePnl;
import com.memetrade.testsupport.AbstractPaperRealIT;
import com.memetrade.testsupport.SyntheticIds;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 261121-paper-real-unico U2, Etapa 20 (BR8.6, AC3.2.6, AC5.3.5, NFR3.1 to NFR3.3; team.md "isolamento entre contas"):
 * two people of different tenants trade the same mint in neighbouring slots; the P&L of each one, its reconciliation and
 * its audit event hold nothing of the other, and the bot of the other is never found (404 for the caller).
 */
@Tag("u2-real-bot-pnl")
class RealPnlTenantIsolationIT extends AbstractPaperRealIT {

    @Autowired
    private RealBotPnlQuery query;

    @Test
    void eachPersonSeesOnlyHerOwnLedgerOrdersAndBots() throws Exception {
        String mint = SyntheticIds.mint();
        Person first = signIn(true, true);
        UUID firstWallet = activeWallet(first, 5 * SOL);
        UUID firstBot = realBotRow(first.tenantId(), first.userId());
        Person second = signIn(true, true);
        UUID secondWallet = activeWallet(second, 5 * SOL);
        UUID secondBot = realBotRow(second.tenantId(), second.userId());
        assertThat(first.tenantId()).isNotEqualTo(second.tenantId());
        new RealPnlSeed(
                        jdbc,
                        first.tenantId(),
                        first.userId(),
                        firstWallet,
                        clock.instant().minusSeconds(600),
                        930_000)
                .buy(firstBot, mint, 100, 100_000_000L, 1_000L);
        new RealPnlSeed(
                        jdbc,
                        second.tenantId(),
                        second.userId(),
                        secondWallet,
                        clock.instant().minusSeconds(600),
                        930_000)
                .buy(secondBot, mint, 700, 900_000_000L, 9_000L);

        RealModePnl mine = query.modePnl(first.tenantId(), first.userId(), PnlWindow.all());

        assertThat(mine.lines()).extracting(RealBotPnlLine::botId).containsExactly(firstBot);
        assertThat(mine.total().feesLamports()).isEqualTo(1_000L);
        assertThat(mine.unowned()).isEmpty();
        assertThat(query.botPnl(first.tenantId(), first.userId(), secondBot, PnlWindow.all()))
                .isEmpty();
        assertThat(query.botPnl(second.tenantId(), second.userId(), secondBot, PnlWindow.all()))
                .get()
                .extracting(RealBotPnlLine::feesLamports)
                .isEqualTo(9_000L);
    }

    @Test
    void aMismatchOfOnePersonNeverCarriesAnythingOfTheOther() throws Exception {
        String mint = SyntheticIds.mint();
        Person first = signIn(true, true);
        UUID firstWallet = activeWallet(first, 5 * SOL);
        UUID firstBot = realBotRow(first.tenantId(), first.userId());
        Person second = signIn(true, true);
        UUID secondBot = realBotRow(second.tenantId(), second.userId());
        RealPnlSeed seed = new RealPnlSeed(
                jdbc,
                first.tenantId(),
                first.userId(),
                firstWallet,
                clock.instant().minusSeconds(600),
                940_000);
        seed.buy(firstBot, mint, 100, 100_000_000L, 0);
        seed.orderOnly(secondBot, mint);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> query.modePnl(first.tenantId(), first.userId(), PnlWindow.all()))
                .isInstanceOf(com.memetrade.pnl.api.RealPnlUnavailable.class)
                .satisfies(failure -> assertThat(((com.memetrade.pnl.api.RealPnlUnavailable) failure).causeLabel())
                        .isEqualTo(com.memetrade.pnl.api.RealPnlUnavailable.RECONCILIATION_MISMATCH));

        String event = RealPnlSeed.mismatchEvent(jdbc, first.userId());
        assertThat(event)
                .contains(first.tenantId().toString())
                .doesNotContain(second.tenantId().toString())
                .doesNotContain(second.userId().toString())
                .doesNotContain(secondBot.toString());
    }
}
