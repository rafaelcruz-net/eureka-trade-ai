package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.infra.PnlRealProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

/**
 * 261121-paper-real-unico U2, Etapa 13 (NFR8.6, performance-design.md): the keys of the U2 under
 * {@code memetrade.pnl.real} bind from {@code application.yml} with their defaults; a value out of its range stops the
 * start with a message that names the property. Phase 1: the budget of the read of cost of the rounds and of the cycle
 * (50 ms to 250 ms) and the local {@code work_mem} (1 MB to 12 MB).
 */
class U2PropertiesBindingIT {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({PnlRealProperties.class, com.memetrade.pnl.infra.PnlMismatchAuditProperties.class})
    static class Bound {}

    /** Phase 2 (Etapa 21, D-NFR1, NFR3.15): the budget of the attribution of the P&L and the bounded audit write. */
    @Test
    void thePhase2KeysBindWithTheirDefaults() {
        runner(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PnlRealProperties.class).attribution().pnlTimeout())
                    .isEqualTo(Duration.ofMillis(500));
            com.memetrade.pnl.infra.PnlMismatchAuditProperties audit =
                    context.getBean(com.memetrade.pnl.infra.PnlMismatchAuditProperties.class);
            assertThat(audit.poolSize()).isEqualTo(2);
            assertThat(audit.writeTimeout()).isEqualTo(Duration.ofMillis(200));
        });
    }

    @Test
    void eachPhase2KeyOutOfItsRangeStopsTheStartNamingTheProperty() {
        for (Map.Entry<String, Object> wrong : Map.<String, Object>of(
                        "memetrade.pnl.real.attribution.pnl-timeout", "600ms",
                        "memetrade.pnl.mismatch-audit.pool-size", "3",
                        "memetrade.pnl.mismatch-audit.write-timeout", "300ms")
                .entrySet()) {
            runner(Map.of(wrong.getKey(), wrong.getValue())).run(context -> assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining(wrong.getKey()));
        }
    }

    @Test
    void theDefaultsOfTheSharedConfigurationApply() {
        runner(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            PnlRealProperties real = context.getBean(PnlRealProperties.class);
            assertThat(real.attribution().gateAndCycleTimeout()).isEqualTo(Duration.ofMillis(250));
            assertThat(real.read().workMem()).isEqualTo(DataSize.ofMegabytes(12));
            assertThat(real.read().workMemSetting()).isEqualTo("12288kB");
        });
    }

    @Test
    void aLowerBudgetIsAccepted() {
        runner(Map.of("memetrade.pnl.real.attribution.gate-and-cycle-timeout", "100ms"))
                .run(context -> assertThat(context.getBean(PnlRealProperties.class)
                                .attribution()
                                .gateAndCycleTimeout())
                        .isEqualTo(Duration.ofMillis(100)));
    }

    @Test
    void aBudgetAboveTheDecisionOfTheFounderStopsTheStartNamingTheProperty() {
        runner(Map.of("memetrade.pnl.real.attribution.gate-and-cycle-timeout", "300ms"))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("memetrade.pnl.real.attribution.gate-and-cycle-timeout"));
    }

    @Test
    void aWorkMemOutOfItsRangeStopsTheStartNamingTheProperty() {
        runner(Map.of("memetrade.pnl.real.read.work-mem", "64MB")).run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("memetrade.pnl.real.read.work-mem"));
    }

    private static ApplicationContextRunner runner(Map<String, Object> overrides) {
        return new ApplicationContextRunner().withUserConfiguration(Bound.class).withInitializer(context -> {
            var sources = context.getEnvironment().getPropertySources();
            for (PropertySource<?> shipped : shipped()) {
                sources.addLast(shipped);
            }
            sources.addFirst(new MapPropertySource("test-overrides", overrides));
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
