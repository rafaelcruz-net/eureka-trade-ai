package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.infra.MarketDataProperties;
import com.memetrade.scanner.infra.ScannerProperties;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.FixtureMarketSource;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** The U4 command runs green before the first test of the Unit: the context starts with the U4 settings. */
@Tag("u4")
class U4ContextIT extends AbstractPostgresIT {

    @Autowired
    private MarketDataProperties market;

    @Autowired
    private ScannerProperties scanner;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    @Qualifier("marketSourceCalls")
    private ThreadPoolTaskExecutor sourceCalls;

    @Autowired
    @Qualifier("riskRecalc")
    private ThreadPoolTaskExecutor riskRecalc;

    @Test
    void theContextStartsWithTheDefaultsOfTheInfrastructureSpecification() throws Exception {
        assertThat(market.sources()).containsExactly("sim-a", "sim-b");
        assertThat(market.collectInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(market.divergenceTolerancePercent()).isEqualByComparingTo(new BigDecimal("5.0000"));
        assertThat(market.simulated().seed()).isEqualTo(42);
        assertThat(market.simulated().tokenCount()).isEqualTo(200);
        // The integration base raises the limit for page walks; the shipped default stays 60.
        assertThat(scanner.rateLimitPerMinute()).isEqualTo(10_000);
        assertThat(new org.springframework.boot.env.YamlPropertySourceLoader()
                        .load("application.yml", new org.springframework.core.io.ClassPathResource("application.yml"))
                        .getFirst()
                        .getProperty("memetrade.scanner.rate-limit-per-minute")
                        .toString())
                .isEqualTo("${SCANNER_RATE_LIMIT_PER_MINUTE:60}");
        // U6: one thread per scheduled job of U1 to U7 (RuntimeSettingsGuard); U5 adds the live channel poller and U8
        // its two jobs.
        assertThat(environment.getProperty("spring.task.scheduling.pool.size")).isEqualTo("16");
        assertThat(((HikariDataSource) unwrap(dataSource)).getMaximumPoolSize()).isEqualTo(20);
    }

    @Test
    void theTwoU4ExecutorsHaveTheirFixedSizes() {
        assertThat(sourceCalls.getMaxPoolSize()).isEqualTo(2);
        assertThat(sourceCalls.getThreadNamePrefix()).isEqualTo("market-source-calls-");
        assertThat(riskRecalc.getMaxPoolSize()).isEqualTo(1);
        assertThat(riskRecalc.getThreadNamePrefix()).isEqualTo("risk-recalc-");
    }

    @Test
    void theRecordedFixturesShareTwoTokensByChainAndMint() {
        var a = FixtureMarketSource.load("sim-a").fetch(new FixtureRequest(clock.instant()).request());
        var b = FixtureMarketSource.load("sim-b").fetch(new FixtureRequest(clock.instant()).request());

        assertThat(a.tokens()).extracting(t -> t.token().mint()).contains(FixtureMarketSource.COMMON_A);
        assertThat(b.tokens()).extracting(t -> t.token().mint()).contains(FixtureMarketSource.COMMON_A);
        assertThat(a.observations())
                .allMatch(o -> o.value().isKnown() || o.field().name().equals("HOLDERS"));
    }

    private static DataSource unwrap(DataSource candidate) {
        try {
            return candidate.unwrap(HikariDataSource.class);
        } catch (java.sql.SQLException notHikari) {
            return candidate;
        }
    }

    private record FixtureRequest(java.time.Instant at) {
        com.memetrade.marketdata.api.MarketSourceAdapter.FetchRequest request() {
            return new com.memetrade.marketdata.api.MarketSourceAdapter.FetchRequest(at);
        }
    }
}
