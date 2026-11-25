package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.api.BotHolding;
import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.pnl.application.HoldingsReaders;
import com.memetrade.pnl.application.StatementLog;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * 261121-paper-real-unico U4, Etapa 3, and loop-back 1, Etapa 32 (written before the code; decision F6 of the founder;
 * performance-design.md "Equivalência dos dois caminhos"; NFR1.1, NFR1.4, NFR2.9, BR7.1): S4 reads the orders the open
 * lots name BY ID at every size, in batches of at most 1,000 ids, and never through the stream of C13.
 *
 * <ul>
 *   <li>the holdings are exact against an oracle computed from the seed (own quantity, own cost, open sales, available)
 *       and equal to the reader of production, on both sides of the old threshold (32 and 33 distinct orders) with and
 *       without a mark of zero in the history (seeds A and B), and with 5,000 distinct orders;
 *   <li>counted on a data source of the test: at most {@code ceil(n / 1000)} statements of orders by id, no read of one
 *       order on the spot, and no statement of the stream of C13;
 *   <li>the history of the scenario of T12 gives the holdings computed by hand.
 * </ul>
 *
 * The value assertions come first: in the red run (before Etapa 33) they hold on the OLD path (the stream above 32),
 * which proves the oracle equal to the old path; the counts then fail. After Etapa 33 the same values hold on the new
 * path, so the old and the new paths give the same holdings.
 */
@Tag("u4-own-quantity-cycle")
class RealBotHoldingsOrderPathParityIT extends AbstractRealExecutionIT {

    /** A budget that never bites: this is a test of equivalence, not of time (the measurement is another IT). */
    private static final Duration GENEROUS = Duration.ofSeconds(30);

    private static final int BATCH = 1_000;

    @Autowired
    private ApplicationContext context;

    @ParameterizedTest(name = "seed {0}, {1} distinct orders")
    @CsvSource({"A, 32", "A, 33", "B, 32", "B, 33", "B, 5000"})
    void s4ReadsTheOrdersByIdInBatchesAtEverySizeWithTheSameHoldings(String seedName, int orders) {
        Setup setup = realBot("AUTO_FULL");
        UUID b = realBotRow(setup.tenantId(), setup.userId());
        String mint = SyntheticIds.mint();
        RealPnlSeed seed = new RealPnlSeed(
                jdbc,
                setup.tenantId(),
                setup.userId(),
                setup.botWalletId(),
                Instant.now().minusSeconds(86_400L * 30),
                10);
        if ("A".equals(seedName)) {
            // Seed A: the wallet reaches zero once before the lots measured (a mark of zero, a new epoch).
            seed.buy(setup.botId(), mint, 700, 1_400, 5_000);
            seed.buy(b, mint, 300, 600, 5_000);
            seed.sell(setup.botId(), mint, 700, 1_500, 5_000);
            seed.sell(b, mint, 300, 650, 5_000);
            ledger.markEpochs(setup.tenantId(), setup.botWalletId());
        }
        long ownUnits = 0;
        long ownCost = 0;
        for (int i = 0; i < orders; i++) {
            UUID holder = i % 2 == 0 ? setup.botId() : b;
            seed.buy(holder, mint, 1_000 + i, 2_000 + i, 5_000);
            if (holder.equals(setup.botId())) {
                ownUnits += 1_000 + i;
                ownCost += 2_000 + i;
            }
        }
        ledger.markEpochs(setup.tenantId(), setup.botWalletId());

        StatementLog log = new StatementLog();
        HoldingsResult counted = HoldingsReaders.counted(context, log)
                .holdings(setup.tenantId(), setup.userId(), setup.botId(), GENEROUS);
        HoldingsResult production =
                HoldingsReaders.of(context).holdings(setup.tenantId(), setup.userId(), setup.botId(), GENEROUS);

        // 1. The holdings (first, so the red run shows them equal on the old path).
        assertThat(counted).isInstanceOf(HoldingsResult.Available.class);
        assertThat(((HoldingsResult.Available) counted).holdings())
                .containsExactly(new BotHolding(mint, ownUnits, ownCost, 0, ownUnits));
        assertThat(((HoldingsResult.Available) production).holdings())
                .isEqualTo(((HoldingsResult.Available) counted).holdings());

        // 2. The statements of S4 against real_order.
        long streamed = log.count("from real_order where user_id = ?");
        long single = log.count("from real_order where real_order_id = ? and tenant_id");
        long byId = log.count("from real_order where real_order_id = any(");
        assertThat(streamed).as("the stream of C13 is never used by S4").isZero();
        assertThat(single).as("no order is read alone on the spot").isZero();
        assertThat(byId)
                .as("at most ceil(%d / %d) statements of orders by id", orders, BATCH)
                .isBetween(1L, (long) Math.ceil(orders / (double) BATCH));
    }

    @Test
    void theHistoryOfTheScenarioOfT12GivesTheHoldingsComputedByHand() {
        Setup setup = realBot("AUTO_FULL");
        UUID b = realBotRow(setup.tenantId(), setup.userId());
        UUID c = realBotRow(setup.tenantId(), setup.userId());
        OwnHoldingsScenario scenario = new OwnHoldingsScenario(
                jdbc, setup.tenantId(), setup.userId(), setup.botWalletId(), setup.botId(), b, c);

        for (UUID bot : scenario.bots()) {
            StatementLog log = new StatementLog();
            HoldingsResult counted =
                    HoldingsReaders.counted(context, log).holdings(setup.tenantId(), setup.userId(), bot, GENEROUS);
            HoldingsResult production =
                    HoldingsReaders.of(context).holdings(setup.tenantId(), setup.userId(), bot, GENEROUS);

            assertThat(counted).isInstanceOf(HoldingsResult.Available.class);
            assertThat(((HoldingsResult.Available) production).holdings())
                    .isEqualTo(((HoldingsResult.Available) counted).holdings());
            assertThat(OwnHoldingsScenario.own(counted))
                    .usingRecursiveComparison()
                    .isEqualTo(scenario.expected(bot));
            assertThat(log.count("from real_order where user_id = ?")).isZero();
        }
    }
}
