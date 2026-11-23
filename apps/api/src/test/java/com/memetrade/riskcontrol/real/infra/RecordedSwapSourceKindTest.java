package com.memetrade.riskcontrol.real.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.SourceKind;
import com.memetrade.riskassessment.api.RiskAssessmentQuery;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.shared.config.RecordedSwapFixture;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 261113-trading-real-bot U4 (BR1.2 of the U3 kept): only the TEST profile with the recorded swap reads the source as
 * REAL, and only for orders of the recorded mint; every other mint, and every order without the recording, keeps the
 * kind of the configured sources, so a simulated source still refuses them.
 */
class RecordedSwapSourceKindTest {

    private static final String MINT = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263";
    private static final String OTHER = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";

    private final MarketDataQuery market = mock(MarketDataQuery.class);
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    private RealGateInputsReader reader(Optional<RecordedSwapFixture> fixture) {
        when(market.activeSourceKind()).thenReturn(SourceKind.SIMULATED);
        return new RealGateInputsReader(
                mock(RealStateQuery.class),
                mock(RealLedger.class),
                market,
                mock(RiskAssessmentQuery.class),
                mock(JdbcRealRiskStore.class),
                mock(com.memetrade.pnl.api.RealWalletCost.class),
                fixture);
    }

    private OrderIntent buy(String mint) {
        return OrderIntent.botBuy(tenant, user, UUID.randomUUID(), UUID.randomUUID(), mint, 50_000_000L);
    }

    @Test
    void theRecordedMintReadsRealForTheBuyAndTheLiquidationSell() {
        RealGateInputsReader reader =
                reader(Optional.of(new RecordedSwapFixture(MINT, 5, 50_000_000L, 154_230_000_000L)));

        assertThat(reader.source(buy(MINT))).isEqualTo(SourceKind.REAL);
        assertThat(reader.source(OrderIntent.userLiquidation(tenant, user, MINT, 154_230_000_000L)))
                .isEqualTo(SourceKind.REAL);
    }

    @Test
    void anyOtherMintKeepsTheSimulatedSourceAndItsRefusal() {
        RealGateInputsReader reader =
                reader(Optional.of(new RecordedSwapFixture(MINT, 5, 50_000_000L, 154_230_000_000L)));

        assertThat(reader.source(buy(OTHER))).isEqualTo(SourceKind.SIMULATED);
    }

    @Test
    void withoutTheRecordingTheRecordedMintIsSimulatedToo() {
        RealGateInputsReader reader = reader(Optional.empty());

        assertThat(reader.source(buy(MINT))).isEqualTo(SourceKind.SIMULATED);
        assertThat(reader.source()).isEqualTo(SourceKind.SIMULATED);
    }
}
