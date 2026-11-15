package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Step 1 of the U4 (U4-NFR8.1, U4-NFR8.10, U4-NFR3.3, BR4.3): the start-up check of the REAL execution settings and the
 * shipped values of {@code application.yml}.
 */
@Tag("u4-real-execution")
class RealExecutionSettingsTest {

    private static RealExecutionProperties properties(String baseUrl, long maxFee) {
        return new RealExecutionProperties(
                new RealExecutionProperties.Jupiter(baseUrl, Duration.ofSeconds(2), 40, 24),
                Duration.ofSeconds(10),
                Duration.ofSeconds(8),
                Duration.ofSeconds(15),
                Duration.ofSeconds(60),
                Duration.ofSeconds(10),
                maxFee,
                20,
                20,
                3,
                Duration.ofMinutes(5),
                new RealExecutionProperties.Jobs(
                        Duration.ofMillis(200),
                        Duration.ofMillis(200),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(1)));
    }

    @Test
    void theShippedDefaultsPassInProduction() {
        assertThat(RealExecutionSettings.violations(
                        properties("https://lite-api.jup.ag/swap/v1", 1_000_000), false, true, "MAINNET"))
                .isEmpty();
    }

    @Test
    void mainnetWithoutTheProductionProfileIsRefused() {
        assertThat(RealExecutionSettings.violations(
                        properties("https://lite-api.jup.ag/swap/v1", 1_000_000), false, false, "MAINNET"))
                .singleElement()
                .asString()
                .contains("MAINNET");
        assertThat(RealExecutionSettings.violations(
                        properties("http://jupiter-stub:8080", 1_000_000), true, false, "MAINNET"))
                .anySatisfy(v -> assertThat(v).contains("production profile"));
    }

    @Test
    void theStubOfTheLocalComposeIsAcceptedOnlyInTestOrLocal() {
        assertThat(RealExecutionSettings.violations(
                        properties("http://jupiter-stub:8080", 1_000_000), true, false, "LOCALNET"))
                .isEmpty();
        assertThat(RealExecutionSettings.violations(
                        properties("http://jupiter-stub:8080", 1_000_000), false, false, "DEVNET"))
                .singleElement()
                .asString()
                .contains("fixed Jupiter host");
        assertThat(RealExecutionSettings.violations(
                        properties("https://evil.example/swap/v1", 1_000_000), false, true, "MAINNET"))
                .singleElement()
                .asString()
                .contains("fixed Jupiter host");
    }

    @Test
    void aUrlWithAUserOrAQueryIsRefusedEverywhere() {
        assertThat(RealExecutionSettings.violations(
                        properties("https://user@lite-api.jup.ag/swap/v1", 1_000_000), true, false, "LOCALNET"))
                .isNotEmpty();
        assertThat(RealExecutionSettings.violations(
                        properties("https://lite-api.jup.ag/swap/v1?key=1", 1_000_000), true, false, "LOCALNET"))
                .isNotEmpty();
    }

    @Test
    void thePriorityFeeCeilingNeverPassesOneThousandthOfSol() {
        assertThat(RealExecutionSettings.violations(
                        properties("https://lite-api.jup.ag/swap/v1", 1_000_001), true, false, "LOCALNET"))
                .singleElement()
                .asString()
                .contains("max-priority-fee-lamports");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theShippedApplicationYmlHasTheDesignedValues() throws IOException {
        PropertySource<?> yml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();
        String prefix = "memetrade.real.execution.";

        assertThat(String.valueOf(yml.getProperty(prefix + "jupiter.base-url")))
                .isEqualTo("${MEMETRADE_JUPITER_BASE_URL:https://lite-api.jup.ag/swap/v1}");
        assertThat(String.valueOf(yml.getProperty(prefix + "jupiter.timeout"))).isEqualTo("2s");
        assertThat(String.valueOf(yml.getProperty(prefix + "jupiter.max-accounts")))
                .isEqualTo("40");
        assertThat(String.valueOf(yml.getProperty(prefix + "quote-max-age"))).isEqualTo("10s");
        assertThat(String.valueOf(yml.getProperty(prefix + "signing-window"))).isEqualTo("8s");
        assertThat(String.valueOf(yml.getProperty(prefix + "claim-stuck-after")))
                .isEqualTo("15s");
        assertThat(String.valueOf(yml.getProperty(prefix + "confirmation-timeout")))
                .isEqualTo("60s");
        assertThat(String.valueOf(yml.getProperty(prefix + "max-priority-fee-lamports")))
                .isEqualTo("1000000");
        assertThat(String.valueOf(yml.getProperty(prefix + "upstream-failures-to-pause")))
                .isEqualTo("3");
        assertThat(String.valueOf(yml.getProperty(prefix + "upstream-healthy-for")))
                .isEqualTo("5m");
    }
}
