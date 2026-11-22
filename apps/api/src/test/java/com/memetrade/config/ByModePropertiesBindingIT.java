package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.infra.BotProperties;
import com.memetrade.pnl.infra.PnlByModeProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 261121-paper-real-unico U1, Step 16 (NFR1.9, NFR1.10, NFR8.1; infrastructure-specification.md "Ligação obrigatória
 * por {@code @ConfigurationProperties}"): the three variables of the api task bind to their keys through the relaxed
 * binding of the environment (dots become {@code _}, hyphens vanish), over the defaults of {@code application.yml}; a
 * value out of its range stops the start with a message that names the property.
 */
class ByModePropertiesBindingIT {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({PnlByModeProperties.class, BotProperties.class})
    static class Bound {}

    @Test
    void withoutVariablesTheDefaultsOfTheSharedConfigurationApply() {
        runner(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            PnlByModeProperties byMode = context.getBean(PnlByModeProperties.class);
            assertThat(byMode.blockTimeout()).isEqualTo(Duration.ofMillis(800));
            assertThat(byMode.realMaxOrders()).isEqualTo(50_000);
            assertThat(byMode.poolSize()).isEqualTo(8);
            assertThat(byMode.queueCapacity()).isEqualTo(16);
            assertThat(context.getBean(BotProperties.class).realEligibilityTimeout())
                    .isEqualTo(Duration.ofMillis(500));
        });
    }

    @Test
    void theThreeVariablesOfTheApiTaskBindToTheirKeys() {
        runner(Map.of(
                        "MEMETRADE_PNL_BYMODE_BLOCKTIMEOUT", "700ms",
                        "MEMETRADE_BOTS_REALELIGIBILITYTIMEOUT", "400ms",
                        "MEMETRADE_PNL_BYMODE_REALMAXORDERS", "60000"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PnlByModeProperties byMode = context.getBean(PnlByModeProperties.class);
                    assertThat(byMode.blockTimeout()).isEqualTo(Duration.ofMillis(700));
                    assertThat(byMode.realMaxOrders()).isEqualTo(60_000);
                    assertThat(context.getBean(BotProperties.class).realEligibilityTimeout())
                            .isEqualTo(Duration.ofMillis(400));
                });
    }

    @Test
    void aBlockTimeoutOutOfItsRangeStopsTheStartNamingTheProperty() {
        runner(Map.of("MEMETRADE_PNL_BYMODE_BLOCKTIMEOUT", "950ms")).run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("memetrade.pnl.by-mode.block-timeout"));
    }

    @Test
    void anEligibilityTimeoutOutOfItsRangeStopsTheStartNamingTheProperty() {
        runner(Map.of("MEMETRADE_BOTS_REALELIGIBILITYTIMEOUT", "50ms")).run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("memetrade.bots.real-eligibility-timeout"));
    }

    @Test
    void aMaximumOfOrdersOutOfItsRangeStopsTheStartNamingTheProperty() {
        runner(Map.of("MEMETRADE_PNL_BYMODE_REALMAXORDERS", "1000")).run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("memetrade.pnl.by-mode.real-max-orders"));
    }

    private static ApplicationContextRunner runner(Map<String, Object> environment) {
        return new ApplicationContextRunner().withUserConfiguration(Bound.class).withInitializer(context -> {
            var sources = context.getEnvironment().getPropertySources();
            for (PropertySource<?> shipped : shipped()) {
                sources.addLast(shipped);
            }
            // The name ends with "-systemEnvironment", so Spring Boot maps it as an environment (relaxed binding).
            sources.addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment", environment));
        });
    }

    private static List<PropertySource<?>> shipped() {
        try {
            return new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        } catch (IOException missing) {
            throw new IllegalStateException("application.yml is on the class path", missing);
        }
    }
}
