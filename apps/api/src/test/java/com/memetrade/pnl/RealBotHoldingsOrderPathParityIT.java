package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.pnl.application.HoldingsReaders;
import com.memetrade.pnl.application.RealBotHoldingsReader;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * 261121-paper-real-unico U4, Etapa 3 (written before the code; performance-design.md "Equivalência dos dois caminhos",
 * review R-05; NFR1.11, BR7.1): S4 reads the orders the open lots name by id up to {@link
 * RealBotHoldingsReader#ORDER_PREFETCH_THRESHOLD} distinct orders and through the stream of C13 above it. The same
 * snapshot read both ways gives the same holdings per mint, on both sides of the threshold (exactly 32 and exactly 33
 * distinct orders) and on the history of the scenario of T12. The seeds A and B of NFR1.5 are read both ways inside
 * {@code RealBotHoldingsMeasurementIT}.
 */
@Tag("u4-own-quantity-cycle")
class RealBotHoldingsOrderPathParityIT extends AbstractRealExecutionIT {

    private static final Duration BUDGET = Duration.ofMillis(250);

    @Autowired
    private ApplicationContext context;

    @ParameterizedTest
    @ValueSource(ints = {32, 33})
    void byIdAndByStreamGiveTheSameHoldingsOnBothSidesOfTheThreshold(int orders) {
        Setup setup = realBot("AUTO_FULL");
        UUID b = realBotRow(setup.tenantId(), setup.userId());
        String mint = SyntheticIds.mint();
        RealPnlSeed seed = new RealPnlSeed(
                jdbc,
                setup.tenantId(),
                setup.userId(),
                setup.botWalletId(),
                Instant.now().minusSeconds(86_400),
                10);
        for (int i = 0; i < orders; i++) {
            seed.buy(i % 2 == 0 ? setup.botId() : b, mint, 1_000 + i, 2_000 + i, 5_000);
        }

        HoldingsResult byId =
                reader(Integer.MAX_VALUE).holdings(setup.tenantId(), setup.userId(), setup.botId(), BUDGET);
        HoldingsResult byStream = reader(0).holdings(setup.tenantId(), setup.userId(), setup.botId(), BUDGET);
        HoldingsResult production =
                HoldingsReaders.of(context).holdings(setup.tenantId(), setup.userId(), setup.botId(), BUDGET);

        assertThat(byId).isInstanceOf(HoldingsResult.Available.class);
        assertThat(((HoldingsResult.Available) byStream).holdings())
                .isEqualTo(((HoldingsResult.Available) byId).holdings());
        assertThat(((HoldingsResult.Available) production).holdings())
                .isEqualTo(((HoldingsResult.Available) byId).holdings());
        long expected = 0;
        for (int i = 0; i < orders; i += 2) {
            expected += 1_000 + i;
        }
        assertThat(((HoldingsResult.Available) byId).holdings().getFirst().ownTokenBaseUnits())
                .isEqualTo(expected);
    }

    @Test
    void byIdAndByStreamGiveTheSameHoldingsOnTheScenarioOfT12() {
        Setup setup = realBot("AUTO_FULL");
        UUID b = realBotRow(setup.tenantId(), setup.userId());
        UUID c = realBotRow(setup.tenantId(), setup.userId());
        OwnHoldingsScenario scenario = new OwnHoldingsScenario(
                jdbc, setup.tenantId(), setup.userId(), setup.botWalletId(), setup.botId(), b, c);

        for (UUID bot : scenario.bots()) {
            HoldingsResult byId = reader(Integer.MAX_VALUE).holdings(setup.tenantId(), setup.userId(), bot, BUDGET);
            HoldingsResult byStream = reader(0).holdings(setup.tenantId(), setup.userId(), bot, BUDGET);

            assertThat(byStream).isInstanceOf(HoldingsResult.Available.class);
            assertThat(((HoldingsResult.Available) byStream).holdings())
                    .isEqualTo(((HoldingsResult.Available) byId).holdings());
            assertThat(OwnHoldingsScenario.own(byStream))
                    .usingRecursiveComparison()
                    .isEqualTo(scenario.expected(bot));
        }
    }

    private RealBotHoldingsReader reader(int threshold) {
        return HoldingsReaders.of(context, threshold);
    }
}
