package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.pnl.api.UnavailableReason;
import com.memetrade.pnl.application.HoldingsReaders;
import com.memetrade.pnl.application.RealBotHoldingsReader;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.realexecution.OpenSellsFixture;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * 261121-paper-real-unico U4, Etapa 3, T6 (written before the code; BR2.4, NFR3.1, NFR3.2; team.md "isolamento entre
 * contas, inclusive tarefas em segundo plano"): two people of different tenants trade the same mint with orders in the
 * same slots; adding orders, ledger rows and PENDING decisions of one never changes the holdings of the other; the id
 * of a bot of another person or another tenant is BOT_NOT_OWNED and gives no data.
 */
@Tag("u4-own-quantity-cycle")
class RealBotHoldingsTenantIT extends AbstractRealExecutionIT {

    private static final Duration BUDGET = Duration.ofMillis(250);

    @Autowired
    private ApplicationContext context;

    @Test
    void theOrdersRowsAndDecisionsOfAnotherTenantNeverChangeTheHoldingsOfThePerson() {
        Setup mine = realBot("AUTO_FULL");
        Setup theirs = realBot("AUTO_FULL");
        String mint = SyntheticIds.mint();
        Instant start = Instant.now().minusSeconds(3_600);
        new RealPnlSeed(jdbc, mine.tenantId(), mine.userId(), mine.botWalletId(), start, 500)
                .buy(mine.botId(), mint, 100, 1_000, 5_000);
        RealBotHoldingsReader reader = HoldingsReaders.of(context);
        HoldingsResult before = reader.holdings(mine.tenantId(), mine.userId(), mine.botId(), BUDGET);

        RealPnlSeed other = new RealPnlSeed(jdbc, theirs.tenantId(), theirs.userId(), theirs.botWalletId(), start, 500);
        other.buy(theirs.botId(), mint, 900, 9_000, 5_000);
        other.sell(theirs.botId(), mint, 100, 2_000, 5_000);
        new OpenSellsFixture(jdbc).pendingSell(theirs.tenantId(), theirs.userId(), theirs.botId(), mint, 700);
        new OpenSellsFixture(jdbc).order(theirs.tenantId(), theirs.userId(), theirs.botId(), "SELL", mint, 50, "SENT");

        HoldingsResult after = reader.holdings(mine.tenantId(), mine.userId(), mine.botId(), BUDGET);

        assertThat(after).isInstanceOf(HoldingsResult.Available.class);
        assertThat(((HoldingsResult.Available) after).holdings())
                .isEqualTo(((HoldingsResult.Available) before).holdings());
        assertThat(OwnHoldingsScenario.own(after)).containsOnlyKeys(mint);
        assertThat(OwnHoldingsScenario.own(after).get(mint)).containsExactly(100, 1_000);
    }

    @Test
    void aBotOfAnotherPersonOrAnotherTenantIsBotNotOwnedWithNoData() {
        Setup mine = realBot("AUTO_FULL");
        Setup theirs = realBot("AUTO_FULL");
        new RealPnlSeed(jdbc, theirs.tenantId(), theirs.userId(), theirs.botWalletId(), Instant.now(), 900)
                .buy(theirs.botId(), SyntheticIds.mint(), 100, 1_000, 5_000);
        RealBotHoldingsReader reader = HoldingsReaders.of(context);

        // The id of their bot under my tenant and person, and under their tenant with my person.
        assertThat(reader.holdings(mine.tenantId(), mine.userId(), theirs.botId(), BUDGET))
                .isEqualTo(new HoldingsResult.Unavailable(UnavailableReason.BOT_NOT_OWNED));
        assertThat(reader.holdings(theirs.tenantId(), mine.userId(), theirs.botId(), BUDGET))
                .isEqualTo(new HoldingsResult.Unavailable(UnavailableReason.BOT_NOT_OWNED));
    }

    @Test
    void aPaperBotOfThePersonIsNotARealBotAndIsBotNotOwned() {
        Setup mine = realBot("AUTO_FULL");
        java.util.UUID paper = java.util.UUID.randomUUID();
        jdbc.sql("""
                        insert into bot (bot_id, tenant_id, owner_user_id, name, template_id, template_version, state,
                          created_at, updated_at, market)
                        values (:b, :t, :u, 'robo papel', 'MOMENTUM_VOLUME', '1', 'ACTIVE', now(), now(), 'PAPER')""")
                .param("b", paper)
                .param("t", mine.tenantId())
                .param("u", mine.userId())
                .update();

        assertThat(HoldingsReaders.of(context).holdings(mine.tenantId(), mine.userId(), paper, BUDGET))
                .isEqualTo(new HoldingsResult.Unavailable(UnavailableReason.BOT_NOT_OWNED));
    }
}
