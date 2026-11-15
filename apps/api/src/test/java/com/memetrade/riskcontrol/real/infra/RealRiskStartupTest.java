package com.memetrade.riskcontrol.real.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.riskcontrol.real.domain.RealRiskLimits;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Step 12 of the U3 (U3-NFR8.3, security-requirements.md "Limites na configuração"): the api and the worker start with
 * the defaults of {@code application.yml}; outside the profiles {@code test} and {@code local} a looser value of ANY key
 * stops the start with a message naming the key and never the value; a stricter value starts; a value out of range stops
 * the start even in {@code local}.
 */
class RealRiskStartupTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RealRiskProperties.class)
    static class LimitsOnly {
        @Bean
        RealRiskLimits realRiskLimits(RealRiskProperties properties, Environment environment) {
            return new RealRiskConfig().realRiskLimits(properties, environment);
        }
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(LimitsOnly.class);

    @Test
    void withoutAnyKeyTheDefaultsStart() {
        runner.run(context -> assertThat(context.getBean(RealRiskLimits.class)).isEqualTo(RealRiskLimits.DEFAULTS));
    }

    @ParameterizedTest
    @CsvSource({
        "order-max-bps, 1001",
        "daily-loss-max-bps, 2001",
        "daily-buys-max, 21",
        "signal-max-age, 61s",
        "reservation-ttl, 6m"
    })
    void aLooserValueOfAnyKeyStopsTheStartOutsideTheTestProfiles(String key, String value) {
        runner.withPropertyValues("memetrade.real.risk." + key + "=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("memetrade.real.risk." + key)
                    .hasMessageNotContaining(value);
        });
    }

    @ParameterizedTest
    @CsvSource({
        "order-max-bps, 1001",
        "daily-loss-max-bps, 2001",
        "daily-buys-max, 21",
        "signal-max-age, 61s",
        "reservation-ttl, 6m"
    })
    void theLocalProfileAcceptsALooserValueForTests(String key, String value) {
        runner.withPropertyValues("spring.profiles.active=local", "memetrade.real.risk." + key + "=" + value)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void stricterValuesStartInEveryProfile() {
        runner.withPropertyValues(
                        "memetrade.real.risk.order-max-bps=500",
                        "memetrade.real.risk.daily-loss-max-bps=1000",
                        "memetrade.real.risk.daily-buys-max=10",
                        "memetrade.real.risk.signal-max-age=30s",
                        "memetrade.real.risk.reservation-ttl=2m")
                .run(context -> {
                    RealRiskLimits limits = context.getBean(RealRiskLimits.class);
                    assertThat(limits.orderMaxBps()).isEqualTo(500);
                    assertThat(limits.signalMaxAge()).isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    void aValueOutOfRangeStopsTheStartEvenInTheTestProfile() {
        runner.withPropertyValues("spring.profiles.active=test", "memetrade.real.risk.daily-buys-max=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessage("memetrade.real.risk.daily-buys-max is below its valid range");
                });
    }
}
