package com.memetrade.marketdata.infra.coingecko;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.shared.config.ProcessRole;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

/**
 * Step 1 and BR1.10 (NFR1.1, NFR1.2, NFR1.6): the settings of the source with the values of the design, the key read
 * only by the worker from {@code COINGECKO_API_KEY}, the allowed hosts outside the test profile and the condition that
 * builds the adapter only when {@code MARKET_SOURCES} names coingecko.
 */
class CoinGeckoConfigTest {

    @Test
    void theDefaultsAreTheValuesOfTheDesign() {
        CoinGeckoProperties defaults = CoinGeckoProperties.defaults();

        assertThat(defaults.baseUrl()).isEqualTo("https://pro-api.coingecko.com/api/v3");
        assertThat(defaults.network()).isEqualTo("solana");
        assertThat(defaults.discoveryLimit()).isEqualTo(50);
        assertThat(defaults.batchSize()).isEqualTo(30);
        assertThat(defaults.discoveryInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(defaults.detailCallsPerMinute()).isEqualTo(2);
        assertThat(defaults.detailRefresh()).isEqualTo(Duration.ofMinutes(60));
        assertThat(defaults.callsPerMinute()).isEqualTo(250);
        assertThat(defaults.monthlyCallBudget()).isEqualTo(450_000L);
        assertThat(defaults.callTimeout()).isEqualTo(Duration.ofSeconds(4));
        assertThat(defaults.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(defaults.roundDeadline()).isEqualTo(Duration.ofSeconds(18));
        assertThat(defaults.lastCallStart()).isEqualTo(Duration.ofSeconds(12));
        assertThat(defaults.attributionText()).isEqualTo("Dados: CoinGecko");
    }

    @Test
    void anOutOfRangeSettingNamesItsVariable() {
        assertThatThrownBy(() -> new CoinGeckoProperties(
                        null, null, null, 31, null, null, null, null, null, null, null, null, null, null, null, null))
                .hasMessageContaining("COINGECKO_BATCH_SIZE");
        assertThatThrownBy(() -> new CoinGeckoProperties(
                        null, null, null, null, null, null, null, null, 0L, null, null, null, null, null, null, null))
                .hasMessageContaining("COINGECKO_MONTHLY_CALL_BUDGET");
        assertThatThrownBy(() -> new CoinGeckoProperties(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(6),
                        null,
                        null,
                        null))
                .hasMessageContaining("COINGECKO_LAST_CALL_START");
        assertThatThrownBy(() -> new CoinGeckoProperties(
                        null, "../x", null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null))
                .hasMessageContaining("COINGECKO_NETWORK");
    }

    @Test
    void outsideTheTestProfileOnlyHttpsOnACoinGeckoHostIsAccepted() {
        assertThatCode(() -> CoinGeckoProperties.defaults().checkProductionBaseUrl())
                .doesNotThrowAnyException();
        assertThatCode(() -> CoinGeckoProperties.defaults()
                        .withBaseUrl("https://api.coingecko.com/api/v3")
                        .checkProductionBaseUrl())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> CoinGeckoProperties.defaults()
                        .withBaseUrl("http://pro-api.coingecko.com/api/v3")
                        .checkProductionBaseUrl())
                .hasMessageContaining("COINGECKO_BASE_URL");
        assertThatThrownBy(() -> CoinGeckoProperties.defaults()
                        .withBaseUrl("https://pro-api.coingecko.com.attacker.example/api/v3")
                        .checkProductionBaseUrl())
                .hasMessageContaining("COINGECKO_BASE_URL");
    }

    @Test
    void theWorkerNeedsTheKeyAndTheMessageNamesOnlyTheVariable() {
        assertThatThrownBy(() -> CoinGeckoConfig.apiKey(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COINGECKO_API_KEY");
        MockEnvironment blank = new MockEnvironment().withProperty("COINGECKO_API_KEY", "  ");
        assertThatThrownBy(() -> CoinGeckoConfig.apiKey(blank)).isInstanceOf(IllegalStateException.class);

        MockEnvironment withKey =
                new MockEnvironment().withProperty("COINGECKO_API_KEY", " " + CoinGeckoFixtures.TEST_KEY);
        assertThat(CoinGeckoConfig.apiKey(withKey)).isEqualTo(CoinGeckoFixtures.TEST_KEY);
    }

    @Test
    void theAdapterIsBuiltOnlyInTheWorkerAndOnlyWhenTheSourcesNameCoinGecko() {
        assertThat(CoinGeckoConfig.class.getAnnotation(ProcessRole.class).value())
                .isEqualTo(ProcessRole.Kind.WORKER);
        assertThat(CoinGeckoConfig.class.getAnnotation(Conditional.class).value())
                .containsExactly(CoinGeckoConfiguredCondition.class);
        assertThat(CoinGeckoCollectorJob.class.getAnnotation(ProcessRole.class).value())
                .isEqualTo(ProcessRole.Kind.WORKER);
        assertThat(CoinGeckoUsageJob.class.getAnnotation(ProcessRole.class).value())
                .isEqualTo(ProcessRole.Kind.WORKER);

        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Probe.class);
        runner.withPropertyValues("memetrade.market.sources=sim-a,coingecko")
                .run(context -> assertThat(context).hasBean("probe"));
        runner.withPropertyValues("memetrade.market.sources=sim-a,sim-b")
                .run(context -> assertThat(context).doesNotHaveBean("probe"));
    }

    @Configuration
    @Conditional(CoinGeckoConfiguredCondition.class)
    static class Probe {

        @Bean
        String probe() {
            return "built";
        }
    }
}
