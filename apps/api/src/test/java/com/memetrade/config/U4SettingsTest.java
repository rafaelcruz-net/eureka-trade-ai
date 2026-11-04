package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.marketdata.infra.MarketDataProperties;
import com.memetrade.marketdata.infra.MarketDataProperties.Simulated;
import com.memetrade.scanner.infra.ScannerProperties;
import com.memetrade.shared.config.RuntimeSettingsGuard;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Start-up range checks of the U4 variables (infrastructure-specification.md): every value out of range stops the api
 * with a message that names the variable.
 */
@Tag("u4")
class U4SettingsTest {

    private static final Simulated SIMULATED = new Simulated(42, 200);
    private static final BigDecimal TOLERANCE = new BigDecimal("5.0000");

    @Test
    void theDefaultsOfTheSpecificationAreAccepted() {
        assertThatCode(() -> new MarketDataProperties(
                        List.of("sim-a", "sim-b"), Duration.ofSeconds(30), TOLERANCE, SIMULATED))
                .doesNotThrowAnyException();
        assertThat(new ScannerProperties(60).rateLimitPerMinute()).isEqualTo(60);
    }

    @Test
    void theCollectionIntervalAcceptsFiveToSixtySecondsAndNamesTheVariableOtherwise() {
        assertThatCode(() -> market(Duration.ofSeconds(5))).doesNotThrowAnyException();
        assertThatCode(() -> market(Duration.ofSeconds(60))).doesNotThrowAnyException();
        assertThatThrownBy(() -> market(Duration.ofMillis(4_999))).hasMessageContaining("MARKET_COLLECTION_INTERVAL");
        assertThatThrownBy(() -> market(Duration.ofSeconds(61))).hasMessageContaining("MARKET_COLLECTION_INTERVAL");
    }

    @Test
    void onlyKnownDistinctSimulatedSourcesAreAccepted() {
        assertThatCode(() -> sources(List.of("sim-b"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> sources(List.of())).hasMessageContaining("MARKET_SOURCES");
        assertThatThrownBy(() -> sources(List.of("sim-a", "real-dex"))).hasMessageContaining("MARKET_SOURCES");
        assertThatThrownBy(() -> sources(List.of("sim-a", "sim-a"))).hasMessageContaining("MARKET_SOURCES");
    }

    @Test
    void theToleranceIsAPercentageWithAtMostFourDecimals() {
        assertThatCode(() -> tolerance("0")).doesNotThrowAnyException();
        assertThatCode(() -> tolerance("100.0000")).doesNotThrowAnyException();
        assertThatThrownBy(() -> tolerance("-0.0001")).hasMessageContaining("MARKET_DIVERGENCE_TOLERANCE_PERCENT");
        assertThatThrownBy(() -> tolerance("100.0001")).hasMessageContaining("MARKET_DIVERGENCE_TOLERANCE_PERCENT");
        assertThatThrownBy(() -> tolerance("5.00001")).hasMessageContaining("MARKET_DIVERGENCE_TOLERANCE_PERCENT");
    }

    @Test
    void tokensPerSourceGoFromFiftyToTenThousand() {
        assertThatCode(() -> new Simulated(42, 50)).doesNotThrowAnyException();
        assertThatCode(() -> new Simulated(42, 10_000)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new Simulated(42, 49)).hasMessageContaining("MARKET_SIMULATED_TOKENS_PER_SOURCE");
        assertThatThrownBy(() -> new Simulated(42, 10_001)).hasMessageContaining("MARKET_SIMULATED_TOKENS_PER_SOURCE");
    }

    @Test
    void theRateLimitGoesFromOneToTenThousand() {
        assertThatCode(() -> new ScannerProperties(1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new ScannerProperties(0)).hasMessageContaining("SCANNER_RATE_LIMIT_PER_MINUTE");
        assertThatThrownBy(() -> new ScannerProperties(10_001)).hasMessageContaining("SCANNER_RATE_LIMIT_PER_MINUTE");
    }

    @Test
    void thePoolSizesAreCheckedAtStartUpNamingTheVariable() {
        assertThatCode(() -> new RuntimeSettingsGuard(environment("20", "11"))).doesNotThrowAnyException();
        assertThatCode(() -> new RuntimeSettingsGuard(new MockEnvironment())).doesNotThrowAnyException();
        assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("9", "11")))
                .hasMessageContaining("DB_POOL_MAX_SIZE");
        assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("51", "11")))
                .hasMessageContaining("DB_POOL_MAX_SIZE");
        assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("twenty", "11")))
                .hasMessageContaining("DB_POOL_MAX_SIZE");
        assertThatThrownBy(() -> new RuntimeSettingsGuard(environment("20", "6")))
                .hasMessageContaining("SCHEDULER_POOL_SIZE");
    }

    private static MockEnvironment environment(String pool, String scheduler) {
        return new MockEnvironment()
                .withProperty("spring.datasource.hikari.maximum-pool-size", pool)
                .withProperty("spring.task.scheduling.pool.size", scheduler);
    }

    private static MarketDataProperties market(Duration interval) {
        return new MarketDataProperties(List.of("sim-a", "sim-b"), interval, TOLERANCE, SIMULATED);
    }

    private static MarketDataProperties sources(List<String> sources) {
        return new MarketDataProperties(sources, Duration.ofSeconds(30), TOLERANCE, SIMULATED);
    }

    private static MarketDataProperties tolerance(String value) {
        return new MarketDataProperties(List.of("sim-a"), Duration.ofSeconds(30), new BigDecimal(value), SIMULATED);
    }
}
