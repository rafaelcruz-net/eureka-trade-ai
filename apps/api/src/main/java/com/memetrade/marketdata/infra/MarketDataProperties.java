package com.memetrade.marketdata.infra;

import com.memetrade.marketdata.infra.simulated.SimulatedMarketSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Market settings of U4 (infrastructure-specification.md). Every value is checked when the api starts; a value out of
 * range stops it with a message that names the environment variable, never the value of a secret (none of these is a
 * secret).
 *
 * @param sources active sources ({@code MARKET_SOURCES}), a non-empty subset of {@code sim-a,sim-b,coingecko}
 * @param collectInterval interval of each source ({@code MARKET_COLLECTION_INTERVAL}), 5 s to 60 s
 * @param divergenceTolerancePercent tolerance per field in percentage points, scale up to 4, 0 to 100
 *     ({@code MARKET_DIVERGENCE_TOLERANCE_PERCENT})
 * @param simulated seed and tokens per source of the simulated adapters
 */
@ConfigurationProperties(prefix = "memetrade.market")
public record MarketDataProperties(
        List<String> sources, Duration collectInterval, BigDecimal divergenceTolerancePercent, Simulated simulated) {

    /** Identifiers of the simulated adapters (local and test environments only with REAL on, BR1.6 of U2). */
    public static final Set<String> SIMULATED_SOURCES = Set.of("sim-a", "sim-b");

    /** 261113-trading-real-bot U2: the real source {@code coingecko}, collected only by the worker. */
    public static final String COINGECKO = "coingecko";

    /** Every source id accepted in {@code MARKET_SOURCES}: the simulated ones and the real CoinGecko (U2). */
    public static final Set<String> KNOWN_SOURCES = Set.of("sim-a", "sim-b", COINGECKO);

    static final Duration MIN_INTERVAL = Duration.ofSeconds(5);
    static final Duration MAX_INTERVAL = Duration.ofSeconds(60);
    static final BigDecimal MAX_TOLERANCE = new BigDecimal("100");

    public MarketDataProperties {
        if (sources == null
                || sources.isEmpty()
                || !KNOWN_SOURCES.containsAll(sources)
                || new LinkedHashSet<>(sources).size() != sources.size()) {
            throw invalid(
                    "MARKET_SOURCES", "a comma-separated list of distinct values among sim-a, sim-b and coingecko");
        }
        sources = List.copyOf(sources);
        if (collectInterval == null
                || collectInterval.compareTo(MIN_INTERVAL) < 0
                || collectInterval.compareTo(MAX_INTERVAL) > 0) {
            throw invalid("MARKET_COLLECTION_INTERVAL", "between 5s and 60s");
        }
        if (divergenceTolerancePercent == null
                || divergenceTolerancePercent.signum() < 0
                || divergenceTolerancePercent.compareTo(MAX_TOLERANCE) > 0
                || Math.max(0, divergenceTolerancePercent.stripTrailingZeros().scale()) > 4) {
            throw invalid("MARKET_DIVERGENCE_TOLERANCE_PERCENT", "a decimal from 0 to 100 with at most 4 decimals");
        }
        if (simulated == null) {
            throw invalid("MARKET_SIMULATED_TOKENS_PER_SOURCE", "configured");
        }
    }

    /** True when {@code MARKET_SOURCES} names a simulated source (sim-a or sim-b). */
    public boolean anySimulated() {
        return sources.stream().anyMatch(SIMULATED_SOURCES::contains);
    }

    /** True when {@code MARKET_SOURCES} names the real CoinGecko source. */
    public boolean coingeckoConfigured() {
        return sources.contains(COINGECKO);
    }

    /**
     * @param seed fixed seed of the simulated adapters ({@code MARKET_SIMULATED_SEED})
     * @param tokenCount tokens per source ({@code MARKET_SIMULATED_TOKENS_PER_SOURCE}), 50 to 10000; the first 50 are
     *     common to sim-a and sim-b
     */
    public record Simulated(long seed, int tokenCount, SimulatedMarketSource.Scenario scenario) {

        public static final int MIN_TOKENS = 50;
        public static final int MAX_TOKENS = 10_000;

        /**
         * @param scenario U5: {@code STANDARD} (the default) or {@code FREQUENT_SIGNALS} ({@code
         *     MARKET_SIMULATED_SCENARIO}), which only the test profile accepts ({@code MarketDataConfig})
         */
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Simulated {
            if (tokenCount < MIN_TOKENS || tokenCount > MAX_TOKENS) {
                throw invalid("MARKET_SIMULATED_TOKENS_PER_SOURCE", "between 50 and 10000");
            }
            if (scenario == null) {
                scenario = SimulatedMarketSource.Scenario.STANDARD;
            }
        }

        /** U4 form: the standard scenario. */
        public Simulated(long seed, int tokenCount) {
            this(seed, tokenCount, SimulatedMarketSource.Scenario.STANDARD);
        }
    }

    static IllegalArgumentException invalid(String variable, String expected) {
        return new IllegalArgumentException(variable + " is out of range: expected " + expected);
    }
}
